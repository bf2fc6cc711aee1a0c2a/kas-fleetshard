package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.common.OperandUtils;
import org.bf2.operator.MockProfile;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTestResource(KubernetesServerTestResource.class)
@TestProfile(MockProfile.class)
@QuarkusTest
public class ImagePullSecretManagerTest {

    @Inject
    KubernetesClient client;

    @Inject
    ImagePullSecretManager imagePullSecretManager;

    @BeforeEach
    public void setup() {
        client.apps()
                .deployments()
                .inNamespace(client.getNamespace())
                .withName(OperandUtils.FLEETSHARD_OPERATOR_NAME)
                .delete();
        client.secrets().inNamespace(client.getNamespace()).delete();
        imagePullSecretManager.initialize();
    }

    @Test
    void testNoSecrets() {
        ManagedKafka mk = new ManagedKafka();

        assertTrue(imagePullSecretManager.getOperatorImagePullSecrets(mk).isEmpty());

        // these will just be no-ops
        imagePullSecretManager.checkSecret();
        imagePullSecretManager.propagateSecrets(mk);
        imagePullSecretManager.deleteSecrets(mk);
    }

    @Test
    void testWithSecrets() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName(OperandUtils.FLEETSHARD_OPERATOR_NAME)
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .withImagePullSecrets(
                        new LocalObjectReferenceBuilder().withName("name").build(),
                        new LocalObjectReferenceBuilder().withName("other").build())
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().inNamespace(client.getNamespace()).create(deployment);

        imagePullSecretManager.initialize();

        ManagedKafka managedKafka = new ManagedKafka();
        managedKafka.setMetadata(new ObjectMetaBuilder().withName("mk").withNamespace("testWithSecrets").build());

        assertEquals(Set.of("mk-pull-name", "mk-pull-other"),
                imagePullSecretManager.getOperatorImagePullSecrets(managedKafka)
                        .stream()
                        .map(l -> l.getName())
                        .collect(Collectors.toSet()));

        // no-ops - the secrets aren't yet retrieved
        imagePullSecretManager.propagateSecrets(managedKafka);
        imagePullSecretManager.deleteSecrets(managedKafka);
        imagePullSecretManager.checkSecret();

        client.secrets()
                .inNamespace(client.getNamespace())
                .create(new SecretBuilder().withNewMetadata().withName("name").endMetadata().build());
        client.secrets()
                .inNamespace(client.getNamespace())
                .create(new SecretBuilder().withNewMetadata().withName("other").endMetadata().build());

        // should succeed, but there's no ManagedKafkas so nothing happens
        imagePullSecretManager.checkSecret();

        assertTrue(client.secrets()
                .inNamespace("testWithSecrets")
                .list()
                .getItems()
                .isEmpty());

        imagePullSecretManager.propagateSecrets(managedKafka);

        assertEquals(Set.of("mk-pull-name", "mk-pull-other"),
                client.secrets()
                        .inNamespace("testWithSecrets")
                        .list()
                        .getItems()
                        .stream()
                        .map(s -> s.getMetadata().getName())
                        .collect(Collectors.toSet()));
    }

}
