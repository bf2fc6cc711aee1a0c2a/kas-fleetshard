package io.kafka.performance;

import io.openmessaging.benchmark.TestResult;

import java.io.File;

public class OMBWorkloadResult {
    private final File resultFile;
    private final TestResult testResult;

    public OMBWorkloadResult(File resultFile, TestResult testResult) {
        this.resultFile = resultFile;
        this.testResult = testResult;
    }

    public File getResultFile() {
        return resultFile;
    }

    public TestResult getTestResult() {
        return testResult;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("OMBWorkloadResult{");
        sb.append("resultFile=").append(resultFile);
        sb.append(", testResult=").append(testResult);
        sb.append('}');
        return sb.toString();
    }
}
