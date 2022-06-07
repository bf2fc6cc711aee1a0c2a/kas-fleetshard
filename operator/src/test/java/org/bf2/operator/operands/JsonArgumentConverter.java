package org.bf2.operator.operands;

import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import static com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance;

public class JsonArgumentConverter implements ArgumentConverter {

    @Override
    public Object convert(Object o, ParameterContext parameterContext) throws ArgumentConversionException {
        try (InputStream src = new ByteArrayInputStream(String.valueOf(o).getBytes())) {
            return Serialization.jsonMapper().readValue(src, defaultInstance().constructType(parameterContext.getParameter().getParameterizedType()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
