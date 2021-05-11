package org.bf2.sync;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.HashMap;
import java.util.Map;

/**
 * KubernetesMockServerTestResource is not useful for crud tests, so we need to
 * inject a different client / server
 *
 * Adapted from KubernetesMockServerTestResource
 *
 * Logged https://github.com/quarkusio/quarkus/issues/14744 just in case it's of interest more broadly.
 */
public class KubernetesServerTestResource implements QuarkusTestResourceLifecycleManager {

    private KubernetesServer server;

    @Override
    public Map<String, String> start() {
        final Map<String, String> systemProps = new HashMap<>();
        systemProps.put(Config.KUBERNETES_TRUST_CERT_SYSTEM_PROPERTY, "true");
        systemProps.put(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        systemProps.put(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
        systemProps.put(Config.KUBERNETES_NAMESPACE_SYSTEM_PROPERTY, "test");
        systemProps.put(Config.KUBERNETES_HTTP2_DISABLE, "true");

        server = createServer();
        server.before();
        try (NamespacedKubernetesClient client = server.getClient()) {
            systemProps.put(Config.KUBERNETES_MASTER_SYSTEM_PROPERTY, client.getConfiguration().getMasterUrl());
        }

        configureServer(server);

        return systemProps;
    }

    protected KubernetesServer createServer() {
        return new KubernetesServer(useHttps(), true);
    }

    /**
     * Can be used by subclasses of {@code KubernetesServerTestResource} in order to
     * setup the mock server before the Quarkus application starts
     */
    public void configureServer(KubernetesServer mockServer) {
    }

    @Override
    public void stop() {
        if (server != null) {
            server.after();
        }
    }

    @Override
    public void inject(Object testInstance) {
        //no annotation
    }

    protected boolean useHttps() {
        return Boolean.getBoolean("quarkus.kubernetes-client.test.https");
    }
}
