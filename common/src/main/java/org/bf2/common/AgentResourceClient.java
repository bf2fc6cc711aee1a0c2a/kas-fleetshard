package org.bf2.common;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpecBuilder;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfiguration;
import org.bf2.operator.resources.v1alpha1.ObservabilityConfigurationBuilder;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AgentResourceClient extends AbstractCustomResourceClient<ManagedKafkaAgent, ManagedKafkaAgentList> {

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
                .withMetadata(new ObjectMetaBuilder().withName(AgentResourceClient.RESOURCE_NAME)
                        .build())
                .build();
    }

}
