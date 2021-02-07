package org.bf2.resources;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableKubernetesMockClient(https = false, crud = true)
public class ManagedKafkaCrdTest {
    private final String ROOT_PATH = System.getProperty("user.dir");

    static KubernetesClient client;

    @BeforeAll
    public static void setup() {
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_HTTP2_DISABLE, "true");
        System.setProperty(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY, "test");
        System.setProperty(Config.KUBERNETES_TRUST_CERT_SYSTEM_PROPERTY, "true");
    }


    @Test
    void testRegisterCrds() throws IOException {
        //load all crds
        List<HasMetadata> crdList = client.load(new FileInputStream(Paths.get(ROOT_PATH, "target", "classes", "META-INF", "dekorate", "kubernetes.yml").toString())).get();

        for (HasMetadata crd : crdList) {
            CustomResourceDefinition created = client.apiextensions().v1beta1().customResourceDefinitions().createOrReplace((CustomResourceDefinition) crd);
            assertNotNull(created);
            assertEquals(crd.getMetadata().getName(), created.getMetadata().getName());
            assertNotNull(created.getSpec().getValidation().getOpenAPIV3Schema());
            assertNotNull(client.apiextensions().v1beta1().customResourceDefinitions().withName(created.getMetadata().getName()).get());
        }
    }

    @Test
    @Disabled("due to issue https://github.com/bf2fc6cc711aee1a0c2a/kas-fleetshard/issues/60")
    void testCreateDeleteManagedKafkaResource() {
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withMetadata(
                        new ObjectMetaBuilder()
                                .withNamespace("test")
                                .withName("my-managed-kafka")
                                .build())
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        var mkClient = client.customResources(ManagedKafka.class);
        mkClient.createOrReplace(mk);
        assertEquals(1, mkClient.inNamespace(mk.getMetadata().getNamespace()).list().getItems().size());
        mkClient.inNamespace(mk.getMetadata().getNamespace()).withName(mk.getMetadata().getName()).delete();
        assertEquals(0, mkClient.inNamespace(mk.getMetadata().getNamespace()).list().getItems().size());
    }
}
