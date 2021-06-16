/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package org.bf2.test.k8s.cmdClient;

import org.bf2.test.executor.Exec;

import java.util.List;
import java.util.Map;

/**
 * A {@link KubeCmdClient} implementation wrapping {@code oc}.
 */
public class Oc extends BaseCmdKubeClient<Oc> {

    private static final String OC = "oc";

    public Oc() {
        this(null);
    }

    public Oc(String config) {
        super(config);
    }

    public Oc(String futureNamespace, String config) {
        super(config);
        namespace = futureNamespace;
    }

    @Override
    public String defaultNamespace() {
        return "myproject";
    }

    @Override
    public String defaultOlmNamespace() {
        return "openshift-marketplace";
    }

    @Override
    public Oc namespace(String namespace) {
        return new Oc(namespace, config);
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public Oc createNamespace(String name) {
        try (Context context = defaultContext()) {
            Exec.exec(cmd(), "new-project", name);
        }
        return this;
    }

    public Oc newApp(String template, Map<String, String> params) {
        List<String> cmd = namespacedCommand("new-app", template);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            cmd.add("-p");
            cmd.add(entry.getKey() + "=" + entry.getValue());
        }

        Exec.exec(cmd);
        return this;
    }

    @Override
    public String cmd() {
        return OC;
    }
}
