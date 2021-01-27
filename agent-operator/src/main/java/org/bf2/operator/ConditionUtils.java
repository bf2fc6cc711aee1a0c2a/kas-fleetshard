package org.bf2.operator;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaConditionBuilder;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
        return conditions.stream().filter(c -> c.getType().equals(type.name())).findFirst();
    }

    /**
     * Set a condition into the provided conditions list.
     * If the provided condition type doesn't exist, it's just added to the list.
     * If the provided condition type already exists, it's updated with the data from the new provided condition.
     *
     * @param conditions
     * @param newCondition
     */
    public static void setManagedKafkaCondition(List<ManagedKafkaCondition> conditions, ManagedKafkaCondition newCondition) {
        if (conditions == null)
            return;
        Optional<ManagedKafkaCondition> optCurrentCondition = findManagedKafkaCondition(conditions, ManagedKafkaCondition.Type.from(newCondition.getType()));
        if (optCurrentCondition.isEmpty()) {
            if (newCondition.getLastTransitionTime() == null) {
                newCondition.setLastTransitionTime(iso8601Now());
            }
            conditions.add(newCondition);
            return;
        }
        ManagedKafkaCondition currentCondition = optCurrentCondition.get();
        if (!currentCondition.getStatus().equals(newCondition.getStatus())) {
            currentCondition.setStatus(newCondition.getStatus());
            if (newCondition.getLastTransitionTime() == null) {
                currentCondition.setLastTransitionTime(iso8601Now());
            } else {
                currentCondition.setLastTransitionTime(newCondition.getLastTransitionTime());
            }
        }
        currentCondition.setReason(newCondition.getReason());
        currentCondition.setMessage(newCondition.getMessage());
    }

    /**
     * Build and return a ManagedKafkaCondition with provided type and status
     *
     * @param type condition type
     * @param status condition status
     * @return created ManagedKafkaCondition
     */
    public static ManagedKafkaCondition buildCondition(ManagedKafkaCondition.Type type, String status) {
        return new ManagedKafkaConditionBuilder()
                .withType(type.name())
                .withStatus(status)
                .withLastTransitionTime(ConditionUtils.iso8601Now())
                .build();
    }

    /**
     * Updated a condition to the provided status only if it's changed updating the last transition time as well
     *
     * @param condition condition on which updating the status
     * @param newStatus new status to update
     */
    public static void updateConditionStatus(ManagedKafkaCondition condition, String newStatus) {
        if (!condition.getStatus().equals(newStatus)) {
            condition.setStatus(newStatus);
            condition.setLastTransitionTime(ConditionUtils.iso8601Now());
        }
    }

    /**
     * Returns the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     * @return the current timestamp in ISO 8601 format, for example "2019-07-23T09:08:12.356Z".
     */
    public static String iso8601Now() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    }
}
