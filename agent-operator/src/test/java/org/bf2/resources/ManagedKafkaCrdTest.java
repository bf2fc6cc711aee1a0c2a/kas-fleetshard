package org.bf2.resources;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.EmptyDefaultKubernetesMockServerTestResource;
import io.quarkus.test.kubernetes.client.MockServer;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(EmptyDefaultKubernetesMockServerTestResource.class)
@QuarkusTest
public class ManagedKafkaCrdTest {
    private final String ROOT_PATH = System.getProperty("user.dir");

    @MockServer
    KubernetesMockServer server;

    @Test
    void testRegisterCrds() throws IOException {
        KubernetesClient client = server.createClient();
        //load all crds
        List<HasMetadata> crdList = client.load(new FileInputStream(Paths.get(ROOT_PATH, "target", "classes", "META-INF", "dekorate", "kubernetes.yml").toString())).get();

        for (HasMetadata crd : crdList) {
            server.expect().post().withPath("/apis/apiextensions.k8s.io/v1beta1/customresourcedefinitions").andReturn(200, crd).once();
            CustomResourceDefinition created = client.apiextensions().v1beta1().customResourceDefinitions().createOrReplace((CustomResourceDefinition) crd);
            assertNotNull(created);
            assertEquals(crd.getMetadata().getName(), created.getMetadata().getName());
            assertNotNull(created.getSpec().getValidation().getOpenAPIV3Schema());
        }
    }
}
