package org.bf2.systemtest.framework;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.systemtest.operator.FleetShardOperatorManager;
import org.bf2.systemtest.operator.KeycloakOperatorManager;
import org.bf2.systemtest.operator.StrimziOperatorManager;
import org.bf2.test.Environment;
import org.bf2.test.k8s.KubeClient;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class TestPlanExecutionListener implements TestExecutionListener {

    private static final Logger LOGGER = LogManager.getLogger(TestPlanExecutionListener.class);
    private static final String DIVIDER = "=======================================================================";

    private StrimziOperatorManager strimziOperatorManager;
    private KubeClient kube;
    private boolean systemTestsSelected;

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        LOGGER.info(DIVIDER);
        LOGGER.info(DIVIDER);
        LOGGER.info("                        Test run started");
        LOGGER.info(DIVIDER);
        LOGGER.info(DIVIDER);

        List<String> testClasses = getSelectedTestClassNames(testPlan);
        printSelectedTestClasses(testClasses);
        systemTestsSelected = testClasses.isEmpty() || testClasses.stream().anyMatch(className -> className.endsWith("ST"));

        if (systemTestsSelected) {
            deployComponents();
        }

        try {
            Files.createDirectories(Environment.LOG_DIR);
        } catch (IOException e) {
            LOGGER.warn("Test suite cannot create log dirs");
            throw new RuntimeException("Log folders cannot be created: ", e.getCause());
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            if (systemTestsSelected) {
                removeComponents();
            }
        } finally {
            LOGGER.info(DIVIDER);
            LOGGER.info(DIVIDER);
            LOGGER.info("                        Test run finished");
            LOGGER.info(DIVIDER);
            LOGGER.info(DIVIDER);
        }
    }

    private List<String> getSelectedTestClassNames(TestPlan plan) {
        List<TestIdentifier> selectedTests = Arrays.asList(plan.getChildren(plan.getRoots()
                .toArray(new TestIdentifier[0])[0])
                .toArray(new TestIdentifier[0]));

        if (selectedTests.isEmpty()) {
            return Collections.emptyList();
        } else {
            return selectedTests.stream().map(TestIdentifier::getLegacyReportingName).collect(Collectors.toList());
        }
    }

    private void printSelectedTestClasses(List<String> testClasses) {
        if (testClasses.isEmpty()) {
            LOGGER.info("All test classes are selected for run");
        } else {
            LOGGER.info("Following test classes are selected for run:");
            testClasses.forEach(testIdentifier -> LOGGER.info("-> {}", testIdentifier));
        }

        LOGGER.info(DIVIDER);
        LOGGER.info(DIVIDER);
    }

    private void deployComponents() {
        strimziOperatorManager = new StrimziOperatorManager(SystemTestEnvironment.STRIMZI_VERSION);
        kube = KubeClient.getInstance();

        try {
            CompletableFuture.allOf(
                    KeycloakOperatorManager.installKeycloak(kube),
                    strimziOperatorManager.installStrimzi(kube),
                    FleetShardOperatorManager.deployFleetShardOperator(kube),
                    FleetShardOperatorManager.deployFleetShardSync(kube)).join();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void removeComponents() {
        CompletableFuture.allOf(
                KeycloakOperatorManager.uninstallKeycloak(kube),
                FleetShardOperatorManager.deleteFleetShard(kube),
                strimziOperatorManager.uninstallStrimziClusterWideResources(kube))
            .join();
    }
}
