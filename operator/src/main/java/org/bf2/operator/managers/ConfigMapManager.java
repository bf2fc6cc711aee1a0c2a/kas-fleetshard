package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.xml.bind.DatatypeConverter;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class ConfigMapManager {

    private static final String DIGEST = "org.bf2.operator/digest";

    @Inject
    Logger log;

    @Inject
    KubernetesClient client;

    @Inject
    InformerManager informerManager;

    public String getFullName(ManagedKafka managedKafka, String configMapName) {
        String namePrefix = managedKafka.getMetadata().getName();
        return String.format("%s-%s", namePrefix, configMapName);
    }

    public void createOrUpdate(ManagedKafka managedKafka, String configMapName, boolean compareDigest) {
        String fullName = getFullName(managedKafka, configMapName);
        ConfigMap current = getCachedConfigMap(managedKafka, fullName);
        ConfigMap replacement = configMapFrom(managedKafka, fullName, current);

        if (current == null || !compareDigest || isDigestModified(current, replacement)) {
            OperandUtils.createOrUpdate(client.configMaps(), replacement);
        }
    }

    public ConfigMap getCachedConfigMap(ManagedKafka managedKafka, String name) {
        return informerManager.getLocalConfigMap(managedKafka.getMetadata().getNamespace(), name);
    }

    public boolean allDeleted(ManagedKafka managedKafka, String... names) {
        return Arrays.stream(names).allMatch(name -> getCachedConfigMap(managedKafka, name) == null);
    }

    public void delete(ManagedKafka managedKafka, String... names) {
        Arrays.stream(names).forEach(name -> configMapResource(managedKafka, name).delete());
    }

    private ConfigMap configMapTemplate(ManagedKafka managedKafka, String name) {
        String templateName = name.substring(managedKafka.getMetadata().getName().length() + 1);

        try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(templateName + ".yaml")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            DigestInputStream dis = new DigestInputStream(is, md);
            ConfigMap template = client.configMaps().load(dis).get();
            Map<String, String> annotations = new HashMap<>(1);
            annotations.put(DIGEST, DatatypeConverter.printHexBinary(md.digest()));
            template.getMetadata().setAnnotations(annotations);
            return template;
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Resource<ConfigMap> configMapResource(ManagedKafka managedKafka, String name) {
        return client.configMaps()
                .inNamespace(managedKafka.getMetadata().getNamespace())
                .withName(name);
    }

    private ConfigMap configMapFrom(ManagedKafka managedKafka, String name, ConfigMap current) {

        ConfigMap template = configMapTemplate(managedKafka, name);

        ConfigMapBuilder builder = current != null ? new ConfigMapBuilder(current) : new ConfigMapBuilder(template);
        ConfigMap configMap = builder
                .editOrNewMetadata()
                    .withNamespace(managedKafka.getMetadata().getNamespace())
                    .withName(name)
                    .withLabels(OperandUtils.getDefaultLabels())
                .endMetadata()
                .withData(template.getData())
                .build();

        // setting the ManagedKafka has owner of the ConfigMap resource is needed
        // by the operator sdk to handle events on the ConfigMap resource properly
        OperandUtils.setAsOwner(managedKafka, configMap);

        return configMap;
    }

    private boolean isDigestModified(ConfigMap currentCM, ConfigMap newCM) {
        if (currentCM == null || newCM == null) {
            return true;
        }
        String currentDigest = currentCM.getMetadata().getAnnotations() == null ? null : currentCM.getMetadata().getAnnotations().get(DIGEST);
        String newDigest = newCM.getMetadata().getAnnotations() == null ? null : newCM.getMetadata().getAnnotations().get(DIGEST);
        return !Objects.equals(currentDigest, newDigest);
    }
}
