package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.Infrastructure;
import io.fabric8.openshift.api.model.InfrastructureBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.dsl.OpenShiftConfigAPIGroupDSL;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.managers.MockOpenShiftSupport;
import org.bf2.operator.managers.OperandOverrideManager;
import org.bf2.operator.operands.KafkaInstanceConfigurations.InstanceType;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
class KafkaInstanceConfigurationsTest {

    @Inject
    KafkaInstanceConfigurations target;

    @Inject
    Config applicationConfig;

    @Inject
    MockOpenShiftSupport openshiftSupport;

    @SuppressWarnings("unchecked")
    @ParameterizedTest
    @CsvSource({
            "AWS      , custom-standard-gp2, gp2",
            "Azure    , ' '                , managed-premium",
            "GCP      ,                    , standard",
            "CloudsRUs,                    ," })
    void testStorageClassDerivation(String platformType, String configuredStandardClass,
            String platformDefaultStorageClass) throws IOException {
        Infrastructure infra = new InfrastructureBuilder()
                .withNewSpec()
                .withNewPlatformSpec()
                .withType(platformType)
                .endPlatformSpec()
                .endSpec()
                .build();

        OpenShiftClient mockClient = Mockito.mock(OpenShiftClient.class);
        var mockConfig = Mockito.mock(OpenShiftConfigAPIGroupDSL.class);
        var mockInfrastructures = Mockito.mock(NonNamespaceOperation.class);
        var mockInfraResource = Mockito.mock(Resource.class);

        Mockito.when(mockClient.config()).thenReturn(mockConfig);
        Mockito.when(mockConfig.infrastructures()).thenReturn(mockInfrastructures);
        Mockito.when(mockInfrastructures.withName("cluster")).thenReturn(mockInfraResource);
        Mockito.when(mockInfraResource.get()).thenReturn(infra);

        openshiftSupport.setClient(mockClient);

        // Using spy so that real configuration is used for all except single property
        Config overrideConfig = Mockito.spy(applicationConfig);
        Mockito.when(overrideConfig.getOptionalValue("standard.kafka.storage-class", String.class))
                .thenAnswer(args -> Optional.ofNullable(configuredStandardClass));

        target.setApplicationConfig(overrideConfig);

        try {
            target.init();
        } finally {
            openshiftSupport.setClient(null);
        }

        Arrays.stream(InstanceType.values())
                .filter(Predicate.not(InstanceType.STANDARD::equals))
                .map(target::getConfig)
                .map(KafkaInstanceConfiguration::getKafka)
                .map(KafkaInstanceConfiguration.Kafka::getStorageClass)
                .forEach(actualStorageClass -> {
                    assertEquals(platformDefaultStorageClass, actualStorageClass);
                });

        boolean standardClassPresent = !Objects.requireNonNullElse(configuredStandardClass, "").isBlank();
        String expectedStandardStorageClass =
                standardClassPresent ? configuredStandardClass : platformDefaultStorageClass;
        assertEquals(expectedStandardStorageClass,
                target.getConfig(InstanceType.STANDARD).getKafka().getStorageClass());

        // standard-dynamic profile extends from standard
        OperandOverrideManager overrideManager = Mockito.mock(OperandOverrideManager.class);
        QuarkusMock.installMockForType(overrideManager, OperandOverrideManager.class);
        Mockito.when(overrideManager.useDynamicScalingScheduling(Mockito.anyString()))
            .thenReturn(true);
        ManagedKafka dynamicMk = ManagedKafka.getDummyInstance(1);
        dynamicMk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, InstanceType.STANDARD.lowerName));
        assertEquals(expectedStandardStorageClass,
                target.getConfig(dynamicMk).getKafka().getStorageClass());
    }

    @Test
    void testStorageClassDefaultKubernetes() throws IOException {
        openshiftSupport.setOpenShift(false);

        try {
            target.init();
        } finally {
            openshiftSupport.setOpenShift(true);
        }

        Arrays.stream(InstanceType.values())
                .map(target::getConfig)
                .map(KafkaInstanceConfiguration::getKafka)
                .map(KafkaInstanceConfiguration.Kafka::getStorageClass)
                .forEach(Assertions::assertNull);
    }

    @Test
    void testDynamicOverrides() {
        OperandOverrideManager overrideManager = Mockito.mock(OperandOverrideManager.class);
        QuarkusMock.installMockForType(overrideManager, OperandOverrideManager.class);
        Mockito.when(overrideManager.useDynamicScalingScheduling("x.y.z")).thenReturn(true);
        ManagedKafka mk = new ManagedKafkaBuilder().withNewMetadata()
                .addToLabels(ManagedKafka.PROFILE_TYPE, KafkaInstanceConfigurations.InstanceType.STANDARD.lowerName)
                .endMetadata()
                .withNewSpec()
                .withNewVersions()
                .withStrimzi("x.y.z")
                .endVersions()
                .endSpec()
                .build();
        KafkaInstanceConfiguration config = target.getConfig(mk);
        assertEquals("2900m", config.getKafka().getContainerRequestCpu());
        assertEquals("3200m", config.getKafka().getContainerCpu());
    }
}
