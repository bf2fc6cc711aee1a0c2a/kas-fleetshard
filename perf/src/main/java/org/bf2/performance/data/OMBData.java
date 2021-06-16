package org.bf2.performance.data;

import org.bf2.performance.OMB;
import org.bf2.performance.OMBDriver;
import org.bf2.performance.OMBWorkload;
import org.bf2.performance.k8s.KubeClusterResource;

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
