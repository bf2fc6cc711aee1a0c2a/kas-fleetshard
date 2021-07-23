package org.bf2.operator.operands.dev;

import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.properties.IfBuildProperty;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaBuilder;
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
import org.bf2.operator.secrets.SecuritySecretManager;

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
    protected SecuritySecretManager secretManager;

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Override
    public void createOrUpdate(ManagedKafka managedKafka) {
        super.createOrUpdate(managedKafka);
        secretManager.createOrUpdate(managedKafka);
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        super.delete(managedKafka, context);
        secretManager.delete(managedKafka);
    }

    @Override
    public boolean isDeleted(ManagedKafka managedKafka) {
        return super.isDeleted(managedKafka) && secretManager.isDeleted(managedKafka);
    }

    /* test */
    @Override
    public Kafka kafkaFrom(ManagedKafka managedKafka, Kafka current) {

        KafkaBuilder builder = current != null ? new KafkaBuilder(current) : new KafkaBuilder();

        Kafka kafka = builder
                .editOrNewMetadata()
                    .withName(kafkaClusterName(managedKafka))
                    .withNamespace(kafkaClusterNamespace(managedKafka))
                    .withLabels(buildLabels(managedKafka))
                .endMetadata()
                .editOrNewSpec()
                    .editOrNewKafka()
                        .withVersion(managedKafka.getSpec().getVersions().getKafka())
                        .withReplicas(this.config.getKafka().getReplicas())
                        .withListeners(buildListeners(managedKafka))
                        .withStorage(buildStorage())
                        .withConfig(buildKafkaConfig(managedKafka))
                        .withTemplate(getKafkaTemplate(managedKafka))
                        .withImage(kafkaImage.orElse(null))
                    .endKafka()
                    .editOrNewZookeeper()
                        .withReplicas(this.config.getZookeeper().getReplicas())
                        .withStorage((SingleVolumeStorage) buildStorage())
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

    private Map<String, Object> buildKafkaConfig(ManagedKafka managedKafka) {
        Map<String, Object> config = new HashMap<>();
        config.put("offsets.topic.replication.factor", 3);
        config.put("transaction.state.log.replication.factor", 3);
        config.put("transaction.state.log.min.isr", 2);
        config.put("log.message.format.version", managedKafka.getSpec().getVersions().getKafka());
        config.put("inter.broker.protocol.version", managedKafka.getSpec().getVersions().getKafka());
        return config;
    }

    private Storage buildStorage() {
        return new EphemeralStorageBuilder().build();
    }

    private Map<String, String> buildLabels(ManagedKafka managedKafka) {
        Map<String, String> labels = OperandUtils.getDefaultLabels();
        labels.put("managedkafka.bf2.org/strimziVersion", managedKafka.getSpec().getVersions().getStrimzi());
        labels.put("dev-kafka", "");
        return labels;
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
