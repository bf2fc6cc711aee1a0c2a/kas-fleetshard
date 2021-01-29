package org.bf2.resources;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnableKubernetesMockClient(crud = true)
public class ManagedKafkaCrdTest {
    private final String ROOT_PATH = System.getProperty("user.dir");

    static KubernetesClient client;

    @BeforeAll
    public static void setup() {
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
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
}
