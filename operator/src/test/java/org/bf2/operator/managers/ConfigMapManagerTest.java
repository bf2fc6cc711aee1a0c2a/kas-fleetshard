package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.utils.ManagedKafkaUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.UUID;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class ConfigMapManagerTest {

    @Inject
    ConfigMapManager configMapManager;

    @Inject
    KubernetesClient client;

    @Test
    void testCreateOrUpdateDataPreservedWhenDigestUnchanged() {
        ManagedKafka mk = ManagedKafkaUtils.exampleManagedKafka("40Gi");
        final String namespace = mk.getMetadata().getNamespace();
        final String mkName = mk.getMetadata().getName();
        final String cmName = "test-cm-preservation";
        final String cmFullName = mkName + "-" + cmName;

        configMapManager.createOrUpdate(mk, cmName, true);

        this.client.configMaps()
            .inNamespace(namespace)
            .withName(cmFullName)
            .edit(cm -> new ConfigMapBuilder(cm).addToData("file2.txt", "value2").build());

        configMapManager.createOrUpdate(mk, cmName, true);

        Assertions.assertEquals("value2", this.client.configMaps()
                .inNamespace(namespace)
                .withName(cmFullName)
                .get()
                .getData()
                .get("file2.txt"));

        configMapManager.delete(mk, cmName);
        Assertions.assertTrue(configMapManager.allDeleted(mk, cmName));
    }

    @Test
    void testCreateOrUpdateDataLostWhenDigestModified() {
        ManagedKafka mk = ManagedKafkaUtils.exampleManagedKafka("40Gi");
        final String namespace = mk.getMetadata().getNamespace();
        final String mkName = mk.getMetadata().getName();
        final String cmName = "test-cm-preservation";
        final String cmFullName = mkName + "-" + cmName;

        configMapManager.createOrUpdate(mk, cmName, true);

        this.client.configMaps()
            .inNamespace(namespace)
            .withName(cmFullName)
            .edit(cm -> new ConfigMapBuilder(cm)
                    .editOrNewMetadata()
                        .addToAnnotations(ConfigMapManager.DIGEST, UUID.randomUUID().toString())
                    .endMetadata()
                    .addToData("file2.txt", "value2")
                    .build());

        configMapManager.createOrUpdate(mk, cmName, true);

        Assertions.assertFalse(this.client.configMaps()
                .inNamespace(namespace)
                .withName(cmFullName)
                .get()
                .getData()
                .containsKey("file2.txt"));

        configMapManager.delete(mk, cmName);
        Assertions.assertTrue(configMapManager.allDeleted(mk, cmName));
    }

    @Test
    void testCreateOrUpdateDataLostWhenDigestNotChecked() {
        ManagedKafka mk = ManagedKafkaUtils.exampleManagedKafka("40Gi");
        final String namespace = mk.getMetadata().getNamespace();
        final String mkName = mk.getMetadata().getName();
        final String cmName = "test-cm-preservation";
        final String cmFullName = mkName + "-" + cmName;

        configMapManager.createOrUpdate(mk, cmName, false);

        this.client.configMaps()
            .inNamespace(namespace)
            .withName(cmFullName)
            .edit(cm -> new ConfigMapBuilder(cm)
                    .addToData("file2.txt", "value2")
                    .build());

        configMapManager.createOrUpdate(mk, cmName, false);

        Assertions.assertFalse(this.client.configMaps()
                .inNamespace(namespace)
                .withName(cmFullName)
                .get()
                .getData()
                .containsKey("file2.txt"));

        configMapManager.delete(mk, cmName);
        Assertions.assertTrue(configMapManager.allDeleted(mk, cmName));
    }
}
