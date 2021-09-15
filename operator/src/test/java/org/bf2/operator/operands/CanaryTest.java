package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
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
        ConfigMap cm = new ConfigMapBuilder().withNewMetadata().withName("companion-templates-config-map")
                .endMetadata()
                .withData(Collections.singletonMap("canary-template",
                        "apiVersion: v1\n" +
                        "kind: Template\n" +
                        "metadata:\n" +
                        "  name: companion-template\n" +
                        "objects:\n" +
                        "  # Canary deployment\n" +
                        "  - apiVersion: apps/v1\n" +
                        "    kind: Deployment\n" +
                        "    metadata:\n" +
                        "      name: canary-deployment\n" +
                        "      namespace: ${CANARY_NAMESPACE}\n" +
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
                        "                  value: \"${CANARY_KAFKA_BOOTSTRAP_SERVERS}\"\n" +
                        "                - name: RECONCILE_INTERVAL_MS\n" +
                        "                  value: 5000\n" +
                        "                - name: EXPECTED_CLUSTER_SIZE\n" +
                        "                  value: ${CANARY_EXPECTED_CLUSTER_SIZE}\n" +
                        "                - name: KAFKA_VERSION\n" +
                        "                  value: ${CANARY_KAFKA_VERSION}\n" +
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
                        "                  value: \"${CANARY_SASL_MECHANISM}\"\n" +
                        "                - name: SASL_USER\n" +
                        "                  value: \"${CANARY_SASL_USER}\"\n" +
                        "                - name: SASL_PASSWORD\n" +
                        "                  value: \"${CANARY_SASL_PASSWORD}\"\n" +
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
                        // validation issue with arrays in template
                        //"        imagePullSecrets: ${{CANARY_IMAGE_PULL_SECRETS}}\n" +
                        "        volumes:\n" +
                        "          - name: ${CANARY_VOLUME_NAME}\n" +
                        "            secret: \n" +
                        "              name: ${CANARY_VOLUME_SECRET}\n"))
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
}
