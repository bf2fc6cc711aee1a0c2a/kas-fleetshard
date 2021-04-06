package org.bf2.operator.operands.test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;

import javax.enterprise.context.ApplicationScoped;

import org.bf2.operator.operands.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.Context;
import io.quarkus.arc.properties.IfBuildProperty;
import io.strimzi.api.kafka.model.Kafka;

/**
 * Provides same functionalities to get a Kafka resource from a ManagedKafka one
 * and checking the corresponding status
 * For testing purpose only, it puts the Kafka declaration into a ConfigMap so the actual Kafka cluster is not created
 */
@ApplicationScoped
@IfBuildProperty(name = "kafka", stringValue = "test")
public class KafkaCluster extends org.bf2.operator.operands.KafkaCluster {

    @Override
    protected void createOrUpdate(Kafka kafka) {
        // Kafka resource doesn't exist, has to be created
        if (kubernetesClient.configMaps()
                .inNamespace(kafka.getMetadata().getNamespace())
                .withName(kafka.getMetadata().getName()).get() == null) {

            ConfigMap cm = new ConfigMapBuilder()
                    .withNewMetadata()
                        .withNamespace(kafka.getMetadata().getNamespace())
                        .withName(kafka.getMetadata().getName())
                        .withLabels(OperandUtils.getDefaultLabels())
                        .withOwnerReferences(kafka.getMetadata().getOwnerReferences())
                    .endMetadata()
                    .withData(Collections.singletonMap("kafka", kafka.toString()))
                    .build();

            kubernetesClient.configMaps().inNamespace(kafka.getMetadata().getNamespace()).create(cm);
        // Kafka resource already exists, has to be updated
        } else {
            ConfigMap cm = kubernetesClient.configMaps()
                    .inNamespace(kafka.getMetadata().getNamespace())
                    .withName(kafka.getMetadata().getName()).get();

            kubernetesClient.configMaps()
                    .inNamespace(kafka.getMetadata().getNamespace())
                    .withName(kafka.getMetadata().getName())
                    .patch(cm);
        }
    }

    @Override
    public void delete(ManagedKafka managedKafka, Context<ManagedKafka> context) {
        kubernetesClient.configMaps()
                .inNamespace(kafkaClusterNamespace(managedKafka))
                .withName(kafkaClusterName(managedKafka))
                .delete();

        configMapResource(managedKafka, kafkaMetricsConfigMapName(managedKafka)).delete();
        configMapResource(managedKafka, zookeeperMetricsConfigMapName(managedKafka)).delete();

        if (isKafkaExternalCertificateEnabled) {
            secretResource(managedKafka, kafkaTlsSecretName(managedKafka)).delete();
        }
        if (isKafkaAuthenticationEnabled) {
            secretResource(managedKafka, ssoClientSecretName(managedKafka)).delete();
            secretResource(managedKafka, ssoTlsSecretName(managedKafka)).delete();
        }
    }

    @Override
    protected Kafka cachedKafka(ManagedKafka managedKafka) {
        ConfigMap cm = informerManager.getLocalConfigMap(kafkaClusterNamespace(managedKafka), kafkaClusterName(managedKafka));
        if (cm == null)
            return null;
        InputStream is = new ByteArrayInputStream(cm.getData().get("kafka").getBytes());
        return Serialization.unmarshal(is, Kafka.class);
    }
}
