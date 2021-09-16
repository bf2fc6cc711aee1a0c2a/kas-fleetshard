package org.bf2.common;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ManagedKafkaAgentResourceClient extends AbstractCustomResourceClient<ManagedKafkaAgent, ManagedKafkaAgentList> {

    public static final String RESOURCE_NAME = "managed-agent";

    @Override
    protected Class<ManagedKafkaAgent> getCustomResourceClass() {
        return ManagedKafkaAgent.class;
    }

    @Override
    protected Class<ManagedKafkaAgentList> getCustomResourceListClass() {
        return ManagedKafkaAgentList.class;
    }

    public static ManagedKafkaAgent getDummyInstance() {
        ObservabilityConfiguration observabilityConfig = new ObservabilityConfigurationBuilder()
                .withAccessToken("test-token")
                .withChannel("test")
                .withTag("test-tag")
                .withRepository("test-repo")
                .build();

        return new ManagedKafkaAgentBuilder()
                .withSpec(new ManagedKafkaAgentSpecBuilder()
                        .withObservability(observabilityConfig)
                        .build())
                .withNewMetadata()
                    .withName(ManagedKafkaAgentResourceClient.RESOURCE_NAME)
                .endMetadata()
                .build();
    }

}
