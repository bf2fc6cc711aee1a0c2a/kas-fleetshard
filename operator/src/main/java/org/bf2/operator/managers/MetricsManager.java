package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.search.Search;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaSpec;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfiguration;
import org.bf2.common.OperandUtils;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

@Startup
@ApplicationScoped
public class MetricsManager implements ResourceEventHandler<Kafka>{
    static final String KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT = "kafka_instance_spec_brokers_desired_count";
    static final String KAFKA_INSTANCE_PARTITION_LIMIT = "kafka_instance_partition_limit";
    static final String KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT = "kafka_instance_max_message_size_limit";
    static final String KAFKA_INSTANCE_CONNECTION_LIMIT = "kafka_instance_connection_limit";
    static final String KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT = "kafka_instance_connection_creation_rate_limit";
    static final String KAFKA_INSTANCE_QUOTA_CONSUMED = "kafka_instance_profile_quota_consumed";
    public static final String KAFKA_INSTANCE_PAUSED = "kafka_instance_paused";
    public static final String KAFKA_INSTANCE_SUSPENDED = "kafka_instance_suspended";

    static final String TAG_LABEL_OWNER = "owner";
    static final String TAG_LABEL_BROKER_ID = "broker_id";
    static final String TAG_LABEL_NAMESPACE = "namespace";
    static final String TAG_LABEL_INSTANCE_NAME = "instance_name";
    static final String TAG_LABEL_INSTANCE_PROFILE_TYPE = "profile_type";

    static final Tag OWNER = Tag.of(TAG_LABEL_OWNER, "KafkaInstanceMetricsManager");
    static final String TAG_LABEL_LISTENER = "listener";

    @Inject
    InformerManager informerManager;

    @Inject
    MeterRegistry meterRegistry;

    private final Map<String, AtomicReference<Kafka>> kafkaMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<ManagedKafka>> managedKafkaMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void postConstruct() {
        informerManager.registerKafkaInformerHandler(this);
    }

    @Override
    public void onAdd(Kafka kafka) {
        createOrUpdateMetrics(kafka);
    }

    @Override
    public void onUpdate(Kafka oldObj, Kafka newObj) {
        createOrUpdateMetrics(newObj);
    }

    @Override
    public void onDelete(Kafka obj, boolean deletedFinalStateUnknown) {
        deleteMetrics(obj);
    }

    static <T> AtomicReference<T> getMetricHolder(T object, Map<String, AtomicReference<T>> cache) {
        String cacheKey = Cache.metaNamespaceKeyFunc(object);
        AtomicReference<T> ref = cache.computeIfAbsent(cacheKey, k -> new AtomicReference<T>(object));
        // Note that meterRegistry.gauge() is a no-op if the gauge already exists.  To handle the update case we
        // update the existing atomic reference that is already registered with the gauge to point at the new kafka object.
        ref.set(object);
        return ref;
    }

    public void createOrUpdateMetrics(ManagedKafka managedKafka) {
        AtomicReference<ManagedKafka> ref = getMetricHolder(managedKafka, managedKafkaMap);
        Tags tags = MetricsManager.buildKafkaInstanceTags(managedKafka);

        meterRegistry.gauge(MetricsManager.KAFKA_INSTANCE_SUSPENDED, tags, ref, this::suspended);
        meterRegistry.gauge(MetricsManager.KAFKA_INSTANCE_PAUSED, tags, ref, this::paused);
    }

    public void deleteMetrics(HasMetadata resource) {
        Search.in(meterRegistry).tags(buildKafkaInstanceTags(resource)).meters().forEach(meterRegistry::remove);
        String cacheKey = Cache.metaNamespaceKeyFunc(resource);
        kafkaMap.remove(cacheKey);
        managedKafkaMap.remove(cacheKey);
    }

