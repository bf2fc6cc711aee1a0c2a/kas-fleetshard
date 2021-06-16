package io.kafka.performance;

import java.util.Map;

public class RouterConfig {

    private String domain;
    private Map<String, String> routeSelectorLabels;

    public RouterConfig(String domain, Map<String, String> routeSelectorLabels) {
        this.domain = domain;
        this.routeSelectorLabels = routeSelectorLabels;
    }

    public String getDomain() {
        return domain;
    }

    public Map<String, String> getRouteSelectorLabels() {
        return routeSelectorLabels;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setRouteSelectorLabels(Map<String, String> routeSelectorLabels) {
        this.routeSelectorLabels = routeSelectorLabels;
    }
}
