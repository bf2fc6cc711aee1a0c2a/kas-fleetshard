package org.bf2.test.mock;

import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import okhttp3.mockwebserver.MockWebServer;
import org.bf2.test.Environment;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;

/**
 * Custom CRUD mock kube server which allows to set also port of running mock
 */
public class KubeMockServer implements AfterEachCallback, AfterAllCallback, BeforeEachCallback, BeforeAllCallback {
    private KubernetesMockServer mock;
    private NamespacedKubernetesClient client;

    public KubeMockServer() {
    }

    public void afterEach(ExtensionContext context) throws Exception {
        Optional<Class<?>> optClass = context.getTestClass();
        if (optClass.isPresent()) {
            Class<?> testClass = (Class) optClass.get();
            if (this.findField(testClass, true) == null) {
                this.destroy();
            }
        }

    }

    public void afterAll(ExtensionContext context) throws Exception {
        this.destroy();
    }

    public void beforeEach(ExtensionContext context) throws Exception {
        this.setKubernetesClientField(context, false);
    }

    public void beforeAll(ExtensionContext context) throws Exception {
        this.setKubernetesClientField(context, true);
    }

    private void setKubernetesClientField(ExtensionContext context, boolean isStatic) throws IllegalAccessException, FileNotFoundException {
        Optional<Class<?>> optClass = context.getTestClass();
        if (optClass.isPresent()) {
            Class<?> testClass = (Class) optClass.get();
            Field[] fields = testClass.getDeclaredFields();
            Field[] var6 = fields;
            int var7 = fields.length;

            for (int var8 = 0; var8 < var7; ++var8) {
                Field f = var6[var8];
                if (f.getType() == KubernetesClient.class && Modifier.isStatic(f.getModifiers()) == isStatic) {
                    this.createKubernetesClient(testClass);
                    f.setAccessible(true);
                    if (isStatic) {
                        f.set((Object) null, this.client);
                    } else {
                        Optional<Object> optTestInstance = context.getTestInstance();
                        if (optTestInstance.isPresent()) {
                            f.set(optTestInstance.get(), this.client);
                        }
                    }
                }
            }
        }

    }

    private void createKubernetesClient(Class<?> testClass) throws FileNotFoundException {
        UseKubeMockServer a = testClass.getAnnotation(UseKubeMockServer.class);
        this.mock = a.crud() ? new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap(), new KubernetesCrudDispatcher(Collections.emptyList()), a.https()) : new KubernetesMockServer(a.https());
        this.mock.init(InetAddress.getLoopbackAddress(), a.port());
        this.client = this.mock.createClient();

        client.load(new FileInputStream(Paths.get(Environment.ROOT_PATH, "kas-fleetshard-api", "target", "classes", "META-INF", "dekorate", "kubernetes.yml").toString())).get().forEach(crd ->
                client.apiextensions().v1beta1().customResourceDefinitions().createOrReplace((CustomResourceDefinition) crd));
    }

    private void destroy() {
        this.mock.destroy();
        this.client.close();
    }

    private Field findField(Class<?> testClass, boolean isStatic) {
        Field[] fields = testClass.getDeclaredFields();
        Field[] var4 = fields;
        int var5 = fields.length;

        for (int var6 = 0; var6 < var5; ++var6) {
            Field f = var4[var6];
            if (f.getType() == KubernetesClient.class && Modifier.isStatic(f.getModifiers()) == isStatic) {
                return f;
            }
        }

        return null;
    }
}
