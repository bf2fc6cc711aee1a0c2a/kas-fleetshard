package org.bf2.performance.framework;

import org.bf2.performance.OMB;
import org.bf2.performance.OMBDriver;
import org.bf2.performance.OMBWorkload;

import java.util.List;

class OMBData {
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
