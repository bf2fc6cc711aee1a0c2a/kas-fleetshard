package io.kafka.performance;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.kafka.performance.k8s.KubeClusterResource;

/**
 * Helper class for installing DrainCleaner application.
 * 
 * The DrainCleaner application registers a webhook into K8s API and it is notified about any drain request. Then, it marks
 * affected Strimzi pods with the strimzi.io/manual-rolling-update annotation and, at the next reconciliation event, the CO
 * will execute a rolling update moving these pods to other nodes, as the draining node is cordoned.
 *
 * The CO avoids any pods restart that would cause under-replication in any of the hosted partitions. It is important to block
 * K8s draining by setting PDB maxUnavailable property to zero in cluster CR for both Kafka and Zookeeper components.
 */
public class DrainCleanerInstaller {
    private static final Logger LOGGER = LogManager.getLogger(DrainCleanerInstaller.class);

    static void install(KubeClusterResource cluster, String namespace) throws Exception {
        LOGGER.info("Installing DrainCleaner in namespace {}", namespace);
        List<File> drainCleanerFiles = Arrays.stream(new File(Constants.DRAIN_CLEANER_INSTALL_DIR).listFiles()).sorted()
                .filter(File::isFile)
                .collect(Collectors.toList());

        for (File drainCleanerFile : drainCleanerFiles) {
            File createFile = drainCleanerFile;
            String fileAbsolutePath = createFile.getAbsolutePath();
            LOGGER.info("Creating file {}", fileAbsolutePath);
            cluster.kubeClient().getClient().load(new FileInputStream(fileAbsolutePath))
                .inNamespace(Constants.DRAIN_CLEANER_NAMESPACE).createOrReplace();
        }

        LOGGER.info("Done installing DrainCleaner in namespace {}", namespace);
    }
}
