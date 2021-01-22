package org.bf2.sync;

import javax.inject.Inject;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dekorate.crd.config.Scope;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;

public class AgentSync implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentSync.class);

    @Inject
    KubernetesClient localClient;

    //http connection to poll for ManagedKafkas and post updates
    
    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent sync");
        
        //CustomResourceDefinition kafkaCrd = Crds.kafka();
        //CustomResourceDefinitionContext kafkaCrdContext = CustomResourceDefinitionContext.fromCrd(kafkaCrd);
        
        //monitor the ManagedKafka resources
        
        CustomResourceDefinitionContext managedKafkaCrdContext = new CustomResourceDefinitionContext.Builder()
        		.withScope(Scope.Namespaced.name())
        		.withGroup("managedkafka.bf2.org")
        		.withVersion("v1alpha1")
        		.withKind("ManagedKafka")
        		.withPlural("managedkafkas")
        		.withName("managedkafkas.managedkafka.bf2.org").build();
        		
        int resyncPeriodInMillis = 60000;
		SharedIndexInformer<ManagedKafka> managedKafkaInformer = localClient.informers()
        		.sharedIndexInformerForCustomResource(managedKafkaCrdContext, ManagedKafka.class, ManagedKafkaList.class, resyncPeriodInMillis);
        
        managedKafkaInformer.addEventHandler(new ResourceEventHandler<ManagedKafka>() {
			
			@Override
			public void onUpdate(ManagedKafka oldObj, ManagedKafka newObj) {
				//an update will also be generated for each resyncPeriodInMillis
				System.out.println("update " + oldObj);
				System.out.println(newObj.getStatus());
			}
			
			@Override
			public void onDelete(ManagedKafka obj, boolean deletedFinalStateUnknown) {
				System.out.println("delete " + obj);
			}
			
			@Override
			public void onAdd(ManagedKafka obj) {
				//on a restart we'll hit add again for each resource
				System.out.println("add " + obj);
			}
		});
        
        managedKafkaInformer.run();
        
        //monitor the agent to supply "kafka units"
        
        Quarkus.waitForExit();
        return 0;
    }
}
