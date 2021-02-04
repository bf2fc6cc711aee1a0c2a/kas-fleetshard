package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

/**
 * Represents different versions supported by the ManagedKafka instance
 */
@Buildable(builderPackage = "io.fabric8.kubernetes.api.builder")
public class Versions {

    private String kafka;

    public String getKafka() {
        return kafka;
    }

    public void setKafka(String kafka) {
        this.kafka = kafka;
    }
}
