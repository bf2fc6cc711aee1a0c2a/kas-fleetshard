package org.bf2.operator.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.zjsonpatch.JsonDiff;
import io.javaoperatorsdk.operator.processing.event.source.filter.OnUpdateFilter;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resource event filter for use with
 * {@link io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration#eventFilters
 * eventFilters} used by {@code ManagedKafka} and {@code ManagedKafkaAgent}
 * controllers. This event filter will accept any change where the resource:
 * <ul>
 * <li>is added
 * <li>generation has changed
 * <li>annotations have changed
 * <li>labels have changed
 * </ul>
 *
 */
public class ControllerEventFilter implements OnUpdateFilter<HasMetadata> {

    private static Logger log = Logger.getLogger(ControllerEventFilter.class);

    @Override
    public boolean accept(HasMetadata oldResource,
                          HasMetadata newResource) {

        Optional<ObjectMeta> oldMeta = meta(oldResource);
        Optional<ObjectMeta> newMeta = meta(newResource);

        boolean resourceChanged =
                oldResource == null || // Always accept an "add" change
                changed(oldMeta, newMeta, this::generation) ||
                changed(oldMeta, newMeta, this::annotations) ||
                changed(oldMeta, newMeta, this::labels);

        if (log.isDebugEnabled() && resourceChanged && oldResource != null) {
            String kind = Optional.ofNullable(oldResource.getKind()).orElseGet(newResource::getKind);
            String name = newMeta.map(ObjectMeta::getName).orElse("<UNKNOWN NAME>");
            ObjectMapper objectMapper = Serialization.yamlMapper();
            JsonNode expectedJson = objectMapper.convertValue(oldResource, JsonNode.class);
            JsonNode actualJson = objectMapper.convertValue(newResource, JsonNode.class);
            JsonNode patch = JsonDiff.asJson(expectedJson, actualJson);
            log.debugf("%s/%s changed =>\n%s", kind, name, patch.toPrettyString());
        }

        return resourceChanged;
    }

    Optional<ObjectMeta> meta(HasMetadata resource) {
        return Optional.ofNullable(resource).map(HasMetadata::getMetadata);
    }

    Long generation(Optional<ObjectMeta> meta) {
        return meta.map(ObjectMeta::getGeneration).orElse(null);
    }

    Map<String, String> annotations(Optional<ObjectMeta> meta) {
        return meta.map(ObjectMeta::getAnnotations).orElse(Collections.emptyMap());
    }

    Map<String, String> labels(Optional<ObjectMeta> meta) {
        return meta.map(ObjectMeta::getLabels).orElse(Collections.emptyMap());
    }

    boolean changed(Optional<ObjectMeta> oldMeta, Optional<ObjectMeta> newMeta, Function<Optional<ObjectMeta>, Object> source) {
        Object oldAttr = source.apply(oldMeta);
        Object newAttr = source.apply(newMeta);
        return !Objects.equals(oldAttr, newAttr);
    }
}
