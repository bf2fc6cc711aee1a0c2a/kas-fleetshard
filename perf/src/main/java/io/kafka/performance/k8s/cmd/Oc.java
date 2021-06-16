package io.kafka.performance.k8s.cmd;

public class Oc extends BaseCmdKubeClient<Oc> {

    private static final String OC = "oc";

    public Oc(String futureNamespace, String config) {
        super(config);
        namespace = futureNamespace;
    }

    @Override
    public String defaultNamespace() {
        return "default";
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
    public String cmd() {
        return OC;
    }
}
