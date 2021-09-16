package org.bf2.operator.utils;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.bf2.operator.controllers.ManagedKafkaControllerTest;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAuthenticationOAuthBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaEndpointBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ServiceAccountBuilder;
import org.bf2.operator.resources.v1alpha1.Versions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ManagedKafkaUtils {
    private ManagedKafkaUtils() {
    }

    public static ManagedKafka dummyManagedKafka(String id) {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.setId(id);
        return mk;
    }

    public static ManagedKafka exampleManagedKafka(String size) {
        ManagedKafka mk = new ManagedKafkaBuilder()
            .withNewMetadata()
                .withNamespace("test")
                .withName("test-mk")
            .endMetadata()
            .withSpec(
                new ManagedKafkaSpecBuilder()
                    .withEndpoint(
                        new ManagedKafkaEndpointBuilder()
                            .withBootstrapServerHost("xxx.yyy.zzz")
                            .build()
                    )
                    .withOauth(
                        new ManagedKafkaAuthenticationOAuthBuilder()
                            .withClientId("clientId")
                            .withClientSecret("clientSecret")
                            .withTokenEndpointURI("https://tokenEndpointURI")
                            .withJwksEndpointURI("https://jwksEndpointURI")
                            .withValidIssuerEndpointURI("https://validIssuerEndpointURI")
                            .withUserNameClaim("userNameClaim")
                            .withFallbackUserNameClaim("fallbackUserNameClaim")
                            .withTlsTrustedCertificate("TLS trusted cert")
                            .build()
                    )
                    .withNewCapacity()
                    .withMaxDataRetentionSize(Quantity.parse(size))
                    .withIngressEgressThroughputPerSec(Quantity.parse("2Mi"))
                    .endCapacity()
                    .withNewVersions()
                        .withKafka("2.6.0")
                        .withStrimzi(Versions.STRIMZI_CLUSTER_OPERATOR_V0_23_0_4)
                    .endVersions()
                    .withOwners("userid-123")
                    .withServiceAccounts(
                        new ServiceAccountBuilder()
                            .withName("canary")
                            .withPrincipal("canary-123")
                            .withPassword("canary-secret")
                            .build()
                    )
                    .build())
            .build();
        return mk;
    }

    public static void createCompanionConfigMap(String namespace) throws IOException {
        KubernetesClient client = new DefaultKubernetesClient();
        String url = ManagedKafkaControllerTest.class.getResource("/controllers/companion-templates-config-map.yaml").getPath();
        String f = Files.readString(Paths.get(url), StandardCharsets.UTF_8);
        ConfigMap cm = Serialization.unmarshal(f, ConfigMap.class);
        client.configMaps().inNamespace(namespace).create(cm);
    }
}
