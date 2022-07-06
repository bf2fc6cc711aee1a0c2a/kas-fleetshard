package org.bf2.operator.operands;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraint;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.strimzi.api.kafka.model.template.PodTemplate;
import org.bf2.common.OperandUtils;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;

import java.util.List;

/**
 * Utility for converting a deployment or a strimzi resource into a pause pod deployment
 */
public class PausePodConverter {

    public static Deployment asPauseDeployment(Deployment existing, Deployment toModify, ManagedKafka managedKafka) {
        PodSpec spec = toModify.getSpec().getTemplate().getSpec();
        if (spec.getContainers().size() > 1) {
            throw new AssertionError();
        }
        return PausePodConverter.asPauseDeployment(existing, managedKafka, toModify.getMetadata().getName(),
                toModify.getMetadata(), toModify.getSpec().getReplicas(),
                spec.getAffinity(),
                spec.getTolerations(),
                spec.getTopologySpreadConstraints(),
                spec.getContainers().get(0).getResources());
    }

    public static Deployment asPauseDeployment(Deployment existing, ManagedKafka managedKafka, String deploymentName,
            ObjectMeta meta,
            Integer replicas, PodTemplate template, ResourceRequirements resources) {
        return asPauseDeployment(existing, managedKafka, deploymentName, meta, replicas, template.getAffinity(),
                template.getTolerations(), template.getTopologySpreadConstraints(), resources);
    }

    static Deployment asPauseDeployment(Deployment existing, ManagedKafka managedKafka, String deploymentName,
            ObjectMeta meta,
            Integer replicas, Affinity affinity, List<Toleration> tolerations,
            List<TopologySpreadConstraint> topologySpreadConstraints, ResourceRequirements resources) {
        Deployment result =
                (existing == null ? new DeploymentBuilder() : new DeploymentBuilder(existing)).editOrNewMetadata()
                        .withLabels(meta.getLabels())
                        .addToLabels(OperandUtils.MANAGED_BY_LABEL, OperandUtils.FLEETSHARD_OPERATOR_NAME)
                        .withName(deploymentName)
                        .withNamespace(meta.getNamespace())
                        .endMetadata()
                        .editOrNewSpec()
                        .withSelector(new LabelSelectorBuilder().withMatchLabels(meta.getLabels()).build())
                        .withReplicas(replicas)
                        .editOrNewTemplate()
                        .editOrNewMetadata()
                        .withName(deploymentName)
                        .withLabels(meta.getLabels())
                        .addToLabels("strimzi.io/name", deploymentName)
                        .endMetadata()
                        .editOrNewSpec()
                        .withAffinity(affinity)
                        .withContainers((existing == null ? new ContainerBuilder()
                                : new ContainerBuilder(
                                        existing.getSpec().getTemplate().getSpec().getContainers().get(0)))
                                                .withName("pause")
                                                .withImage("k8s.gcr.io/pause:3.1")
                                                .withResources(resources)
                                                .withImagePullPolicy("IfNotPresent")
                                                .build())
                        .withPriorityClassName("kas-fleetshard-reservation")
                        .withTolerations(tolerations)
                        .withTopologySpreadConstraints(topologySpreadConstraints)
                        .endSpec()
                        .endTemplate()
                        .endSpec()
                        .build();
        OperandUtils.setAsOwner(managedKafka, result);
        return result;
    }

}
