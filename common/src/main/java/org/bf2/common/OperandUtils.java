package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OperandUtils {

    public static final String FLEETSHARD_OPERATOR_NAME = "kas-fleetshard-operator";

    /**
     * Set the provided resource as owner of the resource
     *
     * @param owner instance as owner
     * @param resource resource to set the owner
     */
    public static void setAsOwner(HasMetadata owner, HasMetadata resource) {
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(owner.getApiVersion())
                .withKind(owner.getKind())
                .withName(owner.getMetadata().getName())
                .withUid(owner.getMetadata().getUid())
                .build();
        resource.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));
    }

    public static List<LocalObjectReference> getOperatorImagePullSecrets(KubernetesClient client) {
        return ImagePullSecretUtils.getImagePullSecrets(client, FLEETSHARD_OPERATOR_NAME);
    }

    public static Map<String, String> getDefaultLabels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(1);
        result.put("app.kubernetes.io/managed-by", FLEETSHARD_OPERATOR_NAME);
        return result;
    }
}
