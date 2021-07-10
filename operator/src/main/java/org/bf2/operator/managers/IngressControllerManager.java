package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Service;
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
import org.bf2.common.OperandUtils;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.operands.Labels;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaRoute;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Startup
@ApplicationScoped
//excluding during smoke tests (when kafka=dev is set) running on Kubernetes
@UnlessBuildProperty(name = "kafka", stringValue = "dev", enableIfMissing = true)
public class IngressControllerManager {

    /**
     * The label key for the multi-AZ IngressController
     */
    public static final String KAS_MULTI_ZONE = "managedkafka.bf2.org/kas-multi-zone";

    /**
     * The node label identifying the AZ in which the node resides
     */
    public static final String TOPOLOGY_KEY = "topology.kubernetes.io/zone";

    protected static final String INGRESS_OPERATOR_NAMESPACE = "openshift-ingress-operator";

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
    Labels routeMatchLabels;

    ResourceInformer<Pod> brokerPodInformer;
    ResourceInformer<Node> nodeInformer;
    ResourceInformer<IngressController> ingressControllerInformer;

    public List<ManagedKafkaRoute> getManagedKafkaRoutesFor(ManagedKafka mk) {
        String multiZoneRoute = getIngressControllerDomain("kas");

        return Stream.concat(
                Stream.of(
                        new ManagedKafkaRoute("bootstrap", "", multiZoneRoute),
                        new ManagedKafkaRoute("admin-server", "admin-server", multiZoneRoute)),
                routesFor(mk)
                    .filter(IS_BROKER_ROUTE)
                    .map(r -> {
                        String namePrefix = mk.getMetadata().getName() + "-";
                        String brokerName = r.getMetadata().getName().replaceFirst(namePrefix, "");
                        String router = getIngressControllerDomain("kas-" + getZoneForBrokerRoute(r));

                        return new ManagedKafkaRoute(brokerName, brokerName, router);
                    }))
                .sorted(Comparator.comparing(ManagedKafkaRoute::getName))
                .collect(Collectors.toList());
    }

    @PostConstruct
    protected void onStart() {
        NonNamespaceOperation<IngressController, IngressControllerList, Resource<IngressController>> ingressControllers =
                openShiftClient.operator().ingressControllers().inNamespace(INGRESS_OPERATOR_NAMESPACE);

        final FilterWatchListDeletable<Node, NodeList> workerNodeFilter = openShiftClient.nodes()
                .withLabel(WORKER_NODE_LABEL)
                .withoutLabel(INFRA_NODE_LABEL);
        nodeInformer = resourceInformerFactory.create(Node.class, workerNodeFilter, null);

        FilterWatchListDeletable<Pod, PodList> brokerPodFilter = openShiftClient.pods().inAnyNamespace().withLabels(Map.of(
                OperandUtils.MANAGED_BY_LABEL, OperandUtils.STRIMZI_OPERATOR_NAME,
                OperandUtils.K8S_NAME_LABEL, "kafka"));
        brokerPodInformer = resourceInformerFactory.create(Pod.class, brokerPodFilter, null);

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

        reconcileIngressControllers();
    }

    @Scheduled(every = "3m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconcileIngressControllers() {
        if (nodeInformer == null || ingressControllerInformer == null) {
            log.warn("One or more informers are not yet ready");
            return;
        }

        String defaultDomain = ingressControllerInformer.getList().stream()
                .filter(ic -> "default".equals(ic.getMetadata().getName()))
                .map(ic -> ic.getStatus().getDomain())
                .findFirst()
                .orElse("apps.testing.domain.tld");


        List<String> zones = nodeInformer.getList().stream()
                .filter(node -> node != null && node.getMetadata().getLabels() != null)
                .map(node -> node.getMetadata().getLabels().get(TOPOLOGY_KEY))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, IngressController> zoneToIngressController = new HashMap<>();
        zones.stream().forEach(z -> zoneToIngressController.put(z, ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas-" + z))));

        List<IngressController> ingressControllers = ingressControllersFrom(zoneToIngressController, defaultDomain);
        ingressControllers.add(buildDefaultIngressController(zones, defaultDomain));

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

    private List<IngressController> ingressControllersFrom(Map<String, IngressController> ingressControllers, String defaultDomain) {
        return ingressControllers.entrySet().stream().map(e -> {
            String zone = e.getKey();
            String kasZone = "kas-" + zone;
            String domain = defaultDomain.replaceFirst("apps", kasZone);
            int replicas = numReplicasForZone(zone);

            Map<String, String> routeMatchLabel = Map.of("managedkafka.bf2.org/" + kasZone, "true");
            LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
            routeMatchLabels.putAll(routeMatchLabel);

            return buildIngressController(kasZone, domain, e.getValue(), replicas, routeSelector, zone);
        }).collect(Collectors.toList());
    }

    private IngressController buildDefaultIngressController(List<String> zones, String defaultDomain) {
        IngressController existing = ingressControllerInformer.getByKey(Cache.namespaceKeyFunc(INGRESS_OPERATOR_NAMESPACE, "kas"));
        int replicas = Math.min(3, Math.toIntExact(zones.stream().map(this::numReplicasForZone).count()));

        final Map<String, String> routeMatchLabel = Map.of(KAS_MULTI_ZONE, "true");
        LabelSelector routeSelector = new LabelSelector(null, routeMatchLabel);
        routeMatchLabels.putAll(routeMatchLabel);

        return buildIngressController("kas", defaultDomain.replaceFirst("apps", "kas"), existing, replicas, routeSelector, null);
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

    private int numReplicasForZone(String zone) {
        int nodesInZone = Math.toIntExact(nodeInformer.getList().stream()
                .filter(node -> zone.equals(node.getMetadata().getLabels().get(TOPOLOGY_KEY)))
                .filter(Objects::nonNull)
                .count());

        // If there are fewer than 3 worker nodes in a zone we should use that number for the
        // replica count, otherwise the IngressController won't be able to get to a healthy state.
        return Math.min(3, nodesInZone);
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
        Stream<Node> nodes = nodeInformer.getList().stream();

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
                .flatMap(nodeName -> nodes.filter(n -> n.getMetadata().getName().equals(nodeName)).findFirst())
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
