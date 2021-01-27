package org.bf2.operator.resources.v1alpha1;

public class ManagedKafkaSpec {

    private Versions versions;
    
    private boolean deleted;

    public Versions getVersions() {
        return versions;
    }

    public void setVersions(Versions versions) {
        this.versions = versions;
    }
    
    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }
}
