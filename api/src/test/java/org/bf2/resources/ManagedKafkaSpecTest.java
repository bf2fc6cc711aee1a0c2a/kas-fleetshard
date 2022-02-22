package org.bf2.resources;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.bf2.operator.resources.v1alpha1.Versions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class ManagedKafkaSpecTest {

    @Test
    public void testEquals() {
        ManagedKafkaSpec spec1 = new ManagedKafkaSpec();
        ManagedKafkaSpec spec2 = new ManagedKafkaSpec();
        // if it's not equals, then the default equality is being used
        assertEquals(spec1, spec2);

        spec1.setVersions(new Versions());
        spec2.setVersions(new Versions());
        assertEquals(spec1, spec2);

        spec1.getVersions().setKafka("2.2.2");
        assertNotEquals(spec1, spec2);
    }

    @Test
    public void testIngressEgressBackwardsCompatibility() {
        ManagedKafkaSpec spec = new ManagedKafkaSpecBuilder().withNewCapacity()
                .withNewIngressEgressThroughputPerSec("30Mi")
                .endCapacity()
                .build();

        assertEquals(spec.getCapacity().getIngressEgressThroughputPerSec(), spec.getCapacity().getIngressPerSec());
        assertEquals(spec.getCapacity().getIngressEgressThroughputPerSec(), spec.getCapacity().getEgressPerSec());
    }

}
