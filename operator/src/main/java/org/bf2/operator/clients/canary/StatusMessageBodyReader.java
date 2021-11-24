package org.bf2.operator.clients.canary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.ws.rs.Consumes;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

// This class is just a workaround until https://github.com/strimzi/strimzi-canary/issues/144 is fixed
// When the right application/json content-type is returned, the REST client does conversion to model under the hood.
// Because of the canary bug reporting a text/plain even if the content is JSON, we need to do it manually
@Provider
@Consumes({MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON})
public class StatusMessageBodyReader implements MessageBodyReader<Status> {
    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return type == Status.class;
    }

    @Override
    public Status readFrom(Class<Status> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException {
        String text = new String(entityStream.readAllBytes(), StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        Status status = null;
        try {
            status = mapper.readValue(text, Status.class);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        return status;
    }
}
