package org.bf2.test.mock;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith({KubeMockServer.class})
public @interface UseKubeMockServer {
    boolean https() default true;

    boolean crud() default false;

    int port() default 56566;
}
