package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.MockProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
class StrimziClusterRoleBindingManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    StrimziClusterRoleBindingManager crbManager;

    @BeforeEach
    void setup() {
        client.namespaces().delete();
        client.rbac().clusterRoleBindings().delete();
    }

    @Test
    @SuppressWarnings("resource")
    void testRemoveAbandonedClusterRoleBindingsWhenNoneAbandoned() throws InterruptedException {
        Stream.of(createNamespace("ns1"), createNamespace("ns2"))
            .forEach(client.namespaces()::create);

        Stream.of(
                createStrimziCRB("crb1", "ns1", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb2", "ns2", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb3", "ns3", "roleref3"))
            .forEach(client.rbac().clusterRoleBindings()::create);

        crbManager.setScanInterval(Duration.ofMillis(100));
        Thread.sleep(200);
        crbManager.removeAbandonedClusterRoleBindings();

        assertNotNull(client.rbac().clusterRoleBindings().withName("ns1-crb1").get());
        assertNotNull(client.rbac().clusterRoleBindings().withName("ns2-crb2").get());
        assertNotNull(client.rbac().clusterRoleBindings().withName("ns3-crb3").get());
    }

    @Test
    @SuppressWarnings("resource")
    void testRemoveAbandonedClusterRoleBindingsWhenOneAbandoned() throws InterruptedException {
        client.namespaces().create(createNamespace("ns1"));
        Stream.of(
                createStrimziCRB("crb1", "ns1", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb2", "ns2", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb3", "ns3", "roleref3"))
            .forEach(client.rbac().clusterRoleBindings()::create);

        assertNotNull(client.rbac().clusterRoleBindings().withName("ns2-crb2").get());

        crbManager.setScanInterval(Duration.ofMillis(100));
        Thread.sleep(200);
        crbManager.removeAbandonedClusterRoleBindings();


        assertNotNull(client.rbac().clusterRoleBindings().withName("ns1-crb1").get());
        assertNull(client.rbac().clusterRoleBindings().withName("ns2-crb2").get());
        assertNotNull(client.rbac().clusterRoleBindings().withName("ns3-crb3").get());
    }

    @Test
    @SuppressWarnings("resource")
    void testRemoveAbandonedClusterRoleBindingsWhenOneAbandonedWithinGracePeriod() throws InterruptedException {
        client.namespaces().create(createNamespace("ns1"));
        Stream.of(
                createStrimziCRB("crb1", "ns1", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb2", "ns2", StrimziClusterRoleBindingManager.STRIMZI_KAFKA_ROLEREF),
                createStrimziCRB("crb3", "ns3", "roleref3"))
            .forEach(client.rbac().clusterRoleBindings()::create);

        crbManager.setScanInterval(Duration.ofMinutes(1));
        crbManager.removeAbandonedClusterRoleBindings();

        assertNotNull(client.rbac().clusterRoleBindings().withName("ns1-crb1").get());
        assertNotNull(client.rbac().clusterRoleBindings().withName("ns2-crb2").get());
        assertNotNull(client.rbac().clusterRoleBindings().withName("ns3-crb3").get());
    }

    static Namespace createNamespace(String name) {
        return new NamespaceBuilder()
                .withNewMetadata()
                    .withName(name)
                .endMetadata()
                .build();
    }

    static ClusterRoleBinding createStrimziCRB(String name, String namespace, String roleRef) {
        return new ClusterRoleBindingBuilder()
                .withNewMetadata()
                    .withName(namespace + '-' + name)
                    .withLabels(StrimziClusterRoleBindingManager.STRIMZI_CRB_LABELS)
                .endMetadata()
                .withRoleRef(new RoleRefBuilder()
                        .withName(roleRef)
                        .build())
                .withSubjects(new SubjectBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .build())
                .build();
    }
}
