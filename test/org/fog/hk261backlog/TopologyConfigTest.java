package org.fog.hk261backlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fog.hk261backlog.topology.TopologyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopologyConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultConstructor_setsExpectedDefaults() {
        TopologyConfig config = new TopologyConfig();

        assertEquals(3, config.getNumEdgeNodes());
        assertEquals(1, config.getNumSensorsPerEdge());
        assertEquals(1000.0, config.getEdgeMips(), 0.0);
        assertEquals(2, config.getEdgeNumPes());
        assertEquals(2048, config.getEdgeRam());
        assertEquals(100_000L, config.getEdgeStorage());
        assertEquals(10_000.0, config.getCloudMips(), 0.0);
        assertEquals(8, config.getCloudNumPes());
        assertEquals(16_384, config.getCloudRam());
        assertEquals(1_000_000L, config.getCloudStorage());
        assertEquals(100.0, config.getEdgeToCloudBandwidth(), 0.0);
        assertEquals(50.0, config.getEdgeToCloudLatency(), 0.0);
        assertEquals(10.0, config.getSensorToEdgeBandwidth(), 0.0);
        assertEquals(5.0, config.getSensorToEdgeLatency(), 0.0);
        assertEquals(42L, config.getSeed());
    }

    @Test
    void fullConstructor_preservesAllValues() {
        TopologyConfig config = new TopologyConfig(
                5, 4,
                1500.5, 4, 4096, 200_000L,
                20_000.5, 16, 32_768, 2_000_000L,
                250.0, 75.5,
                20.0, 8.5,
                12345L);

        assertEquals(5, config.getNumEdgeNodes());
        assertEquals(4, config.getNumSensorsPerEdge());
        assertEquals(1500.5, config.getEdgeMips(), 0.0);
        assertEquals(4, config.getEdgeNumPes());
        assertEquals(4096, config.getEdgeRam());
        assertEquals(200_000L, config.getEdgeStorage());
        assertEquals(20_000.5, config.getCloudMips(), 0.0);
        assertEquals(16, config.getCloudNumPes());
        assertEquals(32_768, config.getCloudRam());
        assertEquals(2_000_000L, config.getCloudStorage());
        assertEquals(250.0, config.getEdgeToCloudBandwidth(), 0.0);
        assertEquals(75.5, config.getEdgeToCloudLatency(), 0.0);
        assertEquals(20.0, config.getSensorToEdgeBandwidth(), 0.0);
        assertEquals(8.5, config.getSensorToEdgeLatency(), 0.0);
        assertEquals(12345L, config.getSeed());
    }

    @Test
    void settersAndGetters_roundTripAllConfigurableFields() {
        TopologyConfig config = new TopologyConfig();

        config.setNumEdgeNodes(7);
        config.setNumSensorsPerEdge(3);
        config.setEdgeMips(1800.25);
        config.setEdgeNumPes(6);
        config.setEdgeRam(8192);
        config.setEdgeStorage(500_000L);
        config.setCloudMips(25_000.75);
        config.setCloudNumPes(12);
        config.setCloudRam(65_536);
        config.setCloudStorage(5_000_000L);
        config.setEdgeToCloudBandwidth(500.0);
        config.setEdgeToCloudLatency(100.0);
        config.setSensorToEdgeBandwidth(50.0);
        config.setSensorToEdgeLatency(12.5);
        config.setSeed(999L);

        assertEquals(7, config.getNumEdgeNodes());
        assertEquals(3, config.getNumSensorsPerEdge());
        assertEquals(1800.25, config.getEdgeMips(), 0.0);
        assertEquals(6, config.getEdgeNumPes());
        assertEquals(8192, config.getEdgeRam());
        assertEquals(500_000L, config.getEdgeStorage());
        assertEquals(25_000.75, config.getCloudMips(), 0.0);
        assertEquals(12, config.getCloudNumPes());
        assertEquals(65_536, config.getCloudRam());
        assertEquals(5_000_000L, config.getCloudStorage());
        assertEquals(500.0, config.getEdgeToCloudBandwidth(), 0.0);
        assertEquals(100.0, config.getEdgeToCloudLatency(), 0.0);
        assertEquals(50.0, config.getSensorToEdgeBandwidth(), 0.0);
        assertEquals(12.5, config.getSensorToEdgeLatency(), 0.0);
        assertEquals(999L, config.getSeed());
    }

    @Test
    void validate_acceptsValidBoundaryValues() {
        TopologyConfig config = new TopologyConfig();
        config.setNumEdgeNodes(1);
        config.setNumSensorsPerEdge(0);
        config.setEdgeToCloudLatency(0.0);
        config.setSensorToEdgeLatency(0.0);

        assertTrue(config.validate());
    }

    @Test
    void validate_rejectsInvalidValues() {
        assertInvalid(config -> config.setNumEdgeNodes(0));
        assertInvalid(config -> config.setNumSensorsPerEdge(-1));
        assertInvalid(config -> config.setEdgeMips(0));
        assertInvalid(config -> config.setEdgeNumPes(0));
        assertInvalid(config -> config.setEdgeRam(0));
        assertInvalid(config -> config.setEdgeStorage(0));
        assertInvalid(config -> config.setCloudMips(0));
        assertInvalid(config -> config.setCloudNumPes(0));
        assertInvalid(config -> config.setCloudRam(0));
        assertInvalid(config -> config.setCloudStorage(0));
        assertInvalid(config -> config.setEdgeToCloudBandwidth(0));
        assertInvalid(config -> config.setEdgeToCloudLatency(-1));
        assertInvalid(config -> config.setSensorToEdgeBandwidth(0));
        assertInvalid(config -> config.setSensorToEdgeLatency(-1));
    }

    @Test
    void fullConstructor_rejectsInvalidConfiguration() {
        assertThrows(IllegalStateException.class, () -> new TopologyConfig(
                0, 1,
                1000, 2, 2048, 100_000,
                10_000, 8, 16_384, 1_000_000,
                100, 50,
                10, 5,
                42));
    }

    @Test
    void builder_buildsConfiguredTopology() {
        TopologyConfig config = TopologyConfig.builder()
                .numEdgeNodes(6)
                .numSensorsPerEdge(2)
                .edgeMips(1200.0)
                .edgeNumPes(4)
                .edgeRam(4096)
                .edgeStorage(250_000L)
                .cloudMips(15_000.0)
                .cloudNumPes(10)
                .cloudRam(32_768)
                .cloudStorage(2_000_000L)
                .edgeToCloudBandwidth(200.0)
                .edgeToCloudLatency(40.0)
                .sensorToEdgeBandwidth(25.0)
                .sensorToEdgeLatency(4.0)
                .seed(2026L)
                .build();

        assertEquals(6, config.getNumEdgeNodes());
        assertEquals(2, config.getNumSensorsPerEdge());
        assertEquals(1200.0, config.getEdgeMips(), 0.0);
        assertEquals(4, config.getEdgeNumPes());
        assertEquals(4096, config.getEdgeRam());
        assertEquals(250_000L, config.getEdgeStorage());
        assertEquals(15_000.0, config.getCloudMips(), 0.0);
        assertEquals(10, config.getCloudNumPes());
        assertEquals(32_768, config.getCloudRam());
        assertEquals(2_000_000L, config.getCloudStorage());
        assertEquals(200.0, config.getEdgeToCloudBandwidth(), 0.0);
        assertEquals(40.0, config.getEdgeToCloudLatency(), 0.0);
        assertEquals(25.0, config.getSensorToEdgeBandwidth(), 0.0);
        assertEquals(4.0, config.getSensorToEdgeLatency(), 0.0);
        assertEquals(2026L, config.getSeed());
    }

    @Test
    void builder_rejectsInvalidConfigurationAtBuild() {
        assertThrows(IllegalStateException.class, () ->
                TopologyConfig.builder().numEdgeNodes(0).build());
    }

    @Test
    void propertiesFile_roundTripPreservesConfiguration() throws IOException {
        TopologyConfig original = TopologyConfig.builder()
                .numEdgeNodes(8)
                .numSensorsPerEdge(3)
                .edgeMips(1750.5)
                .edgeNumPes(5)
                .edgeRam(6144)
                .edgeStorage(300_000L)
                .cloudMips(18_500.25)
                .cloudNumPes(14)
                .cloudRam(49_152)
                .cloudStorage(3_000_000L)
                .edgeToCloudBandwidth(300.0)
                .edgeToCloudLatency(60.0)
                .sensorToEdgeBandwidth(30.0)
                .sensorToEdgeLatency(6.5)
                .seed(777L)
                .build();

        Path file = tempDir.resolve("topology.properties");
        original.toPropertiesFile(file.toString());

        assertTrue(Files.exists(file));
        TopologyConfig restored = TopologyConfig.fromPropertiesFile(file.toString());

        assertEquals(original.getNumEdgeNodes(), restored.getNumEdgeNodes());
        assertEquals(original.getNumSensorsPerEdge(), restored.getNumSensorsPerEdge());
        assertEquals(original.getEdgeMips(), restored.getEdgeMips(), 0.0);
        assertEquals(original.getEdgeNumPes(), restored.getEdgeNumPes());
        assertEquals(original.getEdgeRam(), restored.getEdgeRam());
        assertEquals(original.getEdgeStorage(), restored.getEdgeStorage());
        assertEquals(original.getCloudMips(), restored.getCloudMips(), 0.0);
        assertEquals(original.getCloudNumPes(), restored.getCloudNumPes());
        assertEquals(original.getCloudRam(), restored.getCloudRam());
        assertEquals(original.getCloudStorage(), restored.getCloudStorage());
        assertEquals(original.getEdgeToCloudBandwidth(), restored.getEdgeToCloudBandwidth(), 0.0);
        assertEquals(original.getEdgeToCloudLatency(), restored.getEdgeToCloudLatency(), 0.0);
        assertEquals(original.getSensorToEdgeBandwidth(), restored.getSensorToEdgeBandwidth(), 0.0);
        assertEquals(original.getSensorToEdgeLatency(), restored.getSensorToEdgeLatency(), 0.0);
        assertEquals(original.getSeed(), restored.getSeed());
    }

    @Test
    void propertiesFile_partialConfigKeepsOtherDefaults() throws IOException {
        Path file = tempDir.resolve("partial.properties");
        Files.writeString(file, "numEdgeNodes=10\nedgeMips=2000.0\nseed=123\n");

        TopologyConfig config = TopologyConfig.fromPropertiesFile(file.toString());

        assertEquals(10, config.getNumEdgeNodes());
        assertEquals(2000.0, config.getEdgeMips(), 0.0);
        assertEquals(123L, config.getSeed());
        assertEquals(1, config.getNumSensorsPerEdge());
        assertEquals(2048, config.getEdgeRam());
        assertEquals(100.0, config.getEdgeToCloudBandwidth(), 0.0);
    }

    @Test
    void toString_containsAllMajorConfigurationFields() {
        TopologyConfig config = new TopologyConfig();
        String text = config.toString();

        assertNotNull(text);
        assertTrue(text.contains("numEdgeNodes=3"));
        assertTrue(text.contains("numSensorsPerEdge=1"));
        assertTrue(text.contains("edgeMips=1000.0"));
        assertTrue(text.contains("cloudMips=10000.0"));
        assertTrue(text.contains("edgeToCloudBandwidth=100.0"));
        assertTrue(text.contains("sensorToEdgeLatency=5.0"));
        assertTrue(text.contains("seed=42"));
    }

    private static void assertInvalid(ConfigMutator mutator) {
        TopologyConfig config = new TopologyConfig();
        mutator.apply(config);
        assertThrows(IllegalStateException.class, config::validate);
    }

    @FunctionalInterface
    private interface ConfigMutator {
        void apply(TopologyConfig config);
    }
}