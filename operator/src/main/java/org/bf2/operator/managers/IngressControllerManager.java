package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.builder.TypedVisitor;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.cache.Cache;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.operator.v1.IngressController;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerBuilder;
import io.fabric8.openshift.api.model.operator.v1.IngressControllerList;
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
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.KafkaCluster;
import org.bf2.operator.operands.KafkaInstanceConfiguration;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Controls the resources and number of ingress replicas used by a managed kafka
 * <br>
 * An alternative strategy could be to look at the resource requests on the ManagedKafkas
 * - or the actual Kafkas, but that doesn't work well with performance testing that disables
 * or sets values artificially high.
 */
@Startup
@ApplicationScoped
//excluding during smoke tests (when kafka=dev is set) running on Kubernetes
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class IngressControllerManager {

    protected static final String INGRESSCONTROLLER_LABEL = "ingresscontroller.operator.openshift.io/owning-ingresscontroller";
    protected static final String MEMORY = "memory";
    protected static final String CPU = "cpu";

    // TODO: eventually this will need to be a parameter/calculated, for now it's based roughly on the baseline bandwidth vs the expected ingress/egress
    public static final int NODES_PER_REPLICA = 25;

    /**
     * The label key for the multi-AZ IngressController
     */
    public static final String KAS_MULTI_ZONE = "managedkafka.bf2.org/kas-multi-zone";

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

    private Map<String, String> routeMatchLabels = new ConcurrentHashMap<>();

    ResourceInformer<Pod> brokerPodInformer;
    ResourceInformer<Node> nodeInformer;
    ResourceInformer<IngressController> ingressControllerInformer;

    //TODO: may need to differentiate between default and per az resources

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
    Quantity maxIngressConnections;

    @Inject
    KafkaInstanceConfiguration config;

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

        // this is to patch the IngressController Router deployments to correctly size for resources, should be removed
        // after https://issues.redhat.com/browse/RFE-1475 is resolved.
        this.resourceInformerFactory.create(Deployment.class,
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

        resyncIngressControllerDeployments();
        reconcileIngressControllers();
    }

    private void patchIngressDeploymentResources(Deployment d) {
        if (OperandUtils.getOrDefault(d.getMetadata().getLabels(), INGRESSCONTROLLER_LABEL, "").startsWith("kas")) {

            ResourceRequirementsBuilder builder = new ResourceRequirementsBuilder();
            limitCpu.ifPresent(quantity -> builder.addToLimits(CPU, quantity));
            limitMemory.ifPresent(quantity -> builder.addToLimits(MEMORY, quantity));
            requestCpu.ifPresent(quantity -> builder.addToRequests(CPU, quantity));
            requestMemory.ifPresent(quantity -> builder.addToRequests(MEMORY, quantity));

            if (builder.hasLimits() || builder.hasRequests()) {
                log.infof("Updating the resource limits for Deployment %s/%s", d.getMetadata().getNamespace(), d.getMetadata().getName());
                openShiftClient.apps().deployments().inNamespace(d.getMetadata().getNamespace())
                    .withName(d.getMetadata().getName()).edit(
                        new TypedVisitor<ContainerBuilder>() {
                            @Override
                            public void visit(ContainerBuilder element) {
                                element.withResources(builder.build());
                            }
                        });
            }
        }
    }

    void resyncIngressControllerDeployments() {
        openShiftClient.apps().deployments().inNamespace(INGRESS_ROUTER_NAMESPACE).list().getItems().forEach(d -> {
            patchIngressDeploymentResources(d);
        });
    }

    @Scheduled(every = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileIngressControllers() {
        if (nodeInformer == null || ingressControllerInformer == null) {
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

        // TODO: we may want some dampening on scaling down ingress replicas - it seems like that can introduce reconnects
        List<IngressController> ingressControllers = ingressControllersFrom(zoneToIngressController, defaultDomain, kafkas, connectionDemand);

        ingressControllers.add(buildDefaultIngressController(zones, defaultDomain, connectionDemand));

        ingressControllers.stream().forEach(expected -> {
            String name = expected.getMetadata().getName();

            if (ingressControllerInformer.getList().stream().anyMatch(i -> name.equals(i.getMetadata().getName()))) {
                openShiftClient.operator().ingressControllers()
                    .inNamespace(expected.getMetadata().getNamespace())
                    .withName(name)
                    .edit(i -> new IngressControllerBuilder(i)
                            .editMetadata().withLabels(expected.getMetadata().getLabels()).endMetadata()
                            .withSpec(expected.getSpec())
                            .build());
            } else {
                OperandUtils.createOrUpdate(openShiftClient.operator().ingressControllers(), expected);
            }

        });
    }

    private List<IngressController> ingressControllersFrom(Map<String, IngressController> ingressControllers, String clusterDomain, List<Kafka> kafkas, long connectionDemand) {
        LongSummaryStatistics egress = summarize(kafkas, KafkaCluster::getFetchQuota, () -> config.getKafka().getEgressPerSec());
        LongSummaryStatistics ingress = summarize(kafkas, KafkaCluster::getProduceQuota, () -> config.getKafka().getIngressPerSec());

        return ingressControllers.entrySet().stream().map(e -> {
            String zone = e.getKey();
            String kasZone = "kas-" + zone;
            String domain = kasZone + "." + clusterDomain;
            int replicas = numReplicasForZone(zone, nodeInformer.getList(), ingress, egress, connectionDemand);

            Map<String, String> routeMatchLabel = Map.of("managedkafka.bf2.org/" + kasZone, "true");
            LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
            routeMatchLabels.putAll(routeMatchLabel);

            return buildIngressController(kasZone, domain, e.getValue(), replicas, routeSelector, zone);
        }).collect(Collectors.toList());
    }

    private IngressController buildDefaultIngressController(List<String> zones, String clusterDomain, long connectionDemand) {
        IngressController existing = ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas"));

        int replicas = numReplicasForAllZones(nodeInformer.getList(), connectionDemand);

        final Map<String, String> routeMatchLabel = Map.of(KAS_MULTI_ZONE, "true");
        LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
        routeMatchLabels.putAll(routeMatchLabel);

        return buildIngressController("kas", "kas." + clusterDomain, existing, replicas, routeSelector, null);
    }

    private IngressController buildIngressController(String name, String domain,
            IngressController existing, int replicas, LabelSelector routeSelector, String topologyValue) {

        IngressControllerBuilder builder = Optional.ofNullable(existing).map(IngressControllerBuilder::new).orElse(new IngressControllerBuilder());

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
                         .withScope("External")
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

        return builder.build();
    }

    int numReplicasForZone(String zone, List<Node> nodes, LongSummaryStatistics ingress, LongSummaryStatistics egress, long connectionDemand) {
        // use the override if present
        if (azReplicaCount.isPresent()) {
            return azReplicaCount.get();
        }

        int nodesInZone = Math.toIntExact(nodes.stream()
                .filter(node -> zone.equals(node.getMetadata().getLabels().get(TOPOLOGY_KEY)))
                .filter(Objects::nonNull)
                .count());

        double zonePercentage = nodesInZone / (double)nodes.size();

        long throughput = (egress.getMax() + ingress.getMax())/2;
        long replicationThroughput = ingress.getMax()*2;

        // subtract out that we could share the node with a broker + the 1Mi is padding to account for the bandwidth of other colocated pods
        // we assume a worst case that 1/2 of the traffic to this broker may come from another replicas
        long throughputPerIngressReplica = Quantity.getAmountInBytes(maxIngressThroughput).longValue()
                - replicationThroughput - throughput / 2 - Quantity.getAmountInBytes(Quantity.parse("1Mi")).longValue();

        // average of total ingress/egress in this zone
        double throughputDemanded = (egress.getSum() + ingress.getSum()) * zonePercentage / 2;

        int replicaCount = (int)Math.ceil(throughputDemanded / throughputPerIngressReplica);
        int connectionReplicaCount = numReplicasForConnectionDemand((long) (connectionDemand * zonePercentage));

        /*
         * we want at least 2 replicas, unless there are fewer nodes
         * each replica roughly has the responsibility for access to NODES_PER_REPLICA nodes
         * we'll therefore scale up after 2*NODES_PER_REPLICA nodes in the zone
         */
        return Math.max(Math.min(2, nodesInZone), Math.max(connectionReplicaCount, replicaCount));
    }

    private LongSummaryStatistics summarize(List<Kafka> managedKafkas, Function<Kafka, String> quantity,
            Supplier<String> defaultValue) {
        return managedKafkas.stream()
                .map(m -> {
                    KafkaClusterSpec s = m.getSpec().getKafka();
                    String value = quantity.apply(m);
                    if (value == null) {
                        value = defaultValue.get();
                    }
                    return Quantity.getAmountInBytes(Quantity.parse(value))
                            .multiply(BigDecimal.valueOf(s.getReplicas()));
                })
                .mapToLong(BigDecimal::longValue)
                .summaryStatistics();
    }

    int numReplicasForAllZones(List<Node> nodes, long connectionDemand) {
        // use the override if present
        if (defaultReplicaCount.isPresent()) {
            return defaultReplicaCount.get();
        }

        /*
         * we want at least 2 replicas, unless there are fewer nodes
         *
         * an assumption here is that these ingress replicas will not become bandwidth constrained - but that may need further qualification
         */
        return Math.max(Math.min(2, nodes.size()), numReplicasForConnectionDemand(connectionDemand));
    }

    private int numReplicasForConnectionDemand(long connectionDemand) {
        return (int)Math.ceil(connectionDemand / Quantity.getAmountInBytes(maxIngressConnections).doubleValue());
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
}
