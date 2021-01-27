package org.bf2.resources;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ManagedKafkaCrdTest {
    private final String ROOT_PATH = System.getProperty("user.dir");

    private static KubernetesServer server = new KubernetesServer(false, true);
    private static KubernetesClient client = null;

    @BeforeAll
    public static void setup() {
        server.before();
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");

        Config config = new ConfigBuilder()
                .withTrustCerts(true)
                .withNamespace("test")
                .withHttp2Disable(true)
                .withMasterUrl(server.getClient().getMasterUrl().toString())
                .build();

        client = new DefaultKubernetesClient(config);
    }

    @AfterAll
    public static void tearDown() {
        server.after();
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
        }
    }
}
