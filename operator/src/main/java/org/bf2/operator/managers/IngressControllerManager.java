package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.kubernetes.client.utils.CachedSingleThreadScheduler;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.operator.v1.ConfigBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerList;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerTuningOptions;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListener;
import io.strimzi.api.kafka.model.listener.arraylistener.GenericKafkaListenerConfiguration;
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.ManagedKafkaKeys;
import org.bf2.operator.ManagedKafkaKeys.Labels;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentSpec;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.bf2.operator.resources.v1alpha1.NetworkConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Controls the resources and number of ingress replicas used by a managed kafka
 * <br>
 * This uses values from the actual Kafkas to determine ingress demand.
 * <br>
 * It will not reclaim excess replicas until there is a reduction in the number of nodes.
 */
@Startup
@ApplicationScoped
//excluding during smoke tests (when kafka=dev is set) running on Kubernetes
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class IngressControllerManager {

    private static final String MAX_CONNECTIONS = "maxConnections";
    private static final String RELOAD_INTERVAL = "reloadInterval";
    private static final String DYNAMIC_CONFIG_MANAGER = "dynamicConfigManager";
    private static final String UNSUPPORTED_CONFIG_OVERRIDES = "unsupportedConfigOverrides";
    private static final String TUNING_OPTIONS = "tuningOptions";
    protected static final String INGRESSCONTROLLER_LABEL = "ingresscontroller.operator.openshift.io/owning-ingresscontroller";
    protected static final String HARD_STOP_AFTER_ANNOTATION = "ingress.operator.openshift.io/hard-stop-after";
    protected static final String MEMORY = "memory";
    protected static final String CPU = "cpu";

    /**
     * The node label identifying the AZ in which the node resides
     */
    public static final String TOPOLOGY_KEY = "topology.kubernetes.io/zone";

    protected static final String INGRESS_OPERATOR_NAMESPACE = "openshift-ingress-operator";

    protected static final String INGRESS_ROUTER_NAMESPACE = "openshift-ingress";

    protected static final String INFRA_NODE_LABEL = "node-role.kubernetes.io/infra";

    protected static final String WORKER_NODE_LABEL = "node-role.kubernetes.io/worker";

    /**
     * Domain part prefixed to domain reported on IngressController status. The CNAME DNS records
     * need to point to a sub-domain on the IngressController domain, so we just add this.
     */
    private static final String ROUTER_SUBDOMAIN = "ingresscontroller.";

    /**
     * Predicate that will return true if the input string looks like a broker resource name.
     */
    protected static final Predicate<String> IS_BROKER = Pattern.compile(".+-kafka-\\d+$").asMatchPredicate();
    private static final Predicate<Route> IS_BROKER_ROUTE = r -> IS_BROKER.test(r.getMetadata().getName());

    @Inject
    Logger log;

    @Inject
    OpenShiftClient openShiftClient;

    @Inject
    InformerManager informerManager;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    @Inject
    OperandOverrideManager overrideManager;

    private Map<String, String> routeMatchLabels = new ConcurrentHashMap<>();

    ResourceInformer<Pod> brokerPodInformer;
    ResourceInformer<Node> nodeInformer;
    ResourceInformer<IngressController> ingressControllerInformer;
    private boolean ready;

    @ConfigProperty(name = "ingresscontroller.limit-cpu")
    Optional<Quantity> limitCpu;
    @ConfigProperty(name = "ingresscontroller.limit-memory")
    Optional<Quantity> limitMemory;
    @ConfigProperty(name = "ingresscontroller.request-cpu")
    Optional<Quantity> requestCpu;
    @ConfigProperty(name = "ingresscontroller.request-memory")
    Optional<Quantity> requestMemory;

    @ConfigProperty(name = "ingresscontroller.default-replica-count")
    Optional<Integer> defaultReplicaCount;
    @ConfigProperty(name = "ingresscontroller.az-replica-count")
    Optional<Integer> azReplicaCount;

    @ConfigProperty(name = "ingresscontroller.max-ingress-throughput")
    Quantity maxIngressThroughput;
    @ConfigProperty(name = "ingresscontroller.max-ingress-connections")
    int maxIngressConnections;
    @ConfigProperty(name = "ingresscontroller.hard-stop-after")
    String hardStopAfter;
    @ConfigProperty(name = "ingresscontroller.ingress-container-command")
    List<String> ingressContainerCommand;
    @ConfigProperty(name = "ingresscontroller.reload-interval-seconds")
    Integer ingressReloadIntervalSeconds;

    @ConfigProperty(name = "ingresscontroller.dynamic-config-manager")
    Boolean dynamicConfigManager;

    @ConfigProperty(name = "ingresscontroller.peak-throughput-percentage")
    int peakThroughputPercentage;
    @ConfigProperty(name = "ingresscontroller.peak-connection-percentage")
    int peakConnectionPercentage;

    private ResourceInformer<Deployment> deployments;
    private CachedSingleThreadScheduler scheduler = new CachedSingleThreadScheduler();
    private Set<String> deploymentsToReconcile = new HashSet<>();
    private ResourceRequirements azDeploymentResourceRequirements;
    private ResourceRequirements defaultDeploymentResourceRequirements;

    public Map<String, String> getRouteMatchLabels() {
        return routeMatchLabels;
    }

    public void addToRouteMatchLabels(String key, String value) {
        routeMatchLabels.put(key, value);
    }

    public List<ManagedKafkaRoute> getManagedKafkaRoutesFor(ManagedKafka mk) {
        String multiZoneRoute = getIngressControllerDomain("kas");
        String bootstrapDomain = mk.getSpec().getEndpoint().getBootstrapServerHost();

        return Stream.concat(
                Stream.of(
                        new ManagedKafkaRoute("bootstrap", "", multiZoneRoute),
                        new ManagedKafkaRoute("admin-server", "admin-server", multiZoneRoute)),
                routesFor(mk)
                    .filter(IS_BROKER_ROUTE)
                    .map(r -> {
                        String router = getIngressControllerDomain("kas-" + getZoneForBrokerRoute(r));
                        String routePrefix = r.getSpec().getHost().replaceFirst("-" + bootstrapDomain, "");

                        return new ManagedKafkaRoute(routePrefix, routePrefix, router);
                    }))
                .sorted(Comparator.comparing(ManagedKafkaRoute::getName))
                .collect(Collectors.toList());
    }

    public String getClusterDomain() {
        return ingressControllerInformer.getList()
                .stream()
                .filter(ic -> "default".equals(ic.getMetadata().getName()))
                .map(ic -> ic.getStatus().getDomain())
                .findFirst()
                .orElse("apps.testing.domain.tld")
                .replaceFirst("apps.", "");
    }

    @PostConstruct
    protected void onStart() {
        NonNamespaceOperation<IngressController, IngressControllerList, Resource<IngressController>> ingressControllers =
                openShiftClient.operator().ingressControllers().inNamespace(INGRESS_OPERATOR_NAMESPACE);

        final FilterWatchListDeletable<Node, NodeList> workerNodeFilter = openShiftClient.nodes()
                .withLabel(WORKER_NODE_LABEL)
                .withoutLabel(INFRA_NODE_LABEL);

        nodeInformer = resourceInformerFactory.create(Node.class, workerNodeFilter, new ResourceEventHandler<HasMetadata>() {

            @Override
            public void onAdd(HasMetadata obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(HasMetadata oldObj, HasMetadata newObj) {
            }

            @Override
            public void onDelete(HasMetadata obj, boolean deletedFinalStateUnknown) {
                reconcileIngressControllers();
            }
        });

        FilterWatchListDeletable<Pod, PodList> brokerPodFilter = openShiftClient.pods().inAnyNamespace().withLabels(Map.of(
                OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME,
                OperandUtils.K8S_NAME_LABEL, "kafka"));

        brokerPodInformer = resourceInformerFactory.create(Pod.class, brokerPodFilter, new ResourceEventHandler<HasMetadata>() {

            @Override
            public void onAdd(HasMetadata obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(HasMetadata oldObj, HasMetadata newObj) {
            }

            @Override
            public void onDelete(HasMetadata obj, boolean deletedFinalStateUnknown) {
            }
        });

        ingressControllerInformer = resourceInformerFactory.create(IngressController.class, ingressControllers, new ResourceEventHandler<IngressController>() {

            @Override
            public void onAdd(IngressController obj) {
                reconcileIngressControllers();
            }

            @Override
            public void onUpdate(IngressController oldObj, IngressController newObj) {
                reconcileIngressControllers();
            }

            @Override
            public void onDelete(IngressController obj, boolean deletedFinalStateUnknown) {
                reconcileIngressControllers();
            }
        });

        ResourceRequirementsBuilder deploymentResourceBuilder = new ResourceRequirementsBuilder();
        limitCpu.ifPresent(quantity -> deploymentResourceBuilder.addToLimits(CPU, quantity));
        limitMemory.ifPresent(quantity -> deploymentResourceBuilder.addToLimits(MEMORY, quantity));
        requestCpu.ifPresent(quantity -> deploymentResourceBuilder.addToRequests(CPU, quantity));
        requestMemory.ifPresent(quantity -> deploymentResourceBuilder.addToRequests(MEMORY, quantity));

        // this is to patch the IngressController Router deployments to correctly size for resources, should be removed
        // after https://issues.redhat.com/browse/RFE-1475 is resolved.
        if (deploymentResourceBuilder.hasLimits() || deploymentResourceBuilder.hasRequests()) {
            this.azDeploymentResourceRequirements = deploymentResourceBuilder.build();

            // use the default cpu for the kas/default ic
            this.defaultDeploymentResourceRequirements = new ResourceRequirementsBuilder(azDeploymentResourceRequirements)
                    .addToRequests(CPU, Quantity.parse("100m"))
                    .build();

            deployments = this.resourceInformerFactory.create(Deployment.class,
                    this.openShiftClient.apps().deployments().inNamespace(INGRESS_ROUTER_NAMESPACE).withLabel(INGRESSCONTROLLER_LABEL),
                    new ResourceEventHandler<Deployment>() {
                        @Override
                        public void onAdd(Deployment deployment) {
                            patchIngressDeploymentResources(deployment);
                        }
                        @Override
                        public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
                            patchIngressDeploymentResources(newDeployment);
                        }
                        @Override
                        public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
                            // do nothing
                        }
            });
        }

        ready = true;
        reconcileIngressControllers();
    }

    private void patchIngressDeploymentResources(Deployment d) {
        if (!shouldReconcile(d)) {
            return;
        }

        synchronized (deploymentsToReconcile) {
            boolean needsReconcile = deploymentsToReconcile.isEmpty();
            deploymentsToReconcile.add(Cache.metaNamespaceKeyFunc(d));

            if (needsReconcile) {
                // delay the reconcile as we see clustered events
                scheduler.schedule(() -> {
                    Set<String> toReconcile;
                    synchronized (deploymentsToReconcile) {
                        toReconcile = new HashSet<>(deploymentsToReconcile);
                        deploymentsToReconcile.clear();
                    }
                    toReconcile.stream()
                            .map(deployments::getByKey)
                            .filter(Objects::nonNull)
                            .filter(this::shouldReconcile)
                            .forEach(this::doIngressPatch);
                }, 2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * check first to see if an update is needed
     */
    protected boolean shouldReconcile(Deployment d) {
        if (!OperandUtils.getOrDefault(d.getMetadata().getLabels(), INGRESSCONTROLLER_LABEL, "").startsWith("kas")) {
            return false;
        }
        List<Container> containers = d.getSpec().getTemplate().getSpec().getContainers();
        if (containers.size() != 1) {
            log.errorf("Wrong number of containers for Deployment %s/%s", d.getMetadata().getNamespace(), d.getMetadata().getName());
            return false;
        }
        Container ingressContainer = containers.get(0);
        return !((isDefaultDeployment(d) ? defaultDeploymentResourceRequirements
                : azDeploymentResourceRequirements).equals(ingressContainer.getResources())
                && Objects.equals(ingressContainer.getCommand(), ingressContainerCommand));
    }

    private void doIngressPatch(Deployment d) {
        log.infof("Updating the resource limits/container command for Deployment %s/%s", d.getMetadata().getNamespace(), d.getMetadata().getName());
        openShiftClient.apps().deployments().inNamespace(d.getMetadata().getNamespace())
            .withName(d.getMetadata().getName()).edit(
                new TypedVisitor<ContainerBuilder>() {
                    @Override
                    public void visit(ContainerBuilder element) {
                        if (!isDefaultDeployment(d)) {
                            element.withResources(azDeploymentResourceRequirements);
                        } else {
                            element.withResources(defaultDeploymentResourceRequirements);
                        }
                        element.withCommand(ingressContainerCommand);
                    }
                });
    }

    private boolean isDefaultDeployment(Deployment d) {
        return d.getMetadata().getName().equals("router-kas");
    }

    @Scheduled(every = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileIngressControllers() {
        if (!ready) {
            log.warn("One or more informers are not yet ready");
            return;
        }

        String defaultDomain = getClusterDomain();

        List<String> zones = nodeInformer.getList().stream()
                .filter(node -> node != null && node.getMetadata().getLabels() != null)
                .map(node -> node.getMetadata().getLabels().get(TOPOLOGY_KEY))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, IngressController> zoneToIngressController = new HashMap<>();
        zones.stream().forEach(z -> zoneToIngressController.put(z, ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas-" + z))));

        // someday we might share access to the operator cache
        List<Kafka> kafkas = informerManager.getKafkas();

        long connectionDemand = kafkas.stream()
                .map(m -> m.getSpec().getKafka())
                .map(s -> s.getListeners()
                        .stream()
                        .filter(l -> AbstractKafkaCluster.EXTERNAL_LISTENER_NAME.equals(l.getName()))
                        .map(GenericKafkaListener::getConfiguration)
                        .map(GenericKafkaListenerConfiguration::getMaxConnections)
                        .filter(Objects::nonNull)
                        .map(c -> c * s.getReplicas())
                        .findFirst())
                .mapToLong(o -> o.orElse(0))
                .sum();

        ingressControllersFrom(zoneToIngressController, defaultDomain, kafkas, connectionDemand);

        buildDefaultIngressController(zones, defaultDomain, connectionDemand);

        if (deployments != null) {
            deployments.getList().stream().filter(this::shouldReconcile).forEach(this::doIngressPatch);
        }
    }

    private void createOrEdit(IngressController expected, IngressController existing) {
        String name = expected.getMetadata().getName();

        if (existing != null) {
            openShiftClient.operator().ingressControllers()
            .inNamespace(expected.getMetadata().getNamespace())
            .withName(name)
            .edit(i -> new IngressControllerBuilder(i)
                    .editMetadata()
                    .withLabels(expected.getMetadata().getLabels())
                    .withAnnotations(expected.getMetadata().getAnnotations())
                    .endMetadata()
                    .withSpec(expected.getSpec())
                    .build());
        } else {
            OperandUtils.createOrUpdate(openShiftClient.operator().ingressControllers(), expected);
        }
    }

    private void ingressControllersFrom(Map<String, IngressController> ingressControllers, String clusterDomain, List<Kafka> kafkas, long connectionDemand) {
        LongSummaryStatistics egress = summarize(kafkas, KafkaCluster::getFetchQuota, () -> {throw new IllegalStateException("A kafka lacks a fetch quota");});
        LongSummaryStatistics ingress = summarize(kafkas, KafkaCluster::getProduceQuota, () -> {throw new IllegalStateException("A kafka lacks a produce quota");});

        // there is an assumption that the nodes / brokers will be balanced by zone
        double zonePercentage = 1d / ingressControllers.size();
        int replicas = numReplicasForZone(ingress, egress, connectionDemand, zonePercentage);
        ingressControllers.entrySet().stream().forEach(e -> {
            String zone = e.getKey();
            String kasZone = "kas-" + zone;
            String domain = kasZone + "." + clusterDomain;
            Map<String, String> routeMatchLabel = Map.of(ManagedKafkaKeys.forKey(kasZone), "true");
            LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
            routeMatchLabels.putAll(routeMatchLabel);

            createOrEditIngressController(kasZone, domain, e.getValue(), replicas, routeSelector, zone);
        });
    }

    private void buildDefaultIngressController(List<String> zones, String clusterDomain, long connectionDemand) {
        IngressController existing = ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas"));

        int replicas = numReplicasForDefault(connectionDemand);

        final Map<String, String> routeMatchLabel = Map.of(Labels.KAS_MULTI_ZONE, "true");
        LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
        routeMatchLabels.putAll(routeMatchLabel);

        createOrEditIngressController("kas", "kas." + clusterDomain, existing, replicas, routeSelector, null);
    }

    private void createOrEditIngressController(String name, String domain,
            IngressController existing, int replicas, LabelSelector routeSelector, String topologyValue) {
        createOrEdit(buildIngressController(name, domain, existing, replicas, routeSelector, topologyValue, informerManager.getLocalAgent()), existing);
    }

    IngressController buildIngressController(String name, String domain,
            IngressController existing, int replicas, LabelSelector routeSelector, String topologyValue, ManagedKafkaAgent agent) {

        Optional<IngressController> optionalExisting = Optional.ofNullable(existing);
        IngressControllerBuilder builder = optionalExisting.map(IngressControllerBuilder::new).orElseGet(IngressControllerBuilder::new);

        builder
            .editOrNewMetadata()
                .withName(name)
                .withNamespace(INGRESS_OPERATOR_NAMESPACE)
                .withLabels(OperandUtils.getDefaultLabels())
            .endMetadata()
            .editOrNewSpec()
                .withDomain(domain)
                .withRouteSelector(routeSelector)
                .withReplicas(replicas)
                .withNewEndpointPublishingStrategy()
                     .withType("LoadBalancerService")
                     .withNewLoadBalancer()
                         .withScope(Optional.ofNullable(agent)
                             .map(ManagedKafkaAgent::getSpec)
                             .map(ManagedKafkaAgentSpec::getNet)
                             .filter(NetworkConfiguration::isPrivate)
                             .map(a -> "Internal")
                             .orElse("External"))
                         .withNewProviderParameters()
                             .withType("AWS")
                             .withNewAws()
                                 .withType("NLB")
                             .endAws()
                         .endProviderParameters()
                     .endLoadBalancer()
                .endEndpointPublishingStrategy()
            .endSpec();

        if (topologyValue != null && !topologyValue.isEmpty()) {
            builder
                .editSpec()
                    .withNewNodePlacement()
                        .editOrNewNodeSelector()
                            .addToMatchLabels(TOPOLOGY_KEY, topologyValue)
                            .addToMatchLabels(WORKER_NODE_LABEL, "")
                        .endNodeSelector()
                    .endNodePlacement()
                .endSpec();
        }

        // if configured for profiles, move the ingress replicas to the default machine pool by adding a dummy toleration
        if (OperandUtils.shouldProfileLabelsExist(informerManager.getLocalAgent())
                && overrideManager.migratedToDynamicScalingScheduling()) {
            builder
                .editSpec()
                    .editOrNewNodePlacement()
                    .withTolerations(new TolerationBuilder().withEffect("NoSchedule")
                            .withKey("kas-fleetshard-ingress")
                            .withOperator("Exists")
                            .build())
                    .endNodePlacement()
                .endSpec();
        }

        if (hardStopAfter != null && !hardStopAfter.isBlank()) {
            builder.editMetadata().addToAnnotations(HARD_STOP_AFTER_ANNOTATION, hardStopAfter).endMetadata();
        } else {
            builder.editMetadata().removeFromAnnotations(HARD_STOP_AFTER_ANNOTATION).endMetadata();
        }

        // editing properties that may not exist and unsupported is easier as generic
        GenericKubernetesResource spec = Serialization.jsonMapper().convertValue(builder.buildSpec(), GenericKubernetesResource.class);

        if (ingressReloadIntervalSeconds > 0) {
            setSpecProperty(spec, TUNING_OPTIONS, RELOAD_INTERVAL, ingressReloadIntervalSeconds);
            setSpecProperty(spec, UNSUPPORTED_CONFIG_OVERRIDES, RELOAD_INTERVAL, ingressReloadIntervalSeconds);
        } else {
            removeSpecProperty(spec, RELOAD_INTERVAL);
        }
        setSpecProperty(spec, UNSUPPORTED_CONFIG_OVERRIDES, DYNAMIC_CONFIG_MANAGER, dynamicConfigManager != null ? dynamicConfigManager.toString() : Boolean.FALSE.toString());
        setSpecProperty(spec, TUNING_OPTIONS, MAX_CONNECTIONS, maxIngressConnections);
        setSpecProperty(spec, UNSUPPORTED_CONFIG_OVERRIDES, MAX_CONNECTIONS, maxIngressConnections);

        // on fabric8 6.1 we can convert back from the generic to the actual spec, on earlier versions we cannot
        // because the unsupportedOptions won't be preserved
        builder.editSpec()
                .withTuningOptions(Serialization.jsonMapper()
                        .convertValue(spec.get(TUNING_OPTIONS), IngressControllerTuningOptions.class))
                .withUnsupportedConfigOverrides(
                        Optional.ofNullable((Map<String, Object>) spec.get(UNSUPPORTED_CONFIG_OVERRIDES))
                                .map(m -> new ConfigBuilder()
                                        .withAdditionalProperties(m)
                                        .build())
                                .orElse(null))
                .endSpec();

        return builder.build();
    }

    private void setSpecProperty(GenericKubernetesResource spec, String property, String key, Object value) {
        Optional.ofNullable((Map<String, Object>)spec.get(property)).orElseGet(() -> {
            Map<String, Object> map = new LinkedHashMap<>();
            spec.setAdditionalProperty(property, map);
            return map;
        }).put(key, value);
    }

    private void removeSpecProperty(GenericKubernetesResource spec, String key) {
        Optional.ofNullable((Map)spec.get(TUNING_OPTIONS)).ifPresent(m -> m.remove(key));
        Optional.ofNullable((Map)spec.get(UNSUPPORTED_CONFIG_OVERRIDES)).ifPresent(m -> m.remove(key));
    }

    // for testing
    void setAzReplicaCount(Optional<Integer> value) {
        azReplicaCount = value;
    }

    int numReplicasForZone(LongSummaryStatistics ingress, LongSummaryStatistics egress,
            long connectionDemand, double zonePercentage) {
        // use the override if present
        int minimumReplicaCount = nodeInformer.getList().size() > 0 ? 1:0;
        if (azReplicaCount.isPresent()) {
            return azReplicaCount.get();
        }

        long throughput = (egress.getMax() + ingress.getMax())/2;
        long replicationThroughput = ingress.getMax()*2;

        // subtract out that we could share the node with a broker + the 1Mi is padding to account for the bandwidth of other colocated pods
        // we assume a worst case that 1/2 of the traffic to this broker may come from another replicas
        long throughputPerIngressReplica = Quantity.getAmountInBytes(maxIngressThroughput).longValue()
                - Quantity.getAmountInBytes(Quantity.parse("1Mi")).longValue();
        if (!OperandUtils.shouldProfileLabelsExist(informerManager.getLocalAgent()) || !overrideManager.migratedToDynamicScalingScheduling()) {
           // subtract out that we could share the node with a broker
           // we assume a worst case that 1/2 of the traffic to this broker may come from another replicas
          throughputPerIngressReplica -= (throughput / 2 + replicationThroughput);
          if (throughputPerIngressReplica < 0) {
              throw new AssertionError("Cannot appropriately scale ingress as collocating with a broker takes more than the available node bandwidth");
          }
        }

        // average of total ingress/egress in this zone
        double throughputDemanded = (egress.getSum() + ingress.getSum()) * zonePercentage / 2;

        // scale back with the assumption that we don't really need to meet the peak
        throughputDemanded *= peakThroughputPercentage / 100D;

        int replicaCount = (int)Math.ceil(throughputDemanded / throughputPerIngressReplica);
        int connectionReplicaCount = numReplicasForConnectionDemand((long) (connectionDemand * zonePercentage));

        return Math.max(minimumReplicaCount, Math.max(connectionReplicaCount, replicaCount));
    }

    static LongSummaryStatistics summarize(List<Kafka> kafkas, Function<Kafka, String> quantity,
            Supplier<String> defaultValue) {
        return kafkas.stream()
                .flatMap(m -> {
                    KafkaClusterSpec s = m.getSpec().getKafka();
                    String value = Optional.of(quantity.apply(m)).orElseGet(defaultValue);
                    return Collections.nCopies(s.getReplicas(), Quantity.getAmountInBytes(Quantity.parse(value))).stream();
                })
                .mapToLong(BigDecimal::longValue)
                .summaryStatistics();
    }

    int numReplicasForDefault(long connectionDemand) {
        // use the override if present
        int minimumReplicaCount = nodeInformer.getList().size() > 0 ? 1:0;
        if (defaultReplicaCount.isPresent()) {
            return defaultReplicaCount.get();
        } else if (nodeInformer.getList().size() > 3){
            // enforce a minimum of two replicas on clusters that can accommodate it when no default specified
            minimumReplicaCount = 2;
        }

        /*
         * an assumption here is that these ingress replicas will not become bandwidth constrained - but that may need further qualification
         */
        return Math.max(minimumReplicaCount, numReplicasForConnectionDemand(connectionDemand));
    }

    private int numReplicasForConnectionDemand(double connectionDemand) {
        return (int)Math.ceil(connectionDemand * (peakConnectionPercentage / 100D) / maxIngressConnections);
    }

    private String getIngressControllerDomain(String ingressControllerName) {
        return ingressControllerInformer.getList().stream()
                .filter(ic -> ic.getMetadata().getName().equals(ingressControllerName))
                .map(ic -> ROUTER_SUBDOMAIN + (ic.getStatus() != null ? ic.getStatus().getDomain() : ic.getSpec().getDomain()))
                .findFirst()
                .orElse("");
    }

    private Stream<Route> routesFor(ManagedKafka managedKafka) {
        return informerManager.getRoutesInNamespace(managedKafka.getMetadata().getNamespace())
                .filter(route -> isOwnedBy(route, Kafka.RESOURCE_KIND, AbstractKafkaCluster.kafkaClusterName(managedKafka), AbstractKafkaCluster.kafkaClusterNamespace(managedKafka))
                        || isOwnedBy(route, managedKafka.getKind(), managedKafka.getMetadata().getName(), managedKafka.getMetadata().getNamespace()));
    }

    private String getZoneForBrokerRoute(Route route) {
        String serviceName = route.getSpec().getTo().getName();
        String namespace = route.getMetadata().getNamespace();
        Service svc = informerManager.getLocalService(namespace, serviceName);
        if (svc == null) {
            return "";
        }

        Map<String, String> labels = svc.getSpec().getSelector();
        Stream<Pod> pods = brokerPodInformer.getList().stream()
                .filter(p -> p.getMetadata().getNamespace().equals(namespace)
                        && p.getMetadata().getLabels().entrySet().containsAll(labels.entrySet()));

        return pods
                .findFirst()
                .map(p -> p.getSpec().getNodeName())
                .map(nodeInformer::getByKey)
                .map(n -> n.getMetadata().getLabels().get(IngressControllerManager.TOPOLOGY_KEY))
                .orElse("");
    }

    private boolean isOwnedBy(HasMetadata owned, String ownerKind, String ownerName, String ownerNamespace) {
        boolean sameNamespace = ownerNamespace.equals(owned.getMetadata().getNamespace());
        return sameNamespace &&
                owned.getMetadata().getOwnerReferences().stream()
                    .anyMatch(ref -> ref.getKind().equals(ownerKind) && ref.getName().equals(ownerName));
    }

    List<String> getIngressContainerCommand() {
        return ingressContainerCommand;
    }

    Optional<Quantity> getRequestMemory() {
        return requestMemory;
    }
}
