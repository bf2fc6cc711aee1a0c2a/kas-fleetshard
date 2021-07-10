package org.bf2.operator.operands;

import javax.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class Labels {

    private volatile Map<String, String> labels;

    public Labels() {
        labels = new HashMap<>();
    }

    public Map<String, String> get() {
        return labels;
    }

    public void putAll(Map<String, String> labels) {
        this.labels.putAll(labels);
    }
}
