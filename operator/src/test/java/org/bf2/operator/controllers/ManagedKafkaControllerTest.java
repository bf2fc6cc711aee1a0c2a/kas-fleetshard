package org.bf2.operator.controllers;

import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import org.bf2.operator.managers.StrimziManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.inject.Inject;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class ManagedKafkaControllerTest {

    @Inject
    ManagedKafkaController mkController;

    @Test
    void shouldCreateStatus() throws InterruptedException {
        String id = UUID.randomUUID().toString();
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(id);
        mk.getMetadata().setName(id);
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context context = Mockito.mock(Context.class);

        StrimziManager strimziManager = Mockito.mock(StrimziManager.class);
        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                                .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions(mk.getSpec().getVersions().getKafka())
                        .build());
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.reconcile(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Installing.name(), condition.getReason());

        mk.getMetadata().setLabels(Map.of(ManagedKafka.PROFILE_TYPE, "not valid"));
        mkController.reconcile(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());

        mk.getSpec().setDeleted(true);
        mkController.reconcile(mk, context);

        // should now be deleted
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Deleted.name(), condition.getReason());
    }

    @Test
    void testWrongVersions() throws InterruptedException {
        ManagedKafka mk = ManagedKafka.getDummyInstance(1);
        mk.getMetadata().setUid(UUID.randomUUID().toString());
        mk.getMetadata().setGeneration(1l);
        mk.getMetadata().setResourceVersion("1");

        // create
        Context context = Mockito.mock(Context.class);

        StrimziManager strimziManager = Mockito.mock(StrimziManager.class);
        Mockito.when(strimziManager.getVersionLabel())
                .thenReturn("managedkafka.bf2.org/strimziVersion");

        QuarkusMock.installMockForType(strimziManager, StrimziManager.class);

        mkController.reconcile(mk, context);
        ManagedKafkaCondition condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Strimzi version strimzi-cluster-operator.v0.23.0 is not supported", condition.getMessage());

        Mockito.when(strimziManager.getStrimziVersion("strimzi-cluster-operator.v0.23.0"))
                .thenReturn(new StrimziVersionStatusBuilder()
                        .withVersion(mk.getSpec().getVersions().getStrimzi())
                        .withKafkaVersions("3.0.0")
                        .build());

        mkController.reconcile(mk, context);
        condition = mk.getStatus().getConditions().get(0);
        assertEquals(ManagedKafkaCondition.Reason.Error.name(), condition.getReason());
        assertEquals("The requested Kafka version 2.7.0 is not supported by the Strimzi version strimzi-cluster-operator.v0.23.0", condition.getMessage());
    }

}
