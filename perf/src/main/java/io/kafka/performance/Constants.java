package io.kafka.performance;

public class Constants {
    public static final String SUITE_ROOT = System.getProperty("user.dir");
    public static final String OMB_NAMESPACE = "omb";
    public static final String OMB_WORKER_IMAGE = "quay.io/grdryn/openmessaging-benchmark:latest";
    public static final String OPENSHIFT_INGRESS = "openshift-ingress";
    public static final String OPENSHIFT_INGRESS_OPERATOR = "openshift-ingress-operator";
    public static final String IO_KAFKA_PERFORMANCE_COLLECTPODLOG = "io.kafka.performance/collectpodlog";
    public static final String IO_KAFKA_PERFORMANCE_CHECKRESTARTEDCONTAINERS = "io.kafka.performance/checkrestartedcontainers";
    public static final String KAFKA_NAMESPACE = "kafka";
    public static final String KAFKA_IMAGE = "quay.io/grdryn/kafka:0.21.1-kafka-2.7.0-quota";
    public static final String MK_STORAGECLASS = "mk-storageclass";
    public static final String DRAIN_CLEANER_INSTALL_DIR = "src/main/resources/drain-cleaner";
    // changing DrainCleaner namespace requires webhook and certs update to match the new address
    public static final String DRAIN_CLEANER_NAMESPACE = "strimzi-drain-cleaner";
}
