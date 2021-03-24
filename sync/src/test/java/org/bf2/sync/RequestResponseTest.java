package org.bf2.sync;

import static org.junit.Assert.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;

import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgentStatus;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaList;
import org.junit.jupiter.api.Test;

import java.io.File;

import com.fasterxml.jackson.databind.ObjectMapper;

@QuarkusTest
public class RequestResponseTest {
    private ObjectMapper objMapper = new ObjectMapper();

    @Test
    public void testManagedKafkaSpec() throws Exception {
        File data = new File("src/test/resources/mka.json");
        assertNotNull(objMapper.readValue(data, ManagedKafkaAgent.class));
    }

    @Test
    public void testManagedKafkaList() throws Exception {
        File data = new File("src/test/resources/mk-list.json");
        assertNotNull(objMapper.readValue(data, ManagedKafkaList.class));
    }

    @Test
    public void testManagedKafkaAgentStatus() throws Exception {
        File data = new File("src/test/resources/mka-status.json");
        assertNotNull(objMapper.readValue(data, ManagedKafkaAgentStatus.class));
    }
}
