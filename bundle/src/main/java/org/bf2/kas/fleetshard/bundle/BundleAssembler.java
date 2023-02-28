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
import com.google.cloud.tools.jib.api.TarImage;
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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TreeMap;
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

    static final String METADATA = "metadata";
    static final String ANNOTATIONS_PATH = METADATA + "/annotations.yaml";

    static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            new YAMLFactory()
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
                .disable(YAMLGenerator.Feature.USE_NATIVE_TYPE_ID));

    static class Configs {
        /**
         * The version of the generated OLM bundle. This will also be used as an
         * additional tag for the bundle image.
         */
        public static final String BUNDLE_VERSION = "kas.bundle.version";
        /**
         * Output location where the generated resources will be placed.
         */
        public static final String BUNDLE_OUTPUT_DIR = "kas.bundle.output-directory";
        /**
         * Input directory or ZIP file containing the generated bundle content from the
         * kas-fleetshard-operator module.
         */
        public static final String BUNDLE_OPERATOR_ARCHIVE = "kas.bundle.operator-archive";
        /**
         * Input directory or ZIP file containing the generated bundle content from the
         * kas-fleetshard-sync module.
         */
        public static final String BUNDLE_SYNC_ARCHIVE = "kas.bundle.sync-archive";
        /**
         * Path to an icon file to be placed in the generated ClusterServiceVersion
         */
        public static final String BUNDLE_ICON_FILE = "kas.bundle.icon.file";
        /**
         * Base64-encoded icon file to be placed in the generated ClusterServiceVersion
         */
        public static final String BUNDLE_ICON_BASE64_DATA = "kas.bundle.icon.base64data";
        /**
         * Type of icon given by either {@link #BUNDLE_ICON_FILE} or
         * {@link #BUNDLE_ICON_BASE64_DATA}. Required when either of those options are
         * specified.
         */
        public static final String BUNDLE_ICON_MEDIATYPE = "kas.bundle.icon.mediatype";
        /**
         * JSON Patch array. The patch(es) will be applied to the ClusterServiceVersion
         * as the final step of the process prior to building and pushing the bundle
         * image. Patches in this option will be applied following any specified by
         * {@link #BUNDLE_PATCH_FILE}.
         *
         * @see <a href="https://jsonpatch.com/">jsonpatch.com</a>
         */
        public static final String BUNDLE_PATCH = "kas.bundle.patch";
        /**
         * Path to a file containing a JSON Patch array. The patch(es) will be applied
         * to the ClusterServiceVersion as the final step of the process prior to
         * building and pushing the bundle image. Patches in this option will be applied
         * before any specified by {@link #BUNDLE_PATCH}.
         *
         * @see <a href="https://jsonpatch.com/">jsonpatch.com</a>
         */
        public static final String BUNDLE_PATCH_FILE = "kas.bundle.patch-file";
        /**
         * Name of the bundle image to be built and pushed. This property should include
         * the registry host, group, repository name, and default tag.
         */
        public static final String BUNDLE_IMAGE = "kas.bundle.image";
        /**
         * User name for the registry host specified in {@link #BUNDLE_IMAGE}.
         */
        public static final String BUNDLE_CREDENTIAL_USERNAME = "kas.bundle.credential.username";
        /**
         * Password for the registry host specified in {@link #BUNDLE_IMAGE}.
         */
        public static final String BUNDLE_CREDENTIAL_PASSWORD = "kas.bundle.credential.password";
        /**
         * Path to the Docker config file for the registry host specified in
         * {@link #BUNDLE_IMAGE}.
         */
        public static final String BUNDLE_CREDENTIAL_DOCKER_CONFIG_PATH = "kas.bundle.credential.docker-config-path";
        /**
         * Path to a file where the bundle image TAR file should be written. If
         * specified, no image will be pushed to the remote registry specified in
         * {@link #BUNDLE_IMAGE}. This option is intended for testing only.
         */
        public static final String BUNDLE_TAR_IMAGE = "kas.bundle.tar-image";

        private Configs() {
        }
    }

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
    BundleSource operatorBundle;
    BundleSource syncBundle;
    List<Map<String, ?>> almExamples;

    public BundleAssembler(Config config) {
        this.config = config;
        version = config.getOptionalValue(Configs.BUNDLE_VERSION, String.class).orElse("999.999.999").toLowerCase();
    }

    void initialize() throws IOException {
        bundleDir = Files.createDirectories(Path.of(config.getValue(Configs.BUNDLE_OUTPUT_DIR, String.class)));
        Files.createDirectories(bundleDir.resolve("manifests"));
        Files.createDirectories(bundleDir.resolve(METADATA));
        operatorBundle = loadSource(new File(config.getValue(Configs.BUNDLE_OPERATOR_ARCHIVE, String.class)));
        syncBundle = loadSource(new File(config.getValue(Configs.BUNDLE_SYNC_ARCHIVE, String.class)));
    }

    BundleSource loadSource(File sourceFile) throws IOException {
        if (sourceFile.isDirectory()) {
            return new DirectorySource(sourceFile);
        }
        return new ZipFileSource(new ZipFile(sourceFile));
    }

    void complete() throws IOException {
        if (operatorBundle != null) {
            operatorBundle.close();
        }
        if (syncBundle != null) {
            syncBundle.close();
        }
    }

    void copyResources() throws IOException {
        copyCustomResourceDefinitions();
        copyPriorityClasses();
        write(bundleDir.resolve(ANNOTATIONS_PATH), operatorBundle.readString(ANNOTATIONS_PATH));
    }

    void copyCustomResourceDefinitions() throws IOException {
        almExamples = operatorBundle.entries()
            .filter(entryName -> entryName.endsWith(".crd.yml"))
            .flatMap(entryName -> {
                String crdData = operatorBundle.readString(entryName);
                write(bundleDir.resolve(entryName), crdData);

                CustomResourceDefinition crd = Serialization.unmarshal(crdData);

                return crd.getSpec().getVersions().stream().map(v ->
                    // Use a TreeMap to ensure consistent key order for testing/comparison
                    new TreeMap<>(Map.of(
                            "kind", crd.getSpec().getNames().getKind(),
                            "apiVersion", String.format("%s/%s", crd.getSpec().getGroup(), v.getName()),
                            METADATA, Map.of("name", crd.getMetadata().getName()),
                            "spec", Collections.emptyMap())));
            })
            // Sorted to ensure CRDs are in the same order for testing/comparison
            .sorted(Comparator.<Map<?, ?>, String>comparing(crdExample -> crdExample.get("kind").toString())
                    .thenComparing(crdExample -> crdExample.get("apiVersion").toString()))
            .collect(Collectors.toList());
    }

    void copyPriorityClasses() throws IOException {
        try (KubernetesClient client = new DefaultKubernetesClient()) {
            client.load(operatorBundle.getEntry("kubernetes.yml")).get().stream()
                .filter(resource -> "PriorityClass".equals(resource.getKind()))
                .forEach(uncheckedAccept(priorityClass -> {
                    String name = priorityClass.getMetadata().getName().replace("-", "");
                    write(bundleDir.resolve("manifests/" + name + ".priorityclass.yaml"), YAML_MAPPER.writeValueAsString(priorityClass));
                }));
        }
    }

    void mergeClusterServiceVersions() throws IOException {
        ClusterServiceVersion operatorCsv = operatorBundle.readResource("manifests/kas-fleetshard-operator.clusterserviceversion.yaml");

        operatorCsv.getMetadata().setName("kas-fleetshard-operator.v" + version);
        operatorCsv.getMetadata().setAnnotations(new LinkedHashMap<>());
        operatorCsv.getMetadata().getAnnotations().put("olm.skipRange", ">=0.0.1 <" + version);
        operatorCsv.getMetadata().getAnnotations().put("alm-examples", JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(almExamples));

        operatorCsv.getSpec().setVersion(version);

        includeSyncResources(operatorCsv);

        deployments(operatorCsv).stream()
            .forEach(deployment -> {
                deployment.getSpec().getSelector().setMatchLabels(Map.of("name", deployment.getName()));
                deployment.getSpec().getTemplate().getMetadata().setLabels(Map.of("name", deployment.getName()));
            });

        config.getOptionalValue(Configs.BUNDLE_ICON_FILE, String.class)
            .map(Path::of)
            .map(uncheckedApply(Files::readAllBytes))
            .map(Base64.getEncoder()::encodeToString)
            .ifPresent(base64data -> setIcon(operatorCsv, base64data));

        config.getOptionalValue(Configs.BUNDLE_ICON_BASE64_DATA, String.class)
            .ifPresent(base64data -> setIcon(operatorCsv, base64data));

        final String processedCsv = YAML_MAPPER.writeValueAsString(operatorCsv);

        String finalCsv = Stream.of(patchFile(), config.getOptionalValue(Configs.BUNDLE_PATCH, String.class))
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
        ImageReference bundleImageName = ImageReference.parse(config.getValue(Configs.BUNDLE_IMAGE, String.class));
        CredentialRetrieverFactory retrieverFactory = CredentialRetrieverFactory.forImage(bundleImageName, logger -> {});

        CredentialRetriever credentialRetriever =
                config.getOptionalValue(Configs.BUNDLE_CREDENTIAL_USERNAME, String.class)
                    .filter(Predicate.not(String::isBlank))
                    .map(username -> {
                        String password = config.getValue(Configs.BUNDLE_CREDENTIAL_PASSWORD, String.class);
                        return retrieverFactory.known(Credential.from(username, password),
                                "configuration properties");
                    })
                .or(() -> config.getOptionalValue(Configs.BUNDLE_CREDENTIAL_DOCKER_CONFIG_PATH, String.class)
                        .filter(Predicate.not(String::isBlank))
                        .map(Path::of)
                        .map(retrieverFactory::dockerConfig))
                .orElseGet(retrieverFactory::dockerConfig);

        Map<String, String> bundleAnnotations = new LinkedHashMap<>();

        YAML_MAPPER.readTree(operatorBundle.getEntry(ANNOTATIONS_PATH))
            .get("annotations")
            .fields()
            .forEachRemaining(annotation -> bundleAnnotations.put(annotation.getKey(), annotation.getValue().asText()));

        RegistryImage bundleImage = RegistryImage.named(bundleImageName)
                .addCredentialRetriever(credentialRetriever);

        var containerizer = config.getOptionalValue(Configs.BUNDLE_TAR_IMAGE, String.class)
                .filter(Predicate.not(String::isBlank))
                .map(Path::of)
                .map(tarImage -> Containerizer.to(TarImage.at(tarImage).named(bundleImageName)))
                .orElseGet(() -> Containerizer.to(bundleImage).withAdditionalTag(version));

        var container = Jib.fromScratch()
            .addPlatform("amd64", "linux")
            .addLayer(List.of(bundleDir.resolve("manifests"), bundleDir.resolve(METADATA)), "/")
            .setLabels(bundleAnnotations)
            .containerize(containerizer);

        write(bundleDir.resolve("jib-image.digest"), "sha256:" + container.getDigest().getHash());
    }

    void includeSyncResources(ClusterServiceVersion operatorCsv) throws IOException {
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

    Map<String, List<HasMetadata>> loadSyncResources() throws IOException {
        Map<String, List<HasMetadata>> resources = new HashMap<>();

        try (KubernetesClient client = new DefaultKubernetesClient()) {
            client.load(syncBundle.getEntry("kubernetes.yml")).get().forEach(resource ->
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
            .withMediatype(config.getValue(Configs.BUNDLE_ICON_MEDIATYPE, String.class))
            .build()));
    }

    Optional<String> patchFile() {
        return config.getOptionalValue(Configs.BUNDLE_PATCH_FILE, String.class)
            .map(Path::of)
            .map(uncheckedApply(Files::readString));
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

    static List<StrategyDeploymentSpec> deployments(ClusterServiceVersion csv) {
        return csv.getSpec().getInstall().getSpec().getDeployments();
    }

    @FunctionalInterface
    public interface ThrowingConsumer<I, E extends IOException> {
        void accept(I input) throws E;
    }

    static <I, E extends IOException> Consumer<I> uncheckedAccept(ThrowingConsumer<I, E> task) {
        return input -> {
            try {
                task.accept(input);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @FunctionalInterface
    public interface ThrowingFunction<I, O, E extends IOException> {
        O apply (I input) throws E;
    }

    static <I, O, E extends IOException> Function<I, O> uncheckedApply(ThrowingFunction<I, O, E> task) {
        return input -> {
            try {
                return task.apply(input);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    interface BundleSource extends Closeable {
        InputStream getEntry(String path) throws IOException;

        Stream<String> entries() throws IOException;

        default String readString(String path) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(getEntry(path), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        default <T extends HasMetadata> T readResource(String path) throws IOException {
            try (InputStream stream = getEntry(path)) {
                return Serialization.unmarshal(stream);
            }
        }
    }

    static class ZipFileSource implements BundleSource {
        final ZipFile source;

        public ZipFileSource(ZipFile source) {
            this.source = source;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }

        @Override
        public Stream<String> entries() throws IOException {
            return source.stream().map(ZipEntry::getName);
        }

        @Override
        public InputStream getEntry(String path) throws IOException {
            return source.getInputStream(source.getEntry(path));
        }
    }

    static class DirectorySource implements BundleSource {
        final File source;

        public DirectorySource(File source) {
            this.source = source;
        }

        @Override
        public void close() throws IOException {
            // No-op
        }

        @Override
        public Stream<String> entries() throws IOException {
            Path directoryPath = source.toPath();
            return Files.walk(directoryPath)
                    .map(directoryPath::relativize)
                    .map(Path::toString);
        }

        @Override
        public InputStream getEntry(String path) throws IOException {
            return Files.newInputStream(source.toPath().resolve(path));
        }
    }
}
