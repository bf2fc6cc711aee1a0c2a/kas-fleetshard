package org.bf2.operator.operands;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;

public class OperandUtils {

    /**
     * Set the provided ManagedKafka resource as owner of the resource
     *
     * @param managedKafka ManagedKafka instance as owner
     * @param resource resource to set the owner
     */
    public static void setAsOwner(ManagedKafka managedKafka, HasMetadata resource) {
        OwnerReference ownerReference = new OwnerReferenceBuilder()
                .withApiVersion(managedKafka.getApiVersion())
                .withKind(managedKafka.getKind())
                .withName(managedKafka.getMetadata().getName())
                .withUid(managedKafka.getMetadata().getUid())
                .build();
        resource.getMetadata().setOwnerReferences(Collections.singletonList(ownerReference));
    }

    public static Map<String, String> getDefaultLabels() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(1);
        result.put("app.kubernetes.io/managed-by", "kas-fleetshard-operator");
        return result;
    }
}
