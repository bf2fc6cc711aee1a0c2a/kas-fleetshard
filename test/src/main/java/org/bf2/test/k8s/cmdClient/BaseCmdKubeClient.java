/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package org.bf2.test.k8s.cmdClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bf2.test.executor.Exec;
import org.bf2.test.executor.ExecResult;
import org.bf2.test.k8s.KubeClusterException;

import java.io.File;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.lang.String.join;
import static java.util.Arrays.asList;

public abstract class BaseCmdKubeClient<K extends BaseCmdKubeClient<K>> implements KubeCmdClient<K> {

    private static final Logger LOGGER = LogManager.getLogger(BaseCmdKubeClient.class);

    private static final String CREATE = "create";
    private static final String APPLY = "apply";
    private static final String DELETE = "delete";
    private static final String REPLACE = "replace";
    private static final String PROCESS = "process";

    public static final String STATEFUL_SET = "statefulset";
    public static final String CM = "cm";

    protected String config;

    String namespace = defaultNamespace();

    protected BaseCmdKubeClient(String config) {
        this.config = config;
    }

    @Override
    public abstract String cmd();

    @Override
    @SuppressWarnings("unchecked")
    public K deleteByName(String resourceType, String resourceName) {
        Exec.exec(namespacedCommand(DELETE, resourceType, resourceName));
        return (K) this;
    }

    protected static class Context implements AutoCloseable {
        @Override
        public void close() {
        }
    }

    private static final Context NOOP = new Context();

    protected Context defaultContext() {
        return NOOP;
    }

    // Admin context is not implemented now, because it's not needed
    // In case it will be neded in future, we should change the kubeconfig and apply it for both oc and kubectl
    protected Context adminContext() {
        return defaultContext();
    }

    protected List<String> namespacedCommand(String... rest) {
        return command(asList(rest), true);
    }

    @Override
    public String get(String resource, String resourceName) {
        return Exec.exec(namespacedCommand("get", resource, resourceName, "-o", "yaml")).out();
    }

    @Override
    public String getEvents() {
        return Exec.exec(namespacedCommand("get", "events")).out();
    }

