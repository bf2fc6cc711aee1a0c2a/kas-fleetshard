package org.bf2.kas.fleetshard.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.cloud.tools.jib.api.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.api.Containerizer;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.CredentialRetriever;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.Jib;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.rbac.ClusterRole;
import io.fabric8.kubernetes.api.model.rbac.Role;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.ClusterServiceVersion;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.IconBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.StrategyDeploymentPermissionsBuilder;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.StrategyDeploymentSpec;
import io.fabric8.openshift.api.model.operatorhub.v1alpha1.StrategyDeploymentSpecBuilder;
import io.fabric8.zjsonpatch.JsonPatch;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BundleAssembler {

    static final String ANNOTATIONS_PATH = "metadata/annotations.yaml";

    static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            new YAMLFactory()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                .disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID));

    public static void main(String[] args) throws Exception {
        BundleAssembler assembler = new BundleAssembler(ConfigProvider.getConfig());

        try {
            assembler.initialize();
            assembler.copyResources();
            assembler.mergeClusterServiceVersions();
            assembler.buildAndPushImage();
        } finally {
            assembler.complete();
        }
    }

    final Config config;
    final String version;
    Path bundleDir;
    ZipFile operatorBundle;
    ZipFile syncBundle;
    List<Map<String, ?>> almExamples;

    public BundleAssembler(Config config) {
        this.config = config;
        version = config.getOptionalValue("kas.bundle.version", String.class).orElse("999.999.999").toLowerCase();
    }

    void initialize() throws IOException {
        bundleDir = Files.createDirectories(Path.of(config.getValue("kas.bundle.output-directory", String.class)));
        Files.createDirectories(bundleDir.resolve("manifests"));
        Files.createDirectories(bundleDir.resolve("metadata"));
        operatorBundle = new ZipFile(new File(config.getValue("kas.bundle.operator-archive", String.class)));
        syncBundle = new ZipFile(new File(config.getValue("kas.bundle.sync-archive", String.class)));
    }

    void complete() throws IOException {
        if (operatorBundle != null) {
            operatorBundle.close();
        }
        if (syncBundle != null) {
            syncBundle.close();
        }
    }

    void copyResources() {
        copyCustomResourceDefinitions();
        copyPriorityClasses();
        write(bundleDir.resolve("bundle.Dockerfile"), readString(operatorBundle, "bundle.Dockerfile"));
        write(bundleDir.resolve(ANNOTATIONS_PATH), readString(operatorBundle, ANNOTATIONS_PATH));
    }

    void copyCustomResourceDefinitions() {
        almExamples = new ArrayList<>();

        operatorBundle.stream()
            .filter(entry -> entry.getName().endsWith(".crd.yml"))
            .forEach(crdEntry -> {
                String crdData = readString(operatorBundle, crdEntry);
                write(bundleDir.resolve(crdEntry.getName()), crdData);

                CustomResourceDefinition crd = Serialization.unmarshal(crdData);

                crd.getSpec().getVersions().forEach(v ->
                    almExamples.add(Map.of(
                            "kind", crd.getSpec().getNames().getKind(),
                            "apiVersion", String.format("%s/%s", crd.getSpec().getGroup(), v.getName()),
                            "metadata", Map.of("name", crd.getMetadata().getName()),
                            "spec", Collections.emptyMap())));
            });
    }

    void copyPriorityClasses() {
        try (KubernetesClient client = new DefaultKubernetesClient()) {
            client.load(getStream(operatorBundle, "kubernetes.yml")).get().stream()
                .filter(resource -> "PriorityClass".equals(resource.getKind()))
                .forEach(uncheckedAccept(priorityClass -> {
                    String name = priorityClass.getMetadata().getName().replace("-", "");
                    write(bundleDir.resolve("manifests/" + name + ".priorityclass.yaml"), YAML_MAPPER.writeValueAsString(priorityClass));
                }));
        }
    }

    void mergeClusterServiceVersions() throws IOException {
        ClusterServiceVersion operatorCsv = readResource(operatorBundle, "manifests/kas-fleetshard-operator.clusterserviceversion.yaml");

        operatorCsv.getMetadata().setName("kas-fleetshard-operator.v" + version);
        operatorCsv.getMetadata().setAnnotations(new HashMap<>());
        operatorCsv.getMetadata().getAnnotations().put("olm.skipRange", ">=0.0.1 <" + version);
        operatorCsv.getMetadata().getAnnotations().put("alm-examples", JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(almExamples));

        operatorCsv.getSpec().setVersion(version);

        includeSyncResources(operatorCsv);

        deployments(operatorCsv).stream()
            .forEach(deployment -> {
                deployment.getSpec().getSelector().setMatchLabels(Map.of("name", deployment.getName()));
                deployment.getSpec().getTemplate().getMetadata().setLabels(Map.of("name", deployment.getName()));
            });

        config.getOptionalValue("kas.bundle.icon.file", String.class)
            .map(Path::of)
            .map(uncheckedApply(Files::readAllBytes))
            .map(Base64.getEncoder()::encodeToString)
            .ifPresent(base64data -> setIcon(operatorCsv, base64data));

        config.getOptionalValue("kas.bundle.icon.base64data", String.class)
            .ifPresent(base64data -> setIcon(operatorCsv, base64data));

        final String processedCsv = YAML_MAPPER.writeValueAsString(operatorCsv);

        String finalCsv = Stream.of(patchFile(), config.getOptionalValue("kas.bundle.patch", String.class))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(uncheckedApply(JSON_MAPPER::readTree))
            .filter(JsonNode::isArray)
            .map(JsonNode::iterator)
            .map(iterator -> Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED))
            .flatMap(spliterator -> StreamSupport.stream(spliterator, false))
            .collect(Collector.of(
                    JSON_MAPPER::createArrayNode,
                    ArrayNode::add,
                    (n1, n2) -> n1,
                    Optional::of))
            .filter(Predicate.not(ArrayNode::isEmpty))
            .map(uncheckedApply(patch -> JsonPatch.apply(patch, YAML_MAPPER.readTree(processedCsv))))
            .map(uncheckedApply(YAML_MAPPER::writeValueAsString))
            .orElse(processedCsv);

        write(bundleDir.resolve("manifests/kas-fleetshard-operator.clusterserviceversion.yaml"), finalCsv);
    }

    void buildAndPushImage() throws InvalidImageReferenceException, InterruptedException, RegistryException, IOException, CacheDirectoryCreationException, ExecutionException {
        ImageReference bundleImageName = ImageReference.parse(config.getValue("kas.bundle.image", String.class));
        CredentialRetrieverFactory retrieverFactory = CredentialRetrieverFactory.forImage(bundleImageName, logger -> {});

        CredentialRetriever credentialRetriever =
                config.getOptionalValue("kas.bundle.credential.username", String.class)
                    .filter(Predicate.not(String::isBlank))
                    .map(username -> {
                        String password = config.getValue("kas.bundle.credential.password", String.class);
                        return retrieverFactory.known(Credential.from(username, password),
                                "configuration properties");
                    })
                .or(() -> config.getOptionalValue("kas.bundle.credential.docker-config-path", String.class)
                        .filter(Predicate.not(String::isBlank))
                        .map(Path::of)
                        .map(retrieverFactory::dockerConfig))
                .orElseGet(retrieverFactory::dockerConfig);

        Map<String, String> bundleAnnotations = new HashMap<>();

        YAML_MAPPER.readTree(getStream(operatorBundle, ANNOTATIONS_PATH))
            .get("annotations")
            .fields()
            .forEachRemaining(annotation -> bundleAnnotations.put(annotation.getKey(), annotation.getValue().asText()));

        RegistryImage bundleImage = RegistryImage.named(bundleImageName)
                .addCredentialRetriever(credentialRetriever);

        var container = Jib.fromScratch()
            .addLayer(List.of(bundleDir.resolve("manifests"), bundleDir.resolve("metadata")), "/")
            .setLabels(bundleAnnotations)
            .containerize(Containerizer.to(bundleImage)
                    .withAdditionalTag(version));

        write(bundleDir.resolve("jib-image.digest"), "sha256:" + container.getDigest().getHash());
    }

    void includeSyncResources(ClusterServiceVersion operatorCsv) {
        var syncResources = loadSyncResources();
        var syncServiceAccount = syncResources.get("ServiceAccount")
                .stream()
                .map(ServiceAccount.class::cast)
                .findFirst()
                .orElseThrow();

        var syncDeployment = syncResources.get("Deployment")
                .stream()
                .map(Deployment.class::cast)
                .findFirst()
                .orElseThrow();

        syncResources.get("ClusterRole")
                .stream()
                .map(ClusterRole.class::cast)
                .map(role -> new StrategyDeploymentPermissionsBuilder()
                        .withServiceAccountName(syncServiceAccount.getMetadata().getName())
                        .withRules(role.getRules())
                        .build())
                .forEach(operatorCsv.getSpec().getInstall().getSpec().getClusterPermissions()::add);

        syncResources.get("Role")
                .stream()
                .map(Role.class::cast)
                .map(role -> new StrategyDeploymentPermissionsBuilder()
                        .withServiceAccountName(syncServiceAccount.getMetadata().getName())
                        .withRules(role.getRules())
                        .build())
                .forEach(operatorCsv.getSpec().getInstall().getSpec().getPermissions()::add);

        deployments(operatorCsv).add(new StrategyDeploymentSpecBuilder()
                .withName("kas-fleetshard-sync")
                .withSpec(syncDeployment.getSpec())
                .build());
    }

    Map<String, List<HasMetadata>> loadSyncResources() {
        Map<String, List<HasMetadata>> resources = new HashMap<>();

        try (KubernetesClient client = new DefaultKubernetesClient()) {
            client.load(getStream(syncBundle, "kubernetes.yml")).get().forEach(resource ->
                resources.computeIfAbsent(
                        resource.getKind(),
                        key -> new ArrayList<>())
                    .add(resource));
        }

        return resources;
    }

    void setIcon(ClusterServiceVersion csv, String base64data) {
        csv.getSpec().setIcon(List.of(new IconBuilder()
            .withBase64data(base64data)
            // Required when the icon is set
            .withMediatype(config.getValue("kas.bundle.icon.mediatype", String.class))
            .build()));
    }

    Optional<String> patchFile() {
        return config.getOptionalValue("kas.bundle.patch-file", String.class)
            .map(Path::of)
            .map(uncheckedApply(Files::readString));
    }

    static <T extends HasMetadata> T readResource(ZipFile archive, String path) throws IOException {
        try (InputStream stream = getStream(archive, path)) {
            return Serialization.unmarshal(stream);
        }
    }

    static String readString(ZipFile archive, String path) {
        return readString(archive, archive.getEntry(path));
    }

    static String readString(ZipFile archive, ZipEntry entry) {
        try (InputStream stream = archive.getInputStream(entry);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path location, String data) {
        try {
            Files.writeString(location,
                    data,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static InputStream getStream(ZipFile archive, String path) {
        try {
            ZipEntry entry = archive.getEntry(path);
            return archive.getInputStream(entry);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<StrategyDeploymentSpec> deployments(ClusterServiceVersion csv) {
        return csv.getSpec().getInstall().getSpec().getDeployments();
    }

    <I, O, E extends IOException> Function<I, O> uncheckedApply(ThrowingFunction<I, O, E> task) {
        return input -> {
            try {
                return task.apply(input);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    <I, E extends IOException> Consumer<I> uncheckedAccept(ThrowingConsumer<I, E> task) {
        return input -> {
            try {
                task.accept(input);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @FunctionalInterface
    public interface ThrowingConsumer<I, E extends IOException> {
        void accept(I input) throws E;
    }

    @FunctionalInterface
    public interface ThrowingFunction<I, O, E extends IOException> {
        O apply (I input) throws E;
    }
}
