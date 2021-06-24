package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.TopologySelectorLabelRequirement;
import io.fabric8.kubernetes.api.model.TopologySelectorTerm;
import io.fabric8.kubernetes.api.model.storage.StorageClass;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClassList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Startup
@ApplicationScoped
public class StorageClassManager {

    private static final int EXPECTED_NUM_ZONES = 3;

    private static final String TOPOLOGY_KEY = "topology.kubernetes.io/zone";

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    ResourceInformer<Node> nodeInformer;
    ResourceInformer<StorageClass> storageClassInformer;

    private volatile List<String> storageClassNames;

    public List<String> getStorageClassNames() {
        return storageClassNames;
    }

    @PostConstruct
    protected void onStart() {
        MixedOperation<StorageClass, StorageClassList, Resource<StorageClass>> storageClasses =
                kubernetesClient.storage().storageClasses();

        nodeInformer = ResourceInformer.start(Node.class, kubernetesClient.nodes().withLabel("node-role.kubernetes.io/worker"), new ResourceEventHandler<Node>() {
            @Override public void onAdd(Node obj) {/* do nothing */}
            @Override public void onUpdate(Node oldObj, Node newObj) {/* do nothing */}
            @Override public void onDelete(Node obj, boolean deletedFinalStateUnknown) {/* do nothing */}
        });

        storageClassInformer = ResourceInformer.start(StorageClass.class, storageClasses, new ResourceEventHandler<StorageClass>() {

            @Override
            public void onAdd(StorageClass obj) {
                reconcileStorageClasses();
            }

            @Override
            public void onUpdate(StorageClass oldObj, StorageClass newObj) {
                reconcileStorageClasses();
            }

            @Override
            public void onDelete(StorageClass obj, boolean deletedFinalStateUnknown) {
                reconcileStorageClasses();
            }
        });

        reconcileStorageClasses();
    }

    @Scheduled(every = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileStorageClasses() {
        if (null == nodeInformer || !nodeInformer.isReady() || null == storageClassInformer || !storageClassInformer.isReady()) {
            log.warn("Informers not yet initialized or ready");
            return;
        }

        List<String> zones = nodeInformer.getList().stream()
                .filter(node -> node != null && node.getMetadata().getLabels() != null)
                .map(node -> node.getMetadata().getLabels().get(TOPOLOGY_KEY))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, StorageClass> zoneToStorageClass = zones.stream()
                .collect(Collectors.toMap(Function.identity(),
                        z -> storageClassInformer.getByKey(Cache.namespaceKeyFunc(null, "kas-" + z))));

        List<StorageClass> storageClasses = storageClassesFrom(zoneToStorageClass);

        storageClasses.stream().forEach(storageClass -> OperandUtils.createOrUpdate(kubernetesClient.storage().storageClasses(), storageClass));

        if (storageClasses.size() == EXPECTED_NUM_ZONES) {
            storageClassNames = storageClasses.stream().map(sc -> sc.getMetadata().getName()).sorted().collect(Collectors.toList());
        } else if (storageClasses.isEmpty()) {
            String defaultStorageClass = storageClassInformer.getList().stream()
                    .filter(sc -> sc.getMetadata().getAnnotations() != null && "true".equals(sc.getMetadata().getAnnotations().get("storageclass.kubernetes.io/is-default-class")))
                    .map(sc -> sc.getMetadata().getName())
                    .findFirst().orElse("");

            log.info("No AZs were discovered from node metadata, so the default storage class will be used instead: " + defaultStorageClass);
            storageClassNames = List.of(defaultStorageClass, defaultStorageClass, defaultStorageClass);
        } else {
            throw new RuntimeException(String.format("Wrong number of per-AZ StorageClasses found: %d", storageClasses.size()));
        }
    }

    private List<StorageClass> storageClassesFrom(Map<String, StorageClass> storageClasses) {
        return storageClasses.entrySet().stream().map(e -> {
            StorageClassBuilder builder = e.getValue() != null ? new StorageClassBuilder(e.getValue()) : new StorageClassBuilder();
            return builder
                    .editOrNewMetadata()
                        .withName("kas-" + e.getKey())
                        .withLabels(OperandUtils.getDefaultLabels())
                    .endMetadata()
                    .withProvisioner("kubernetes.io/aws-ebs")
                    .withReclaimPolicy("Delete")
                    .withVolumeBindingMode("WaitForFirstConsumer")
                    .withAllowVolumeExpansion(true)
                    .withParameters(getStorageClassParameters())
                    .withAllowedTopologies(getTopologySelectorTerm(e.getKey()))
                    .build();
        }).collect(Collectors.toList());
    }

    private Map<String, String> getStorageClassParameters() {
        return Map.of(
                "type", "gp2",
                "encrypted", "true"
                );
    }

    private TopologySelectorTerm getTopologySelectorTerm(String zone) {
        TopologySelectorLabelRequirement requirement = new TopologySelectorLabelRequirement(TOPOLOGY_KEY, Collections.singletonList(zone));
        List<TopologySelectorLabelRequirement> requirements = Collections.singletonList(requirement);
        return new TopologySelectorTerm(requirements);
    }


}
