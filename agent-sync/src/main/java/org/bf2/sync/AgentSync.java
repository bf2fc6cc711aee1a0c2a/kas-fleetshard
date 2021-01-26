package org.bf2.sync;

import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.scheduler.Scheduled;

public class AgentSync implements QuarkusApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentSync.class);

    @Inject
    KubernetesClient client;
    
    //TODO: where should this be coming from
    @ConfigProperty(name = "cluster.id") 
    String id;

    @Inject
    @RestClient
    ControlPlaneRestClient controlPlane;
    
    @Inject
    InformerManager informerManager;
    
    @Inject
    ManagedKafkaSync managedKafkaSync;
    
    @Override
    public int run(String... args) throws Exception {
        log.info("Managed Kafka agent sync");
        
        //monitor the agent to supply "kafka units"
        //controlPlane.updateStatus(obj, id);
        
        Quarkus.waitForExit();
        return 0;
    }
    
    @Scheduled(every="{poll.interval}")     
    void pollKafkaClusters() {
    	managedKafkaSync.syncKafkaClusters(controlPlane.getKafkaClusters(id));
    }
    
}
