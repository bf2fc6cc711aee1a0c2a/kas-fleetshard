package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentList;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.bf2.sync.controlplane.ControlPlane;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class InformerManager implements LocalLookup {
    public static final String SECRET_NAME = "addon-kas-fleetshard-operator-parameters";

    @ConfigProperty(name = "resync.interval")
    Duration resync;

    @Inject
    KubernetesClient client;

    @Inject
    ControlPlane controlPlane;

    SecretResourceHandler secretHandler;

    private SharedInformerFactory sharedInformerFactory;

    private SharedIndexInformer<ManagedKafka> managedKafkaInformer;
    private SharedIndexInformer<ManagedKafkaAgent> managedAgentInformer;
    private SharedIndexInformer<Secret> secretSharedIndexInformer;

    @PostConstruct
    protected void onStart() {
        sharedInformerFactory = client.informers();

        managedKafkaInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafka.class, ManagedKafkaList.class,
                resync.toMillis());
        managedKafkaInformer.addEventHandler(CustomResourceEventHandler.of(controlPlane::updateKafkaClusterStatus));

        // for the Agent
        managedAgentInformer = sharedInformerFactory.sharedIndexInformerFor(ManagedKafkaAgent.class, ManagedKafkaAgentList.class,
                resync.toMillis());
        managedAgentInformer.addEventHandler(CustomResourceEventHandler.of(controlPlane::updateAgentStatus));

        // namespace scoped operation context. Note: "withName" in operation context filter yielded unexpected results in testing
        OperationContext nsContext = new OperationContext().withNamespace(this.client.getNamespace());
        secretSharedIndexInformer =
                sharedInformerFactory.sharedIndexInformerFor(Secret.class, SecretList.class, nsContext, 60 * 1000L);
        this.secretHandler = new SecretResourceHandler(this.client);
        secretSharedIndexInformer.addEventHandler(this.secretHandler);

        sharedInformerFactory.startAllRegisteredInformers();
    }

    @PreDestroy
    protected void onStop() {
        sharedInformerFactory.stopAllRegisteredInformers();
    }

    @Override
    public ManagedKafka getLocalManagedKafka(String metaNamespaceKey) {
        return managedKafkaInformer.getIndexer().getByKey(metaNamespaceKey);
    }

    @Override
    public List<ManagedKafka> getLocalManagedKafkas() {
        return managedKafkaInformer.getIndexer().list();
    }

    public boolean isReady() {
        return managedAgentInformer.hasSynced() && managedAgentInformer.hasSynced();
    }

    public boolean isConfigurationSecretChanged() {
        if (this.secretHandler == null) {
            return false;
        }
        return this.secretHandler.isSecretChanged();
    }

    public boolean isConfigurationSecretAvailable() {
        if (this.secretHandler == null) {
            return false;
        }
        return this.secretHandler.isSecretAvailable();
    }

    @Override
    public ManagedKafkaAgent getLocalManagedKafkaAgent() {
        List<ManagedKafkaAgent> list = managedAgentInformer.getIndexer().list();
        if (list.isEmpty()) {
            return null;
        }
        // we're assuming it's a singleton
        return list.get(0);
    }
}