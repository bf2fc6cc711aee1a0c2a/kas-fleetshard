package org.bf2.sync.informer;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;

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
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        if(isAddOnFleetShardSecret(oldObj) && !oldObj.getMetadata().getResourceVersion().equals(newObj.getMetadata().getResourceVersion())) {
            this.secretUpdated = true;
            log.debug(InformerManager.SECRET_NAME + " updated");
        }
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        if(isAddOnFleetShardSecret(obj)) {
            this.secretAvailable = false;
            this.secretUpdated = true;
            log.debug(InformerManager.SECRET_NAME + " deleted");
        }
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
}