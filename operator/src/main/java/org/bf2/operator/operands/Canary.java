package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.openshift.api.model.Parameter;
import io.fabric8.openshift.api.model.ParameterBuilder;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import org.bf2.common.OperandUtils;
import org.bf2.operator.managers.ImagePullSecretManager;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ServiceAccount;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final String CANARY_SASL_MECHANISM = "SASL_MECHANISM";
    private static final String CANARY_SASL_USER = "SASL_USER";
    private static final String CANARY_SASL_PASSWORD = "SASL_PASSWORD";
    private static final String CANARY_APP = "CANARY_APP";
    private static final String CANARY_DEPLOYMENT = "CANARY_DEPLOYMENT";
    private static final String CANARY_NAMESPACE = "CANARY_NAMESPACE";
    private static final String CANARY_KAFKA_BOOTSTRAP_SERVERS = "CANARY_KAFKA_BOOTSTRAP_SERVERS";
    private static final String CANARY_EXPECTED_CLUSTER_SIZE = "CANARY_EXPECTED_CLUSTER_SIZE";
    private static final String CANARY_KAFKA_VERSION = "CANARY_KAFKA_VERSION";
    private static final String CANARY_IMAGE_PULL_SECRETS = "CANARY_IMAGE_PULL_SECRETS";
    private static final String CANARY_VOLUME_NAME = "CANARY_VOLUME_NAME";
    private static final String CANARY_VOLUME_SECRET = "CANARY_VOLUME_SECRET";

    @Override
    public Deployment deploymentFrom(ManagedKafka managedKafka, ConfigMap companionTemplates) {
        Deployment current = cachedDeployment(managedKafka);
        Deployment deployment = Operand.deploymentFromTemplate(companionTemplates, "canary-template", canaryName(managedKafka), buildParameters(managedKafka), current);

        templateValidationWorkaround(deployment, managedKafka);
        if(this.config.getCanary().isColocateWithZookeeper()) {
            deployment.getSpec().getTemplate().getSpec().setAffinity(OperandUtils.buildZookeeperPodAffinity(managedKafka));
        }
        // setting the ManagedKafka has owner of the Canary deployment resource is needed
        // by the operator sdk to handle events on the Deployment resource properly
        OperandUtils.setAsOwner(managedKafka, deployment);
        return deployment;
    }


    private void templateValidationWorkaround(Deployment deployment, ManagedKafka managedKafka) {
        // we need to add Deployment parameters which cause validation failures while unmarshalling the template
        // https://github.com/fabric8io/kubernetes-client/issues/3460
        Container containerTemplate = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        deployment.getSpec().getTemplate().getSpec().setImagePullSecrets(imagePullSecretManager.getOperatorImagePullSecrets(managedKafka));

        Optional<ServiceAccount> canaryServiceAccount = managedKafka.getServiceAccount(ServiceAccount.ServiceAccountName.Canary);
        if (canaryServiceAccount.isPresent()) {
            containerTemplate.getEnv().add(new EnvVarBuilder().withName(CANARY_SASL_MECHANISM).withValue("PLAIN").build());
            containerTemplate.getEnv().add(new EnvVarBuilder().withName(CANARY_SASL_USER).withValue(canaryServiceAccount.get().getPrincipal()).build());
            containerTemplate.getEnv().add(new EnvVarBuilder().withName(CANARY_SASL_PASSWORD).withValue(canaryServiceAccount.get().getPassword()).build());
        }
    }

    private Map<String, String> buildParameters(ManagedKafka managedKafka) {
        List<Parameter> parameters = new ArrayList<>(10);
        parameters.add(new ParameterBuilder().withName(CANARY_APP).withValue(canaryName(managedKafka)).build());
        parameters.add(new ParameterBuilder().withName(CANARY_DEPLOYMENT).withValue(canaryName(managedKafka)).build());
        parameters.add(new ParameterBuilder().withName(CANARY_NAMESPACE).withValue(managedKafka.getMetadata().getNamespace()).build());
        parameters.add(new ParameterBuilder().withName(CANARY_KAFKA_BOOTSTRAP_SERVERS).withValue(managedKafka.getMetadata().getName() + "-kafka-bootstrap:9093").build());
        parameters.add(new ParameterBuilder().withName(CANARY_EXPECTED_CLUSTER_SIZE).withValue(String.valueOf(this.config.getKafka().getReplicas())).build());
        parameters.add(new ParameterBuilder().withName(CANARY_KAFKA_VERSION).withValue(managedKafka.getSpec().getVersions().getKafka()).build());
        parameters.add(new ParameterBuilder().withName(CANARY_IMAGE_PULL_SECRETS).withValue("[" + imagePullSecretManager.getOperatorImagePullSecrets(managedKafka).stream().map(ref -> "'" + ref.getName() + "'").collect(Collectors.joining(", ")) + "]").build());
        parameters.add(new ParameterBuilder().withName(CANARY_VOLUME_NAME).withValue(managedKafka.getMetadata().getName() + "-tls-ca-cert").build());
        parameters.add(new ParameterBuilder().withName(CANARY_VOLUME_SECRET).withValue(managedKafka.getMetadata().getName() + "-cluster-ca-cert").build());

        return parameters.stream().collect(Collectors.toMap(entry -> entry.getName(), entry -> entry.getValue() == null ? "\"\"" :  entry.getValue()));
    }
}
