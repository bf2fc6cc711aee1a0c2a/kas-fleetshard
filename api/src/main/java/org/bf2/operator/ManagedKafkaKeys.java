package org.bf2.operator;

import java.util.Set;

public final class ManagedKafkaKeys {

    public static final String GROUP = "managedkafka.bf2.org";
    static final String MK_PREFIX = GROUP + "/";

    public static String forKey(String key) {
        return MK_PREFIX + key;
    }

    public static final class Annotations {
        /**
         * Digest annotation of the master-secret data values.
         */
        public static final String MASTER_SECRET_DIGEST = MK_PREFIX + "master-secret-digest";

        /**
         * Digest of a deployment's secret values. When secrets are modified, updates to the digest
         * are used to trigger the deployment pods to be recreated.
         */
        public static final String SECRET_DEPENDENCY_DIGEST = MK_PREFIX + "secret-dependency-digest";

        /**
         * Annotation containing the timestamp the operator updates the Strimzi Kafka resource
         * with a new version.
         */
        public static final String KAFKA_UPGRADE_START_TIMESTAMP = MK_PREFIX + "kafka-upgrade-start";

        /**
         * Annotation containing the timestamp of when the operator determines Strimzi has finished
         * upgrading the Kafka version.
         */
        public static final String KAFKA_UPGRADE_END_TIMESTAMP = MK_PREFIX + "kafka-upgrade-end";

        /**
         * Annotation declaring the reason Strimzi Kafka reconciliation has been paused.
         */
        public static final String STRIMZI_PAUSE_REASON = MK_PREFIX + "pause-reason";

        /**
         * Annotation used by the Strimzi Operator OLM bundle that contains the Kafka versions
         * and images supported by each Strimzi deployment.
         */
        public static final String KAFKA_IMAGES = MK_PREFIX + "kafka-images";

        /**
         * Annotation used by the Strimzi Operator OLM bundle that contains the related image versions
         * supported by each Strimzi deployment. This includes the Strimzi Canary and Kafka Admin Server.
         */
        public static final String RELATED_IMAGES = MK_PREFIX + "related-images";

        /**
         * Annotation to pause reconciliation of the ManagedKafka resource. Pausing reconciliation
         * requires the value of this annotation to be {@code true}.
         */
        public static final String PAUSE_RECONCILIATION = MK_PREFIX + "pause-reconciliation";

        /**
         * Set of annotations managed by the data plane - expand as needed.
         */
        public static final Set<String> DATA_PLANE_ANNOTATIONS = Set.of(MASTER_SECRET_DIGEST, PAUSE_RECONCILIATION);

        private Annotations() {
        }
    }

    public static final class Labels {

        /**
         * The label key for the multi-AZ IngressController
         */
        public static final String KAS_MULTI_ZONE = MK_PREFIX + "kas-multi-zone";

        /**
         * Default label used by the Strimzi Operator to identify the Kafka resources under management.
         * The label must align with the value configured for the Strimzi deployments in the
         * Strimzi Operator OLM bundle.
         */
        public static final String STRIMZI_VERSION = MK_PREFIX + "strimziVersion";

        private Labels() {
        }
    }

    private ManagedKafkaKeys() {
    }
}
