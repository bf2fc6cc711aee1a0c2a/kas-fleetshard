package org.bf2.sync.informer;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Quarkus;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class SecretRestartHandler {
    @Inject
    Logger log;

    @ConfigProperty(name="secret.name")
    String secretName;

    @Inject
    KubernetesClient client;

    private volatile String resourceVersion;
    /*
     * track the uid to detect delete/add (there unfortunately doesn't seem
     * to be a hard guarantee about resourceVersions across uids)
     */
    private volatile String uid;

    @Scheduled(every = "60s")
    public void checkSecret() {
        Secret secret = client.secrets().inNamespace(client.getNamespace()).withName(secretName).get();
        if (secret == null) {
            return;
        }
        if (resourceVersion == null) {
            // capture the initial version being used
            // will be replaced by https://github.com/quarkusio/quarkus/issues/15247
            resourceVersion = secret.getMetadata().getResourceVersion();
            uid = secret.getMetadata().getUid();
        } else if (!resourceVersion.equals(secret.getMetadata().getResourceVersion())
                    || !uid.equals(secret.getMetadata().getUid())) {
            log.info(secretName + " changed, requires a restart to pickup new configuration");
            Quarkus.asyncExit();
        }
    }
}
