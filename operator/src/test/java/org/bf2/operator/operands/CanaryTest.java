package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Parameter;
import io.fabric8.openshift.api.model.ParameterBuilder;
import io.fabric8.openshift.api.model.Template;
import io.fabric8.openshift.api.model.TemplateBuilder;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.KubernetesServerTestResource;
import io.quarkus.test.kubernetes.client.KubernetesTestServer;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaBuilder;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaSpecBuilder;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTestResource(KubernetesServerTestResource.class)
@QuarkusTest
public class CanaryTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    Canary canary;

    @Test
    void createCanaryDeployment() throws Exception {
        ConfigMap cm = new ConfigMapBuilder().withNewMetadata().withName("tbd")
                .endMetadata()
                .withData(Collections.singletonMap("template", "apiVersion: v1\n" +
                        "kind: Template\n" +
                        "metadata:\n" +
                        "  name: companion-template\n" +
                        "objects:\n" +
                        "  # Canary deployment\n" +
                        "  - apiVersion: apps/v1\n" +
                        "    kind: Deployment\n" +
                        "    metadata:\n" +
                        "      name: canary-deployment\n" +
                        "      namespace: ${NAMESPACE}\n" +
                        "      labels:\n" +
                        "        app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "        app.kubernetes.io/component: canary\n" +
                        "        app: ${CANARY_APP}\n" +
                        "    spec:\n" +
                        "      replicas: 1\n" +
                        "      selector:\n" +
                        "        matchLabels:\n" +
                        "          app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "          app: ${CANARY_APP}\n" +
                        "      template:\n" +
                        "        metadata:\n" +
                        "          labels:\n" +
                        "            app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "            app.kubernetes.io/component: canary\n" +
                        "            app: ${CANARY_APP}\n" +
                        "        spec:\n" +
                        "          containers:\n" +
                        "            - name: canary\n" +
                        "              image: quay.io/mk-ci-cd/strimzi-canary:0.0.7\n" +
                        "              env:\n" +
                        "                - name: KAFKA_BOOTSTRAP_SERVERS\n" +
                        "                  value: \"${KAFKA_BOOTSTRAP_SERVERS}\"\n" +
                        "                - name: RECONCILE_INTERVAL_MS\n" +
                        "                  value: 5000\n" +
                        "                - name: EXPECTED_CLUSTER_SIZE\n" +
                        "                  value: ${EXPECTED_CLUSTER_SIZE}\n" +
                        "                - name: KAFKA_VERSION\n" +
                        "                  value: ${KAFKA_VERSION}\n" +
                        "                - name: TZ\n" +
                        "                  value: \"UTC\"\n" +
                        "                - name: TLS_ENABLED\n" +
                        "                  value: \"true\"\n" +
                        "                - name: TLS_CA_CERT\n" +
                        "                  value: /tmp/tls-ca-cert/ca.crt\n" +
                        "                - name: SARAMA_LOG_ENABLED\n" +
                        "                  valueFrom:\n" +
                        "                    configMapKeyRef:\n" +
                        "                      name: canary-config\n" +
                        "                      key: sarama.log.enabled\n" +
                        "                      optional: \"true\"\n" +
                        "                - name: VERBOSITY_LOG_LEVEL\n" +
                        "                  valueFrom:\n" +
                        "                    configMapKeyRef:\n" +
                        "                      name: canary-config\n" +
                        "                      key: verbosity.log.level\n" +
                        "                      optional: \"true\"\n" +
                        "                - name: SASL_MECHANISM\n" +
                        "                  value: \"${SASL_MECHANISM}\"\n" +
                        "                - name: SASL_USER\n" +
                        "                  value: \"${SASL_USER}\"\n" +
                        "                - name: SASL_PASSWORD\n" +
                        "                  value: \"${SASL_PASSWORD}\"\n" +
                        "              ports:\n" +
                        "                - containerPort: 8080\n" +
                        "                  name: metrics\n" +
                        "              resources:\n" +
                        "                limits:\n" +
                        "                  cpu: 10m\n" +
                        "                  memory: 64Mi\n" +
                        "                requests:\n" +
                        "                  cpu: 5m\n" +
                        "                  memory: 32Mi\n" +
                        "              readinessProbe:\n" +
                        "                httpGet:\n" +
                        "                  path: /readiness\n" +
                        "                  port: 8080\n" +
                        "                initialDelaySeconds: 15\n" +
                        "                timeoutSeconds: 5\n" +
                        "              livenessProbe:\n" +
                        "                httpGet:\n" +
                        "                  path: /liveness\n" +
                        "                  port: 8080\n" +
                        "                initialDelaySeconds: 15\n" +
                        "                timeoutSeconds: 5\n" +
                        "              volumeMounts:\n" +
                        "                - name: ${CANARY_VOLUME_NAME}\n" +
                        "                  mountPath: /tmp/tls-ca-cert\n" +
                        "        imagePullSecrets: ${CANARY_IMAGE_PULL_SECRETS}\n" +
                        "        volumes:\n" +
                        "          - name: ${CANARY_VOLUME_NAME}\n" +
                        "            secret: ${CANARY_VOLUME_SECRET}\n" +
                        "  # Admin deployment\n" +
                        "  - apiVersion: apps/v1\n" +
                        "    kind: Deployment\n" +
                        "    metadata:\n" +
                        "      name: admin-server-deployment\n" +
                        "      namespace: ${NAMESPACE}\n" +
                        "      labels:\n" +
                        "        app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "        app.kubernetes.io/component: adminserver\n" +
                        "        app: ${ADMIN_SERVER_APP}\n" +
                        "    spec:\n" +
                        "      replicas: 1\n" +
                        "      selector:\n" +
                        "        matchLabels:\n" +
                        "          app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "          app: ${ADMIN_SERVER_APP}\n" +
                        "      template:\n" +
                        "        metadata:\n" +
                        "          labels:\n" +
                        "            app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "            app.kubernetes.io/component: adminserver\n" +
                        "            app: ${ADMIN_SERVER_APP}\n" +
                        "        spec:\n" +
                        "          containers:\n" +
                        "            - name: admin-server\n" +
                        "              image: quay.io/mk-ci-cd/kafka-admin-api:0.4.0\n" +
                        "              env:\n" +
                        "                - name: KAFKA_ADMIN_BOOTSTRAP_SERVERS\n" +
                        "                  value: ${KAFKA_ADMIN_BOOTSTRAP_SERVERS}\n" +
                        "                - name: KAFKA_ADMIN_BROKER_TLS_ENABLED\n" +
                        "                  value: \"true\"\n" +
                        "                - name: KAFKA_ADMIN_BROKER_TRUSTED_CERT\n" +
                        "                  value: ${KAFKA_ADMIN_BROKER_TRUSTED_CERT}\n" +
                        "                - name: KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS\n" +
                        "                  value: ${KAFKA_ADMIN_ACL_RESOURCE_OPERATIONS}\n" +
                        "                - name: KAFKA_ADMIN_TLS_CERT\n" +
                        "                  value: ${KAFKA_ADMIN_TLS_CERT}\n" +
                        "                - name: KAFKA_ADMIN_TLS_KEY\n" +
                        "                  value: ${KAFKA_ADMIN_TLS_KEY}\n" +
                        "                - name: KAFKA_ADMIN_TLS_VERSION\n" +
                        "                  value: ${KAFKA_ADMIN_TLS_VERSION}\n" +
                        "                - name: KAFKA_ADMIN_OAUTH_TRUSTED_CERT\n" +
                        "                  value: ${KAFKA_ADMIN_OAUTH_TRUSTED_CERT}\n" +
                        "                - name: KAFKA_ADMIN_OAUTH_JWKS_ENDPOINT_URI\n" +
                        "                  value: ${KAFKA_ADMIN_OAUTH_JWKS_ENDPOINT_URI}\n" +
                        "                - name: KAFKA_ADMIN_OAUTH_VALID_ISSUER_URI\n" +
                        "                  value: ${KAFKA_ADMIN_OAUTH_VALID_ISSUER_URI}\n" +
                        "                - name: KAFKA_ADMIN_OAUTH_TOKEN_ENDPOINT_URI\n" +
                        "                  value: ${KAFKA_ADMIN_OAUTH_TOKEN_ENDPOINT_URI}\n" +
                        "                - name: KAFKA_ADMIN_OAUTH_ENABLED\n" +
                        "                  value: ${KAFKA_ADMIN_OAUTH_ENABLED}\n" +
                        "                - name: CORS_ALLOW_LIST_REGEX\n" +
                        "                  value: ${CORS_ALLOW_LIST_REGEX}\n" +
                        "              ports:\n" +
                        "                - containerPort: ${API_PORT}\n" +
                        "                  name: ${API_PORT_NAME}\n" +
                        "                - containerPort: 9990\n" +
                        "                  name: management\n" +
                        "              resources:\n" +
                        "                limits:\n" +
                        "                  cpu: 500m\n" +
                        "                  memory: 512Mi\n" +
                        "                requests:\n" +
                        "                  cpu: 250m\n" +
                        "                  memory: 256Mi\n" +
                        "              readinessProbe:\n" +
                        "                httpGet:\n" +
                        "                  path: /liveness\n" +
                        "                  port: management\n" +
                        "                initialDelaySeconds: 15\n" +
                        "                timeoutSeconds: 5\n" +
                        "              livenessProbe:\n" +
                        "                httpGet:\n" +
                        "                  path: /liveness\n" +
                        "                  port: management\n" +
                        "                initialDelaySeconds: 15\n" +
                        "                timeoutSeconds: 5\n" +
                        "              volumeMounts:\n" +
                        "                - name: ${ADMIN_VOLUME_NAME}\n" +
                        "                  mountPath: /opt/kafka-admin-api/custom-config/\n" +
                        "          imagePullSecrets: ${ADMIN_IMAGE_PULL_SECRETS}\n" +
                        "          volumes:\n" +
                        "            - name: ${ADMIN_VOLUME_NAME}\n" +
                        "              secret: ${ADMIN_VOLUME_SECRET}"))
                .build();
        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withNamespace("test")
                    .withName("test-mk")
                .endMetadata()
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Deployment canaryDeployment = canary.deploymentFrom(mk, cm);
        KafkaClusterTest.diffToExpected(canaryDeployment, "/expected/canary.yml");
    }

    @Test
    public void test() {

        Container c = new ContainerBuilder()
                .withImage("image")
                .withPorts(new ContainerPortBuilder().withContainerPort("{ENV}").withName("koko").build()).build();

        Deployment d = new DeploymentBuilder()
                .withNewSpec()
                    .withNewTemplate()
                        .withNewSpec()
                            .withContainers(c)
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        Template t = new TemplateBuilder()
                .withObjects(Collections.singletonList(d))
                .withParameters(new ParameterBuilder().withName("PICA").withValue("2").build()).build();

        String ff = Serialization.asYaml(t);

        Template temp = Serialization.unmarshal(ff, Template.class);
        System.out.println("dssd");

    }
}
