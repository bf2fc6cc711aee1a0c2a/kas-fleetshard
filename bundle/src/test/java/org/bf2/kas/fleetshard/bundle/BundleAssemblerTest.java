package org.bf2.kas.fleetshard.bundle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundleAssemblerTest {

    @Test
    void testAssembler() throws Exception {
        Path targetPath = Paths.get(getClass().getResource("/").toURI()).getParent();
        Path testBundle = targetPath.resolve("test-bundle").toAbsolutePath();
        Files.createDirectories(testBundle);
        Path bundleTar = testBundle.resolve("bundle.tar");

        System.setProperty("kas.bundle.output-directory", testBundle.toAbsolutePath().toString());
        System.setProperty("kas.bundle.operator-archive", targetPath.resolve("test-classes/operator-archive").toString());
        System.setProperty("kas.bundle.sync-archive", targetPath.resolve("test-classes/sync-archive").toString());
        System.setProperty("kas.bundle.version", "1.2.3");
        System.setProperty("kas.bundle.image", "bf2/kas-fleetshard-operator-bundle:1.2.3");
        System.setProperty("kas.bundle.tar-image", bundleTar.toString());
        System.setProperty("kas.bundle.patch-file", targetPath.resolve("test-classes/patch.json").toString());
        BundleAssembler.main(new String[] {});

        Map<String, byte[]> bundleEntries = readArchive(new FileInputStream(bundleTar.toFile()));

        assertEquals(3, bundleEntries.size());

        ObjectMapper jsonMapper = new ObjectMapper();

        ArrayNode manifest = (ArrayNode) jsonMapper.readTree(bundleEntries.get("manifest.json"));
        assertEquals(1, manifest.size());

        ObjectNode config = (ObjectNode) jsonMapper.readTree(bundleEntries.get(manifest.get(0).get("Config").asText()));
        ArrayNode layers = (ArrayNode) manifest.get(0).get("Layers");

        Map<String, String> labels = StreamSupport.stream(Spliterators.spliteratorUnknownSize(config.get("config").get("Labels").fields(), Spliterator.ORDERED), false)
                .filter(e -> e.getKey().startsWith("operators.operatorframework.io.bundle"))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().asText()));

        assertEquals(6, labels.size());

        assertEquals(1, layers.size());

        String layerFileName = layers.get(0).asText();
        Map<String, byte[]> layerEntries = readArchive(new GZIPInputStream(new ByteArrayInputStream(bundleEntries.get(layerFileName))));

        Path expectedBundle = targetPath.resolve("test-classes/expected-bundle");
        Files.walk(expectedBundle)
            .filter(Files::isRegularFile)
            .forEach(file -> {
                try {
                    String expected = Files.readString(file);
                    String actual = new String(layerEntries.get(expectedBundle.relativize(file).toString()), StandardCharsets.UTF_8);
                    assertEquals(expected, actual);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }

    static Map<String, byte[]> readArchive(InputStream input) throws IOException, ArchiveException {
        Map<String, byte[]> bundleEntries = new HashMap<>();

        try (TarArchiveInputStream tarStream = (TarArchiveInputStream) new ArchiveStreamFactory().createArchiveInputStream("tar", input)) {

            TarArchiveEntry entry = null;

            while ((entry = (TarArchiveEntry) tarStream.getNextEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                IOUtils.copy(tarStream, buffer);
                bundleEntries.put(entry.getName(), buffer.toByteArray());
            }
        }

        return bundleEntries;
    }
}
