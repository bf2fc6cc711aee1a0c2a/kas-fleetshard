package org.bf2.operator.resources.v1alpha1;

public class ManagedKafkaAgentSpec {

    // to use to kick off a status of cluster
    long version;

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

}
