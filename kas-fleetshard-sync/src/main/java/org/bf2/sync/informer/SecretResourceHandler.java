package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.quarkus.runtime.Quarkus;

import org.jboss.logging.Logger;

class SecretResourceHandler implements ResourceEventHandler<Secret> {

    static Logger log = Logger.getLogger(SecretResourceHandler.class);

    private boolean secretUpdated = false;
    private boolean secretAvailable = true;

    public SecretResourceHandler(KubernetesClient client) {
        Secret secret = client.secrets().inNamespace(client.getNamespace()).withName(InformerManager.SECRET_NAME).get();
        if (secret == null) {
            this.secretAvailable = false;
            log.warn(InformerManager.SECRET_NAME + " not found, addon secret is required");
        }
    }

    @Override
    public void onAdd(Secret obj) {
        if(isAddOnFleetShardSecret(obj)) {
            this.secretAvailable = true;
            log.debug(InformerManager.SECRET_NAME + " added");
        }
        reconcile();
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        if(isAddOnFleetShardSecret(oldObj) && !oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
            this.secretUpdated = true;
            log.debug(InformerManager.SECRET_NAME + " updated");
        }
        reconcile();
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        if(isAddOnFleetShardSecret(obj)) {
            this.secretAvailable = false;
            this.secretUpdated = true;
            log.debug(InformerManager.SECRET_NAME + " deleted");
        }
        reconcile();
    }

    boolean isAddOnFleetShardSecret(Secret obj) {
        return obj.getMetadata().getName().equals(InformerManager.SECRET_NAME);
    }

    public boolean isSecretChanged() {
        return this.secretUpdated;
    }

    public boolean isSecretAvailable() {
        return this.secretAvailable;
    }

    /**
     * This method is used to bounce the Sync pod so that new configuration is immediately taken effect.
     */
    private void reconcile() {
        if (!isSecretAvailable() || isSecretChanged()) {
            log.info(InformerManager.SECRET_NAME + " changed or deleted, requires a restart to pickup new configuration");
            Quarkus.asyncExit(-2);
        }
    }
}