package org.bf2.operator.resources.v1alpha1;

import io.dekorate.crd.annotation.Crd;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("managedkafka.bf2.org")
@Version("v1alpha1")
@Crd(group = "managedkafka.bf2.org", version = "v1alpha1")
public class ManagedKafkaAgent extends CustomResource<ManagedKafkaAgentSpec, ManagedKafkaAgentStatus>
        implements Namespaced {
    private static final long serialVersionUID = 1L;

}
