package io.kafka.performance.k8s.cmd;

import io.kafka.performance.executor.Exec;
import io.kafka.performance.executor.ExecResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

public abstract class BaseCmdKubeClient<K extends BaseCmdKubeClient<K>> implements KubeCmdClient<K> {

    private static final String APPLY = "apply";
    private static final String PROCESS = "process";

    protected String config;

    String namespace = defaultNamespace();

    protected BaseCmdKubeClient(String config) {
        this.config = config;
    }

    @Override
    public abstract String cmd();

    protected List<String> namespacedCommand(String... rest) {
        return namespacedCommand(asList(rest));
    }

    private List<String> namespacedCommand(List<String> rest) {
        List<String> result = new ArrayList<>();
        result.add(cmd());
        result.add("--kubeconfig");
        result.add(config);
        result.add("--namespace");
        result.add(namespace);
        result.addAll(rest);
        return result;
    }

    private List<String> command(String... rest) {
        return command(asList(rest));
    }

    private List<String> command(List<String> rest) {
        List<String> result = new ArrayList<>();
        result.add(cmd());
        result.add("--kubeconfig");
        result.add(config);
        result.addAll(rest);
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K applyContent(String yamlContent) {
        Exec.builder()
                .withInput(yamlContent)
                .withCommand(namespacedCommand(APPLY, "-f", "-"))
                .exec();
        return (K) this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public K process(Map<String, String> parameters, String file, Consumer<String> c) {
        List<String> command = command(PROCESS, "-f", file);
        command.addAll(parameters.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.toList()));
        ExecResult exec = Exec.builder()
                .throwErrors(true)
                .withCommand(command)
                .exec();
        c.accept(exec.out());
        return (K) this;
    }

    @Override
    public ExecResult exec(boolean throwError, boolean logToOutput, String... command) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cmd());
        cmd.add("--kubeconfig");
        cmd.add(config);
        cmd.addAll(asList(command));
        return Exec.builder().withCommand(cmd).logToOutput(logToOutput).throwErrors(throwError).exec();
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

}
