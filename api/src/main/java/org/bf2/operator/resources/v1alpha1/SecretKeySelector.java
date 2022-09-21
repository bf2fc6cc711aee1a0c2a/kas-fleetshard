
package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@JsonDeserialize(using = com.fasterxml.jackson.databind.JsonDeserializer.None.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "apiVersion",
        "kind",
        "metadata",
        "key",
        "name",
        "optional"
})
@ToString
@EqualsAndHashCode
@Buildable(editableEnabled = false, validationEnabled = false, lazyCollectionInitEnabled = false, builderPackage = "io.fabric8.kubernetes.api.builder")
@Getter
@Setter
public class SecretKeySelector implements KubernetesResource
{
    private String key;
    private String name;
    private Boolean optional;


    /**
     * No args constructor for use in serialization
     *
     */
    public SecretKeySelector() {
    }

    /**
     *
     * @param name
     * @param optional
     * @param key
     */
    public SecretKeySelector(String key, String name, Boolean optional) {
        super();
        this.key = key;
        this.name = name;
        this.optional = optional;
    }

}
