package io.kafka.performance.data;

import io.kafka.performance.OMB;
import io.kafka.performance.OMBDriver;
import io.kafka.performance.OMBWorkload;
import io.kafka.performance.k8s.KubeClusterResource;

import java.util.List;

public class OMBData {
    Integer workerCount;
    List<String> workerNames;
    OMBWorkload workload;
    OMBDriver driver;

    public Integer getWorkerCount() {
        return workerCount;
    }

    public List<String> getWorkerNames() {
        return workerNames;
    }

    public OMBWorkload getWorkload() {
        return workload;
    }

    public OMBDriver getDriver() {
        return driver;
    }

    public OMBData(KubeClusterResource cluster, OMBWorkload workload, OMBDriver driver, OMB omb) {
        this.workerCount = omb.getWorkerNames().size();
        this.workerNames = omb.getWorkerNames();
        this.workload = workload;
        this.driver = driver;
    }
}