    @Override
    @SuppressWarnings("unchecked")
    public K create(File... files) {
        try (Context context = defaultContext()) {
            Map<File, ExecResult> execResults = execRecursive(CREATE, files, Comparator.comparing(File::getName).reversed());
            for (Map.Entry<File, ExecResult> entry : execResults.entrySet()) {
                if (!entry.getValue().exitStatus()) {
                    LOGGER.warn("Failed to create {}!", entry.getKey().getAbsolutePath());
                    LOGGER.debug(entry.getValue().err());
                }
            }
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K apply(File... files) {
        try (Context context = defaultContext()) {
            Map<File, ExecResult> execResults = execRecursive(APPLY, files, Comparator.comparing(File::getName).reversed());
            for (Map.Entry<File, ExecResult> entry : execResults.entrySet()) {
                if (!entry.getValue().exitStatus()) {
                    LOGGER.warn("Failed to apply {}!", entry.getKey().getAbsolutePath());
                    LOGGER.debug(entry.getValue().err());
                }
            }
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K delete(File... files) {
        try (Context context = defaultContext()) {
            Map<File, ExecResult> execResults = execRecursive(DELETE, files, Comparator.comparing(File::getName).reversed());
            for (Map.Entry<File, ExecResult> entry : execResults.entrySet()) {
                if (!entry.getValue().exitStatus()) {
                    LOGGER.warn("Failed to delete {}!", entry.getKey().getAbsolutePath());
                    LOGGER.debug(entry.getValue().err());
                }
            }
            return (K) this;
        }
    }

    private Map<File, ExecResult> execRecursive(String subcommand, File[] files, Comparator<File> cmp) {
        Map<File, ExecResult> execResults = new HashMap<>(25);
        for (File f : files) {
            if (f.isFile()) {
                if (f.getName().endsWith(".yaml")) {
                    execResults.put(f, Exec.exec(null, namespacedCommand(subcommand, "-f", f.getAbsolutePath()), 0, false, false));
                }
            } else if (f.isDirectory()) {
                File[] children = f.listFiles();
                if (children != null) {
                    Arrays.sort(children, cmp);
                    execResults.putAll(execRecursive(subcommand, children, cmp));
                }
            } else if (!f.exists()) {
                throw new RuntimeException(new NoSuchFileException(f.getPath()));
            }
        }
        return execResults;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K replace(File... files) {
        try (Context context = defaultContext()) {
            Map<File, ExecResult> execResults = execRecursive(REPLACE, files, Comparator.comparing(File::getName));
            for (Map.Entry<File, ExecResult> entry : execResults.entrySet()) {
                if (!entry.getValue().exitStatus()) {
                    LOGGER.warn("Failed to replace {}!", entry.getKey().getAbsolutePath());
                    LOGGER.debug(entry.getValue().err());
                }
            }
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K applyContent(String yamlContent) {
        try (Context context = defaultContext()) {
            Exec.exec(yamlContent, namespacedCommand(APPLY, "-f", "-"));
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K deleteContent(String yamlContent) {
        try (Context context = defaultContext()) {
            Exec.exec(yamlContent, namespacedCommand(DELETE, "-f", "-"), 0, true, false);
            return (K) this;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public K createNamespace(String name) {
        try (Context context = adminContext()) {
            Exec.exec(namespacedCommand(CREATE, "namespace", name));
        }
        return (K) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K deleteNamespace(String name) {
        try (Context context = adminContext()) {
            Exec.exec(null, namespacedCommand(DELETE, "namespace", name), 0, true, false);
        }
        return (K) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K scaleByName(String kind, String name, int replicas) {
        try (Context context = defaultContext()) {
            Exec.exec(null, namespacedCommand("scale", kind, name, "--replicas", Integer.toString(replicas)));
            return (K) this;
        }
    }

    @Override
    public ExecResult execInPod(String pod, String... command) {
        List<String> cmd = namespacedCommand("exec", pod, "--");
        cmd.addAll(asList(command));
        return Exec.exec(cmd);
    }

    @Override
    public ExecResult execInPodContainer(String pod, String container, String... command) {
        return execInPodContainer(true, pod, container, command);
    }

    @Override
    public ExecResult execInPodContainer(boolean logToOutput, String pod, String container, String... command) {
        List<String> cmd = namespacedCommand("exec", pod, "-c", container, "--");
        cmd.addAll(asList(command));
        return Exec.exec(null, cmd, 0, logToOutput);
    }

    @Override
    public ExecResult exec(String... command) {
        return exec(true, command);
    }

    @Override
    public ExecResult exec(boolean throwError, String... command) {
        return exec(throwError, true, command);
    }

    @Override
    public ExecResult exec(boolean throwError, boolean logToOutput, String... command) {
        List<String> cmd = command(asList(command), false);
        return Exec.exec(null, cmd, 0, logToOutput, throwError);
    }

    @Override
    public ExecResult execInCurrentNamespace(String... commands) {
        return Exec.exec(namespacedCommand(commands));
    }

    @Override
    public ExecResult execInCurrentNamespace(boolean logToOutput, String... commands) {
        return Exec.exec(null, namespacedCommand(commands), 0, logToOutput);
    }

    enum ExType {
        BREAK,
        CONTINUE,
        THROW
    }

    @Override
    public String toString() {
        return cmd();
    }

    @Override
    public List<String> list(String resourceType) {
        return asList(Exec.exec(namespacedCommand("get", resourceType, "-o", "jsonpath={range .items[*]}{.metadata.name} ")).out().trim().split(" +")).stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    @Override
    public String getResourceAsJson(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("get", resourceType, resourceName, "-o", "json")).out();
    }

    @Override
    public String getResourceAsYaml(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("get", resourceType, resourceName, "-o", "yaml")).out();
    }

    @Override
    public String getResourcesAsYaml(String resourceType) {
        return Exec.exec(namespacedCommand("get", resourceType, "-o", "yaml")).out();
    }

    @Override
    public void createResourceAndApply(String template, Map<String, String> params) {
        List<String> cmd = namespacedCommand("process", template, "-l", "app=" + template, "-o", "yaml");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            cmd.add("-p");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        String yaml = Exec.exec(cmd).out();
        applyContent(yaml);
    }

    @Override
    public String describe(String resourceType, String resourceName) {
        return Exec.exec(namespacedCommand("describe", resourceType, resourceName)).out();
    }

    @Override
    public String logs(String pod, String container) {
        String[] args;
        if (container != null) {
            args = new String[]{"logs", pod, "-c", container};
        } else {
            args = new String[]{"logs", pod};
        }
        return Exec.exec(namespacedCommand(args)).out();
    }

    @Override
    public String searchInLog(String resourceType, String resourceName, long sinceSeconds, String... grepPattern) {
        try {
            return Exec.exec("bash", "-c", join(" ", namespacedCommand("logs", resourceType + "/" + resourceName, "--since=" + sinceSeconds + "s",
                    "|", "grep", " -e " + join(" -e ", grepPattern), "-B", "1"))).out();
        } catch (KubeClusterException e) {
            if (e.result != null && e.result.returnCode() == 1) {
                LOGGER.info("{} not found", grepPattern);
            } else {
                LOGGER.error("Caught exception while searching {} in logs", grepPattern);
            }
        }
        return "";
    }

    @Override
    public String searchInLog(String resourceType, String resourceName, String resourceContainer, long sinceSeconds, String... grepPattern) {
        try {
            return Exec.exec("bash", "-c", join(" ", namespacedCommand("logs", resourceType + "/" + resourceName, "-c " + resourceContainer, "--since=" + sinceSeconds + "s",
                    "|", "grep", " -e " + join(" -e ", grepPattern), "-B", "1"))).out();
        } catch (KubeClusterException e) {
            if (e.result != null && e.result.exitStatus()) {
                LOGGER.info("{} not found", grepPattern);
            } else {
                LOGGER.error("Caught exception while searching {} in logs", grepPattern);
            }
        }
        return "";
    }

    @Override
    public List<String> listResourcesByLabel(String resourceType, String label) {
        return asList(Exec.exec(namespacedCommand("get", resourceType, "-l", label, "-o", "jsonpath={range .items[*]}{.metadata.name} ")).out().split("\\s+"));
    }

    private List<String> command(List<String> rest, boolean namespaced) {
        List<String> result = new ArrayList<>();
        result.add(cmd());
        if (config != null) {
            result.add("--kubeconfig");
            result.add(config);
        }
        if (namespaced) {
            result.add("--namespace");
            result.add(namespace);
        }
        result.addAll(rest);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K process(Map<String, String> parameters, String file, Consumer<String> c) {
        List<String> command = command(asList(PROCESS, "-f", file), false);
        command.addAll(parameters.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toList()));
        ExecResult exec = Exec.builder()
                .throwErrors(true)
                .withCommand(command)
                .exec();
        c.accept(exec.out());
        return (K) this;
    }

}
