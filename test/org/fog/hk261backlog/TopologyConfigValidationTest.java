package org.fog.hk261backlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

import org.fog.hk261backlog.topology.TopologyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test bổ sung cho TopologyConfig (tuần 41): các trường mới (tickSeconds,
 * cloudIngestBytesPerSec, capacityBytesPerTick), giá trị NaN/±Infinity,
 * và các tình huống lỗi khi đọc file .properties.
 * Bổ sung cho TopologyConfigTest, không thay thế.
 */
class TopologyConfigValidationTest {

    private static final double[] NON_FINITE = {
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
    };

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------
    // capacityBytesPerTick — công thức hợp đồng §2: bandwidth × tickSeconds
    // ---------------------------------------------------------------

    @Test
    void capacityBytesPerTick_defaultsToIngestRateTimesOneSecond() {
        TopologyConfig config = new TopologyConfig();

        assertEquals(1.0, config.getTickSeconds(), 0.0);
        assertEquals(100_000L, config.getCloudIngestBytesPerSec());
        assertEquals(100_000L, config.getCapacityBytesPerTick());
    }

    @Test
    void capacityBytesPerTick_followsBandwidthTimesTickSeconds() {
        TopologyConfig config = new TopologyConfig();

        config.setTickSeconds(0.5);
        assertEquals(50_000L, config.getCapacityBytesPerTick());

        config.setTickSeconds(2.0);
        config.setCloudIngestBytesPerSec(250_000L);
        assertEquals(500_000L, config.getCapacityBytesPerTick());
    }

    @Test
    void validate_rejectsNonPositiveTickSecondsAndIngestRate() {
        assertInvalid((c, v) -> c.setTickSeconds(0), 0);
        assertInvalid((c, v) -> c.setTickSeconds(-1), 0);
        assertInvalid((c, v) -> c.setCloudIngestBytesPerSec(0L), 0);
        assertInvalid((c, v) -> c.setCloudIngestBytesPerSec(-5L), 0);
    }

    @Test
    void validate_rejectsCapacityThatRoundsBelowOneByte() {
        TopologyConfig config = new TopologyConfig();
        config.setCloudIngestBytesPerSec(1L);
        config.setTickSeconds(0.1); // 1 B/s × 0.1 s = 0.1 B -> làm tròn 0

        assertThrows(IllegalStateException.class, config::validate);
    }

    // ---------------------------------------------------------------
    // NaN / ±Infinity — phát hiện của N2 QA tuần 41
    // ---------------------------------------------------------------

    @Test
    void validate_rejectsNonFiniteValuesInPositiveFields() {
        for (double bad : NON_FINITE) {
            assertInvalid((c, v) -> c.setEdgeMips(v), bad);
            assertInvalid((c, v) -> c.setCloudMips(v), bad);
            assertInvalid((c, v) -> c.setEdgeToCloudBandwidth(v), bad);
            assertInvalid((c, v) -> c.setSensorToEdgeBandwidth(v), bad);
            assertInvalid((c, v) -> c.setTickSeconds(v), bad);
        }
    }

    @Test
    void validate_rejectsNonFiniteValuesInLatencies() {
        for (double bad : NON_FINITE) {
            assertInvalid((c, v) -> c.setEdgeToCloudLatency(v), bad);
            assertInvalid((c, v) -> c.setSensorToEdgeLatency(v), bad);
        }
    }

    // ---------------------------------------------------------------
    // Builder và file .properties với các trường mới
    // ---------------------------------------------------------------

    @Test
    void builder_acceptsNewFields() {
        TopologyConfig config = TopologyConfig.builder()
                .tickSeconds(0.5)
                .cloudIngestBytesPerSec(400_000L)
                .build();

        assertEquals(0.5, config.getTickSeconds(), 0.0);
        assertEquals(400_000L, config.getCloudIngestBytesPerSec());
        assertEquals(200_000L, config.getCapacityBytesPerTick());
    }

    @Test
    void propertiesFile_roundTripKeepsNewFields() throws IOException {
        TopologyConfig original = TopologyConfig.builder()
                .tickSeconds(0.5)
                .cloudIngestBytesPerSec(250_000L)
                .build();

        Path file = tempDir.resolve("new-fields.properties");
        original.toPropertiesFile(file.toString());
        TopologyConfig restored = TopologyConfig.fromPropertiesFile(file.toString());

        assertEquals(0.5, restored.getTickSeconds(), 0.0);
        assertEquals(250_000L, restored.getCloudIngestBytesPerSec());
        assertEquals(original.getCapacityBytesPerTick(), restored.getCapacityBytesPerTick());
    }

    @Test
    void propertiesFile_malformedNumberThrows() throws IOException {
        Path file = tempDir.resolve("malformed.properties");
        Files.writeString(file, "numEdgeNodes=abc\n");

        assertThrows(NumberFormatException.class,
                () -> TopologyConfig.fromPropertiesFile(file.toString()));
    }

    @Test
    void propertiesFile_missingFileThrowsIOException() {
        Path missing = tempDir.resolve("khong-ton-tai.properties");

        assertThrows(IOException.class,
                () -> TopologyConfig.fromPropertiesFile(missing.toString()));
    }

    @Test
    void propertiesFile_invalidValuesAreRejectedOnLoad() throws IOException {
        Path file = tempDir.resolve("invalid.properties");
        Files.writeString(file, "numEdgeNodes=0\n");

        assertThrows(IllegalStateException.class,
                () -> TopologyConfig.fromPropertiesFile(file.toString()));
    }

    @Test
    void propertiesFile_nonFiniteValuesAreRejectedOnLoad() throws IOException {
        Path file = tempDir.resolve("nan.properties");
        Files.writeString(file, "edgeMips=NaN\n");

        assertThrows(IllegalStateException.class,
                () -> TopologyConfig.fromPropertiesFile(file.toString()));
    }

    @Test
    void validate_returnsTrueForDefaultConfig() {
        assertTrue(new TopologyConfig().validate());
    }

    // ---------------------------------------------------------------

    private static void assertInvalid(BiConsumer<TopologyConfig, Double> mutator, double value) {
        TopologyConfig config = new TopologyConfig();
        mutator.accept(config, value);
        assertThrows(IllegalStateException.class, config::validate);
    }
}