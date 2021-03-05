package org.bf2.operator.resources.v1alpha1;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.dekorate.crd.annotation.Crd;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.sundr.builder.annotations.Buildable;
import io.sundr.builder.annotations.BuildableReference;

/**
 * Represents a ManagedKafka instance declaration with corresponding specification and status
 */
@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        refs = @BuildableReference(CustomResource.class),
        editableEnabled = false
)
@Group("managedkafka.bf2.org")
@Version("v1alpha1")
@Crd(group = "managedkafka.bf2.org", version = "v1alpha1")
public class ManagedKafka extends CustomResource<ManagedKafkaSpec, ManagedKafkaStatus> implements Namespaced {

    public final static String ID = "id";
    public final static String PLACEMENT_ID = "placementId";

    @JsonIgnore
    public String getId() {
        return getOrCreateAnnotations().get(ID);
    }

    private Map<String, String> getOrCreateAnnotations() {
        ObjectMeta metadata = getMetadata();
        if (metadata.getAnnotations() == null) {
            metadata.setAnnotations(new LinkedHashMap<>());
        }
        return metadata.getAnnotations();
    }

    public void setId(String id) {
        getOrCreateAnnotations().put(ID, id);
    }

    @JsonIgnore
    public String getPlacementId() {
        return getOrCreateAnnotations().get(PLACEMENT_ID);
    }

    public void setPlacementId(String placementId) {
        getOrCreateAnnotations().put(PLACEMENT_ID, placementId);
    }

}
