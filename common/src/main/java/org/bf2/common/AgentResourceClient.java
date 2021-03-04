package org.bf2.common;

import javax.enterprise.context.ApplicationScoped;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;

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

}
