package org.bf2.operator.resources.v1alpha1;

import io.sundr.builder.annotations.Buildable;

/**
 * Represents different versions supported by the ManagedKafka instance
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
public class Versions {

    private String kafka;
    private String strimzi;

    public String getKafka() {
        return kafka;
    }

    public void setKafka(String kafka) {
        this.kafka = kafka;
    }

    public String getStrimzi() {
        return strimzi;
    }

    public void setStrimzi(String strimzi) {
        this.strimzi = strimzi;
    }
}
