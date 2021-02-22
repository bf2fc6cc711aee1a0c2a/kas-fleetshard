package org.bf2.sync.controlplane;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaConditionBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaStatusBuilder;
import org.junit.jupiter.api.Test;

public class ControlPlaneTest {

    @Test public void testManagedKafkaStatusComparison() {
        ManagedKafkaStatus status = null;
        assertFalse(ControlPlane.statusChanged(status, status));

        status = new ManagedKafkaStatus();
        assertFalse(ControlPlane.statusChanged(status, null));
        assertFalse(ControlPlane.statusChanged(status, status));
        assertFalse(ControlPlane.statusChanged(null, status));

        ManagedKafkaStatus newStatus = new ManagedKafkaStatusBuilder().addNewCondition().withLastTransitionTime("2020-01-01")
                .endCondition().build();
        assertTrue(ControlPlane.statusChanged(status, newStatus));
        assertFalse(ControlPlane.statusChanged(newStatus, status));

        status.setConditions(new ArrayList<>());
        status.getConditions().add(new ManagedKafkaConditionBuilder().withLastTransitionTime("2021-01-01").build());
        assertFalse(ControlPlane.statusChanged(status, newStatus));
        assertTrue(ControlPlane.statusChanged(newStatus, status));

        newStatus.getConditions().add(new ManagedKafkaConditionBuilder().withLastTransitionTime("2022-01-01").build());
        assertTrue(ControlPlane.statusChanged(status, newStatus));
        assertFalse(ControlPlane.statusChanged(newStatus, status));
    }

}
