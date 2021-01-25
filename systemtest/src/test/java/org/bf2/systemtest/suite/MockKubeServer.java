package org.bf2.systemtest.suite;

import io.fabric8.kubernetes.api.model.APIServiceList;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.quarkus.test.kubernetes.client.EmptyDefaultKubernetesMockServerTestResource;

/**
 * Mock kubernetes cluster for systemtest suite
 */
public class MockKubeServer extends EmptyDefaultKubernetesMockServerTestResource {

    @Override
    public void configureMockServer(KubernetesMockServer mockServer) {
        super.configureMockServer(mockServer);

        mockServer.expect().get().withPath("/apis/apiregistration.k8s.io/v1/apiservices")
                .andReturn(200, new APIServiceList())
                .always();
    }

}
