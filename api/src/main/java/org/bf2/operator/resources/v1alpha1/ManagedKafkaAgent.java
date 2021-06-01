package org.bf2.operator.resources.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        refs = @BuildableReference(CustomResource.class),
        editableEnabled = false
)
@Group("managedkafka.bf2.org")
@Version("v1alpha1")
public class ManagedKafkaAgent extends CustomResource<ManagedKafkaAgentSpec, ManagedKafkaAgentStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;

    @Override
    protected ManagedKafkaAgentSpec initSpec() {
        return new ManagedKafkaAgentSpec();
    }

    /**
     * A null value will be treated as empty instead
     */
    @Override
    public void setSpec(ManagedKafkaAgentSpec spec) {
        if (spec == null) {
            spec = initSpec();
        }
        super.setSpec(spec);
    }

}
