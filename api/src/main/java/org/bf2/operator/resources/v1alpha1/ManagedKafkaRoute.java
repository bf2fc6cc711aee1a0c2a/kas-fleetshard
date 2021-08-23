package org.bf2.operator.resources.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.sundr.builder.annotations.Buildable;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Buildable(
        builderPackage = "io.fabric8.kubernetes.api.builder",
        editableEnabled = false
)
@ToString
@EqualsAndHashCode
@JsonInclude(value = JsonInclude.Include.NON_NULL)
public class ManagedKafkaRoute {

    private String name;
    private String prefix;
    private String router;

    public ManagedKafkaRoute(String name, String prefix, String router) {
        this.name = name;
        this.prefix = prefix;
        this.router = router;
    }

    public ManagedKafkaRoute() {
        this(null, null, null);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRouter() {
        return router;
    }

    public void setRouter(String router) {
        this.router = router;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }
}
