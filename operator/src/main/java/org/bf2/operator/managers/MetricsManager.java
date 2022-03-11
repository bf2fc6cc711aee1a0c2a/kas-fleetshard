package org.bf2.operator.managers;

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
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.HashSet;
import java.util.List;
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

    static final String TAG_LABEL_OWNER = "owner";
    static final String TAG_LABEL_BROKER_ID = "broker_id";
    static final String TAG_LABEL_NAMESPACE = "namespace";
    static final String TAG_LABEL_INSTANCE_NAME = "instance_name";

    static final Tag OWNER = Tag.of(TAG_LABEL_OWNER, "KafkaInstanceMetricsManager");
    static final String TAG_LABEL_LISTENER = "listener";

    @Inject
    InformerManager informerManager;

    @Inject
    MeterRegistry meterRegistry;

    private final Map<String, AtomicReference<Kafka>> kafkaMap = new ConcurrentHashMap<>();

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
        Search.in(meterRegistry).tags(buildKafkaInstanceTags(obj)).meters().forEach(meterRegistry::remove);
        kafkaMap.remove(Cache.metaNamespaceKeyFunc(obj));
    }

    private void createOrUpdateMetrics(Kafka kafka) {
        AtomicReference<Kafka> ref = kafkaMap.computeIfAbsent(Cache.metaNamespaceKeyFunc(kafka), (k) -> new AtomicReference<>(kafka));
        // Note that meterRegistry.gauge() is a no-op if the gauge already exists.  To handle the update case we
        // update the existing atomic reference that is already registered with the gauge to point at the new kafka object.
        ref.set(kafka);

        Tags tags = buildKafkaInstanceTags(kafka);

        meterRegistry.gauge(KAFKA_INSTANCE_SPEC_BROKERS_DESIRED_COUNT, tags, ref, this::replicas);
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

    private Tags buildKafkaInstanceTags(Kafka obj) {
        ObjectMeta metadata = obj.getMetadata();
        return Tags.of(Tag.of(TAG_LABEL_NAMESPACE, metadata.getNamespace()), Tag.of(TAG_LABEL_INSTANCE_NAME, metadata.getName()), OWNER);
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

}

