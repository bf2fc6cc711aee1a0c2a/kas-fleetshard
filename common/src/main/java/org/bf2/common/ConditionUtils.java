package org.bf2.common;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaConditionBuilder;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class ConditionUtils {

    /**
     * Search for a specific condition type in the provided conditions list
     *
     * @param conditions conditions list in which to search for the provided condition type
     * @param type condition type to search for in the list
     * @return condition found if any
     */
    public static Optional<ManagedKafkaCondition> findManagedKafkaCondition(List<ManagedKafkaCondition> conditions,
                                                                            ManagedKafkaCondition.Type type) {
        return conditions == null ? Optional.empty()
                : conditions.stream().filter(c -> c.getType().equals(type.name())).findFirst();
    }

    /**
     * Build and return a ManagedKafkaCondition with provided type and status
     *
     * @param type condition type
     * @param status condition status
     * @return created ManagedKafkaCondition
     */
    public static ManagedKafkaCondition buildCondition(ManagedKafkaCondition.Type type, ManagedKafkaCondition.Status status) {
        return new ManagedKafkaConditionBuilder()
                .withType(type.name())
                .withStatus(status.name())
                .withLastTransitionTime(ConditionUtils.iso8601Now())
                .build();
    }

    /**
     * Updated a condition to the provided status only if it's changed updating the last transition time as well
     *
     * @param condition condition on which updating the status
     * @param newStatus new status to update
     * @param newReason new reason to update
     * @param message that will always be set
     */
    public static void updateConditionStatus(ManagedKafkaCondition condition, ManagedKafkaCondition.Status newStatus, ManagedKafkaCondition.Reason newReason, String message) {
        if (!Objects.equals(condition.getStatus(), newStatus == null ? null : newStatus.name()) || !Objects.equals(condition.getReason(), newReason == null ? null : newReason.name())) {
            condition.setStatus(newStatus);
            condition.setLastTransitionTime(ConditionUtils.iso8601Now());
            condition.reason(newReason);
        }
        condition.setMessage(message);
    }

    /**
     * Returns the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     * @return the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     */
    public static String iso8601Now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }
}
