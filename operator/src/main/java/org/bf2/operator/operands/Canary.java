package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSource;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Parameter;
import io.fabric8.openshift.api.model.ParameterBuilder;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.Startup;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Provides same functionalities to get a Canary deployment from a ManagedKafka one
 * and checking the corresponding status
 */
@Startup
@ApplicationScoped
@DefaultBean
public class Canary extends AbstractCanary {

    @Inject
    protected ImagePullSecretManager imagePullSecretManager;

    @Inject
    protected KafkaInstanceConfiguration config;

    OpenShiftClient openShiftClient;

    void onStart(@Observes StartupEvent ev) {
        if (kubernetesClient.isAdaptable(OpenShiftClient.class)) {
            openShiftClient = kubernetesClient.adapt(OpenShiftClient.class);
        }
    }

    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, ConfigMap companionTemplates) {
        Template template = Serialization.unmarshal(companionTemplates.getData().get("template"), Template.class);
        template.setParameters(buildParameters(managedKafka));

        if (openShiftClient != null) {
            // oc process somehow
            openShiftClient.templates().inNamespace(managedKafka.getMetadata().getName());
        }
        Deployment deployment = template.getObjects().stream().map(object -> (Deployment) object).filter(dep -> dep.getMetadata().getName().equals("canary-deployment")).findFirst().get();

        if(this.config.getCanary().isColocateWithZookeeper()) {
            builder
                    .editOrNewSpec()
                        .editOrNewTemplate()
                            .editOrNewSpec()
                                .withAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka))
                            .endSpec()
                        .endTemplate()
                    .endSpec();
        }

        // setting the ManagedKafka has owner of the Canary deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);

        return deployment;
    }

    private List<Parameter> buildParameters(ManagedKafka managedKafka) {
        List<Parameter> parameters = new ArrayList<>(10);
        parameters.add(new ParameterBuilder().withName("CANARY_APP").withValue(managedKafka.getMetadata().getName() + "-canary").build());
        parameters.add(new ParameterBuilder().withName("KAFKA_BOOTSTRAP_SERVERS").withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9093").build());
        parameters.add(new ParameterBuilder().withName("EXPECTED_CLUSTER_SIZE").withValue(String.valueOf(this.config.getKafka().getReplicas())).build());
        parameters.add(new ParameterBuilder().withName("KAFKA_VERSION").withValue(managedKafka.getSpec().getVersions().getKafka()).build());

        EnvVarSource saramaLogEnabled =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName("canary-config")
                            .withKey("sarama.log.enabled")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        EnvVarSource verbosityLogLevel =
                new EnvVarSourceBuilder()
                        .editOrNewConfigMapKeyRef()
                            .withName("canary-config")
                            .withKey("verbosity.log.level")
                            .withOptional(Boolean.TRUE)
                        .endConfigMapKeyRef()
                        .build();

        parameters.add(envVarToParameter(new EnvVarBuilder().withName("SARAMA_LOG_ENABLED").withValueFrom(saramaLogEnabled).build()));
        parameters.add(envVarToParameter(new EnvVarBuilder().withName("VERBOSITY_LOG_LEVEL").withValueFrom(verbosityLogLevel).build()));
        envVars.add(new EnvVarBuilder().withName("TOPIC_CONFIG").withValue("retention.ms=600000;segment.bytes=16384").build());

        Optional<ServiceAccount> canaryServiceAccount = managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary);
        if (canaryServiceAccount.isPresent()) {
            parameters.add(new ParameterBuilder().withName("SASL_MECHANISM").withValue("PLAIN").build());
            parameters.add(new ParameterBuilder().withName("SASL_USER").withValue(canaryServiceAccount.get().getPrincipal()).build());
            parameters.add(new ParameterBuilder().withName("SASL_PASSWORD").withValue(canaryServiceAccount.get().getPassword()).build());
        } else {
            parameters.add(new ParameterBuilder().withName("SASL_MECHANISM").withValue("").build());
            parameters.add(new ParameterBuilder().withName("SASL_USER").withValue("").build());
            parameters.add(new ParameterBuilder().withName("SASL_PASSWORD").withValue("").build());
        }

        parameters.add(new ParameterBuilder().withName("CANARY_IMAGE_PULL_SECRETS").withValue(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka).stream().map(ref -> ref.getName()).collect(Collectors.joining(", "))).build());

        parameters.add(new ParameterBuilder().withName("CANARY_VOLUME_NAME").withValue(managedKafka.getMetadata().getName() + "-tls-ca-cert").build());
        parameters.add(new ParameterBuilder().withName("CANARY_VOLUME_SECRET").withValue(managedKafka.getMetadata().getName() + "-cluster-ca-cert").build());

        return parameters;
    }

    // Parameter does not provide valueFrom, so we need to use a workaround
    private Parameter envVarToParameter(EnvVar envVar) {
        return new ParameterBuilder().withName(envVar.getName()).withValue(envVar.getValue()).build();
    }
}
