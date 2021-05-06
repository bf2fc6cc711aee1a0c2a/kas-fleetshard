package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.runtime.Quarkus;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class SecretRestartHandler {
    private static final Pattern SECRET_PATTERN = Pattern.compile(".*\\[secret=[^/]*/[^/]*/([^/]*)/([^/]*)\\]");

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
            // find from the config if possible
            // see https://github.com/quarkusio/quarkus/issues/15247
            for (ConfigSource configSource : ConfigProvider.getConfig().getConfigSources()) {
                Matcher matcher = SECRET_PATTERN.matcher(configSource.getName());
                if (!matcher.matches()) {
                    continue;
                }
                uid = matcher.group(1);
                resourceVersion = matcher.group(2);
                break;
            }

            if (resourceVersion == null) {
                resourceVersion = secret.getMetadata().getResourceVersion();
                uid = secret.getMetadata().getUid();
            }
        } else if (!resourceVersion.equals(secret.getMetadata().getResourceVersion())
                    || !uid.equals(secret.getMetadata().getUid())) {
            log.info(secretName + " changed, requires a restart to pickup new configuration");
            Quarkus.asyncExit();
        }
    }
}
