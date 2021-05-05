package org.bf2.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class OperandUtils {

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

    public static Map<String, String> getDefaultLabels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(1);
        result.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return result;
    }
}
