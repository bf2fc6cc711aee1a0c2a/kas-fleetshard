package org.bf2.sync;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.scheduler.Scheduled;

public class AgentSync implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentSync.class);

    //TODO: where should this be coming from
    @ConfigProperty(name = "cluster.id") 
    String id;
    
    @Inject
    KubernetesClient client;
    
    @Inject
    @RestClient
    ControlPlaneRestClient controlPlane;
    
    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent sync");
        
        //monitor the ManagedKafka resources
        
		CustomResourceDefinition managedKafkaCrd = client.apiextensions().v1().customResourceDefinitions()
				.withName("managedkafkas.managedkafka.bf2.org").get();

		CustomResourceDefinitionContext managedKafkaCrdContext = CustomResourceDefinitionContext
				.fromCrd(managedKafkaCrd);
        		
        int resyncPeriodInMillis = 60000;
		SharedIndexInformer<ManagedKafka> managedKafkaInformer = client.informers()
        		.sharedIndexInformerForCustomResource(managedKafkaCrdContext, ManagedKafka.class, ManagedKafkaList.class, resyncPeriodInMillis);
        
        managedKafkaInformer.addEventHandler(new ResourceEventHandler<ManagedKafka>() {
			
			@Override
			public void onUpdate(ManagedKafka oldObj, ManagedKafka newObj) {
				//an update will also be generated for each resyncPeriodInMillis
				//TODO: filter unnecessary updates
				controlPlane.updateKafkaClusterStatus(newObj.getStatus(), id, getClusterId(newObj));
			}
			
			@Override
			public void onDelete(ManagedKafka obj, boolean deletedFinalStateUnknown) {
				//TODO: ensure the delete status is set
				controlPlane.updateKafkaClusterStatus(obj.getStatus(), id, getClusterId(obj));
			}
			
			@Override
			public void onAdd(ManagedKafka obj) {
				//TODO: on a restart we'll hit add again for each resource - that could be filtered
				controlPlane.updateKafkaClusterStatus(obj.getStatus(), id, getClusterId(obj));
			}

		});
        
        managedKafkaInformer.run();
        
        //monitor the agent to supply "kafka units"
        //controlPlane.updateStatus(obj, id);
        
        Quarkus.waitForExit();
        return 0;
    }
    
    @Scheduled(every="{poll.interval}")     
    void pollKafkaClusters() {
    	//process the response
    	//TODO: the remote call could be none blocking
    	//controlPlane.getKafkaClusters(id); 
    }
    
    //TODO: create a method on the ManagedKafka object?
    private String getClusterId(ManagedKafka obj) {
		return obj.getMetadata().getAnnotations().get("kas.redhat.com/kafka-id");
	}

}