    private void createOrUpdateMetrics(Kafka kafka) {
        AtomicReference<Kafka> ref = getMetricHolder(kafka, kafkaMap);
        Tags tags = buildKafkaInstanceTags(kafka);

        meterRegistry.gauge(KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT, tags, ref, this::replicas);
        meterRegistry.gauge(KAFKA_INSTANCE_QUOTA_CONSUMED, tags, ref, this::getQuotaConsumed);
        meterRegistry.gauge(KAFKA_INSTANCE_PARTITION_LIMIT, tags, ref, k -> kafkaConfigValue(k, KafkaCluster.MAX_PARTITIONS));
        meterRegistry.gauge(KAFKA_INSTANCE_MAX_MESSAGE_SIZE_LIMIT, tags, ref, k -> kafkaConfigValue(k, KafkaCluster.MESSAGE_MAX_BYTES));

        Set<Meter> orphanMeters = new HashSet<>(Search.in(meterRegistry).tags(tags).tagKeys(TAG_LABEL_BROKER_ID).meters());

        Optional<GenericKafkaListener> externalListener = getExternalListener(ref);
        externalListener.ifPresent(genericKafkaListener -> IntStream.range(0, kafka.getSpec().getKafka().getReplicas()).forEach(ordinal -> {
            String listenerName = String.format("%s-%d", genericKafkaListener.getName().toUpperCase(Locale.ROOT), genericKafkaListener.getPort()); // Note - kafka itself capitalises the listener name.
            Tag brokerTag = Tag.of(TAG_LABEL_BROKER_ID, String.valueOf(ordinal));
            Tag listenerTag = Tag.of(TAG_LABEL_LISTENER, listenerName);
            Tags listenerTags = tags.and(brokerTag, listenerTag);

            orphanMeters.removeIf(m -> m.getId().getTags().contains(brokerTag));

            meterRegistry.gauge(KAFKA_INSTANCE_CONNECTION_LIMIT, listenerTags, ref,
                    r -> getExternalListener(r).map(GenericKafkaListener::getConfiguration).map(GenericKafkaListenerConfiguration::getMaxConnections).map(Integer::doubleValue).orElse(Double.NaN));
            meterRegistry.gauge(KAFKA_INSTANCE_CONNECTION_CREATION_RATE_LIMIT, listenerTags, ref,
                    r -> getExternalListener(r).map(GenericKafkaListener::getConfiguration).map(GenericKafkaListenerConfiguration::getMaxConnectionCreationRate).map(Integer::doubleValue).orElse(Double.NaN));
        }));

        orphanMeters.forEach(meterRegistry::remove);
    }

    public static Tags buildKafkaInstanceTags(HasMetadata obj) {
        ObjectMeta metadata = obj.getMetadata();
        String profileType = OperandUtils.getOrDefault(metadata.getLabels(), ManagedKafka.PROFILE_TYPE, "standard");
        return Tags.of(Tag.of(TAG_LABEL_NAMESPACE, metadata.getNamespace()), Tag.of(TAG_LABEL_INSTANCE_NAME, metadata.getName()), Tag.of(TAG_LABEL_INSTANCE_PROFILE_TYPE, profileType), OWNER);
    }

    private Double replicas(AtomicReference<Kafka> r) {
        return getKafkaClusterSpec(r)
                .map(KafkaClusterSpec::getReplicas)
                .map(Number::doubleValue)
                .orElse(Double.NaN);
    }

    private Double kafkaConfigValue(AtomicReference<Kafka> r, String configKey) {
        Class<Number> number = Number.class;
        return getKafkaConfig(r).map(m -> m.get(configKey)).filter(number::isInstance).map(number::cast).map(Number::doubleValue).orElse(Double.NaN);
    }

    private Optional<Map<String, Object>> getKafkaConfig(AtomicReference<Kafka> k) {
        return getKafkaClusterSpec(k)
                .map(KafkaClusterSpec::getConfig);
    }

    private Optional<GenericKafkaListener> getExternalListener(AtomicReference<Kafka> r) {
        return getKafkaClusterSpec(r)
                .map(KafkaClusterSpec::getListeners)
                .flatMap(ll -> ll.stream().filter(l -> AbstractKafkaCluster.EXTERNAL_LISTENER_NAME.equals(l.getName())).findFirst());
    }

    private Optional<KafkaClusterSpec> getKafkaClusterSpec(AtomicReference<Kafka> k) {
        return Optional.ofNullable(k.get())
                .map(Kafka::getSpec)
                .map(KafkaSpec::getKafka);
    }

    private Double getQuotaConsumed(AtomicReference<Kafka> k) {
        return Optional.ofNullable(k.get())
                .map(Kafka::getMetadata)
                .map(ObjectMeta::getLabels)
                .map(m -> m.get(ManagedKafka.PROFILE_QUOTA_CONSUMED))
                .map(Double::parseDouble).orElse(Double.NaN);
    }

    private Double suspended(AtomicReference<ManagedKafka> ref) {
        return Optional.ofNullable(ref.get())
                .map(ManagedKafka::isSuspended)
                .filter(Boolean.TRUE::equals)
                .map(suspended -> 1d)
                .orElse(0d);
    }

    private Double paused(AtomicReference<ManagedKafka> ref) {
        return Optional.ofNullable(ref.get())
                .map(mk -> mk.getAnnotation(ManagedKafkaKeys.Annotations.PAUSE_RECONCILIATION))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(Boolean::valueOf)
                .filter(Boolean.TRUE::equals)
                .map(paused -> 1d)
                .orElse(0d);
    }
}

