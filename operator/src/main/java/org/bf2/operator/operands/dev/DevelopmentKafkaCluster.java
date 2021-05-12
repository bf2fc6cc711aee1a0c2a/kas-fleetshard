package org.bf2.operator.operands.dev;

import io.quarkus.arc.properties.IfBuildProperty;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListeners;
import io.strimzi.api.kafka.model.listener.arraylistener.ArrayOrObjectKafkaListenersBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.listener.arraylistener.KafkaListenerType;
import io.strimzi.api.kafka.model.storage.EphemeralStorageBuilder;
import io.strimzi.api.kafka.model.storage.SingleVolumeStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplate;
import io.strimzi.api.kafka.model.template.KafkaClusterTemplateBuilder;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplateBuilder;
import org.bf2.common.OperandUtils;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.secrets.ImagePullSecretManager;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.HashMap;
import java.util.Map;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 */
@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "dev")
public class DevelopmentKafkaCluster extends AbstractKafkaCluster {

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    /* test */
    @Override
    protected Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {

        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        Kafka kafka = builder
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(getLabels())
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withReplicas(3)
                        .withListeners(getListeners())
                        .withStorage(getStorage())
                        .withConfig(getKafkaConfig(managedKafka))
                        .withTemplate(getKafkaTemplate(managedKafka))
                        .withImage(kafkaImage.orElse(null))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(3)
                        .withStorage((SingleVolumeStorage)getStorage())
                        .withTemplate(getZookeeperTemplate(managedKafka))
                        .withImage(zookeeperImage.orElse(null))
                    .endZookeeper()
                .endSpec()
                .build();

        // setting the ManagedKafka has owner of the Kafka resource is needed
        // by the operator sdk to handle events on the Kafka resource properly
        OperandUtils.setAsOwner(managedKafka, kafka);

        return kafka;
    }

    private Map<String, Object> getKafkaConfig(ManagedKafka managedKafka) {
        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());
        return config;
    }

    private ArrayOrObjectKafkaListeners getListeners() {
        return new ArrayOrObjectKafkaListenersBuilder()
                .withGenericKafkaListeners(
                        new GenericKafkaListenerBuilder()
                                .withName("plain")
                                .withPort(9092)
                                .withType(KafkaListenerType.INTERNAL)
                                .withTls(false)
                                .build()
                ).build();
    }

    private Storage getStorage() {
        return new EphemeralStorageBuilder().build();
    }

    private Map<String, String> getLabels() {
        return OperandUtils.getDefaultLabels();
    }

    private KafkaClusterTemplate getKafkaTemplate(ManagedKafka managedKafka) {
        return new KafkaClusterTemplateBuilder()
                .withNewPod()
                    .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                .endPod()
            .build();
    }

    private ZookeeperClusterTemplate getZookeeperTemplate(ManagedKafka managedKafka) {
        return new ZookeeperClusterTemplateBuilder()
                .withNewPod()
                    .withImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka))
                .endPod()
            .build();
    }

}
