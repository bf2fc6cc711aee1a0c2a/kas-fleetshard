package io.kafka.performance.k8s.cmd;

import io.kafka.performance.executor.ExecResult;

import java.util.Map;
import java.util.function.Consumer;

public interface KubeCmdClient<K extends KubeCmdClient<K>> {

    String defaultNamespace();

    KubeCmdClient<K> namespace(String namespace);

    /** Returns namespace for cluster */
    String namespace();

    K process(Map<String, String> domain, String file, Consumer<String> c);

    K applyContent(String yamlContent);

    /**
     * Execute the given {@code command}. You can specify if potential failure will thrown the exception or not.
     * @param throwError parameter which control thrown exception in case of failure
     * @param command The command
     * @param logToOutput determines if we want to print whole output of command
     * @return The process result.
     */
    ExecResult exec(boolean throwError, boolean logToOutput, String... command);

    String cmd();
}
