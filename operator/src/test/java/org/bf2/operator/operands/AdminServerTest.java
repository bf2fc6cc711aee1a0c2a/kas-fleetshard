package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
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
public class AdminServerTest {

    @KubernetesTestServer
    KubernetesServer server;

    @Inject
    AdminServer adminServer;

    @Test
    void createAdminServerDeployment() {
        ConfigMap cm = new ConfigMapBuilder().withNewMetadata().withName("companion-templates-config-map")
                .endMetadata()
                .withData(Collections.singletonMap("admin-server-template",
                        "apiVersion: v1\n" +
                        "kind: Template\n" +
                        "metadata:\n" +
                        "  name: companion-template\n" +
                        "objects:\n" +
                        "  # Admin deployment\n" +
                        "  - apiVersion: apps/v1\n" +
                        "    kind: Deployment\n" +
                        "    metadata:\n" +
                        "      name: admin-server-deployment\n" +
                        "      namespace: ${KAFKA_ADMIN_NAMESPACE}\n" +
                        "      labels:\n" +
                        "        app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "        app.kubernetes.io/component: adminserver\n" +
                        "        app: ${KAFKA_ADMIN_SERVER_APP}\n" +
                        "    spec:\n" +
                        "      replicas: 1\n" +
                        "      selector:\n" +
                        "        matchLabels:\n" +
                        "          app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "          app: ${KAFKA_ADMIN_SERVER_APP}\n" +
                        "      template:\n" +
                        "        metadata:\n" +
                        "          labels:\n" +
                        "            app.kubernetes.io/managed-by: kas-fleetshard-operator\n" +
                        "            app.kubernetes.io/component: adminserver\n" +
                        "            app: ${KAFKA_ADMIN_SERVER_APP}\n" +
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
                        "                  value: ${KAFKA_ADMIN_CORS_ALLOW_LIST_REGEX}\n" +
                        "              ports:\n" +
                        // we need to use a placeholder for port adter fabric8
                        // validation issue (https://github.com/fabric8io/kubernetes-client/issues/3460)
                        // is fixed
                        //"                - containerPort: ${KAFKA_ADMIN_API_PORT}\n" +
                        //"                  name: ${KAFKA_ADMIN_API_PORT_NAME}\n" +
                        //"                - containerPort: 8080\n" +
                        //"                  name: http\n" +
                        //"                - containerPort: 8443\n" +
                        //"                  name: https\n" +
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
                        "                - name: ${KAFKA_ADMIN_VOLUME_NAME}\n" +
                        "                  mountPath: /opt/kafka-admin-api/custom-config/\n" +
                        // validation issue with arrays in template
                        //"          imagePullSecrets: ${{KAFKA_ADMIN_IMAGE_PULL_SECRETS}}\n" +
                        "          volumes:\n" +
                        "            - name: ${KAFKA_ADMIN_VOLUME_NAME}\n" +
                        "              secret: \n" +
                        "                name: ${KAFKA_ADMIN_VOLUME_SECRET}"))
                .build();

        ManagedKafka mk = new ManagedKafkaBuilder()
                .withNewMetadata()
                    .withNamespace("test")
                    .withName("test-mk")
                .endMetadata()
                .withSpec(
                        new ManagedKafkaSpecBuilder()
                                .withNewEndpoint().endEndpoint()
                                .withNewVersions()
                                .withKafka("2.6.0")
                                .endVersions()
                                .build())
                .build();

        Deployment adminServerDeployment = adminServer.deploymentFrom(mk, cm);

        server.getClient().apps().deployments().create(adminServerDeployment);
        assertNotNull(server.getClient().apps().deployments()
                .inNamespace(adminServerDeployment.getMetadata().getNamespace())
                .withName(adminServerDeployment.getMetadata().getName()).get());
    }
}
