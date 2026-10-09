package org.fog.hk261backlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fog.hk261backlog.experiment.ExperimentConfig;
import org.fog.hk261backlog.experiment.ExperimentConfig.PolicyType;
import org.fog.hk261backlog.topology.TopologyConfig;
import org.junit.jupiter.api.Test;

/**
 * Test cho ExperimentConfig: giá trị dẫn xuất và các ràng buộc cấu hình
 * theo INTERFACE_CONTRACT §4.6, §4.7.
 */
class ExperimentConfigTest {

    /** Topology nhỏ cho dễ tính: 2 biên, capacity 10_000 byte/tick. */
    private static TopologyConfig smallTopology() {
        return TopologyConfig.builder().numEdgeNodes(2).cloudIngestBytesPerSec(10_000L).build();
    }

    private static ExperimentConfig.Builder base() {
        return ExperimentConfig.builder().experimentId("t").topologyConfig(smallTopology());
    }

    // ---------------------------------------------------------------
    // Giá trị dẫn xuất
    // ---------------------------------------------------------------

    @Test
    void defaults_areValid() {
        assertTrue(new ExperimentConfig().validate());
    }

    @Test
    void derivedValues_forDefaultConfig() {
        ExperimentConfig config = new ExperimentConfig();

        assertEquals(100_000L, config.getCapacityBytesPerTick());
        assertEquals(10L, config.getNewItemsPerEdgePerTick());   // 0.3 × 100000 / 3 / 1000
        assertEquals(10_000L, config.getNewDataBytesPerEdgePerTick());
        assertEquals(0.3, config.getEffectiveNewDataLoadRatio(), 1e-9);
    }

    @Test
    void newData_isRoundedToWholeItems() {
        // 0.5 × 10000 / 2 biên = 2500 B/biên = 2.5 gói -> làm tròn 3 gói
        ExperimentConfig config = base().newDataLoadRatio(0.5).itemBytes(1000).build();

        assertEquals(3L, config.getNewItemsPerEdgePerTick());
        assertEquals(0.6, config.getEffectiveNewDataLoadRatio(), 1e-9);
    }

    @Test
    void totalTicks_isCeilingOfHorizonOverTick() {
        assertEquals(300L, new ExperimentConfig().getTotalTicks());
        assertEquals(11L, base().horizonSeconds(10.5).build().getTotalTicks());

        TopologyConfig halfSecondTick = TopologyConfig.builder().tickSeconds(0.5).build();
        ExperimentConfig config = ExperimentConfig.builder()
                .experimentId("t").topologyConfig(halfSecondTick).horizonSeconds(10.0).build();
        assertEquals(20L, config.getTotalTicks());
    }

    @Test
    void onlyPriorityAllocatesNewDataItself() {
        assertTrue(PolicyType.PRIORITY_NEW_DATA.allocatesNewDataItself());
        assertEquals(false, PolicyType.FIFO.allocatesNewDataItself());
        assertEquals(false, PolicyType.RATE_LIMIT.allocatesNewDataItself());
        assertEquals(false, PolicyType.ROUND_ROBIN.allocatesNewDataItself());
    }

    // ---------------------------------------------------------------
    // Ràng buộc cấu hình (§4.6, §4.7)
    // ---------------------------------------------------------------

    @Test
    void validate_rejectsItemLargerThanCapacity() {
        assertThrows(IllegalStateException.class, () -> base().itemBytes(20_000).build());
    }

    @Test
    void validate_priority_rejectsItemLargerThanEffectiveOldQuota() {
        assertThrows(IllegalStateException.class, () -> base()
                .policyType(PolicyType.PRIORITY_NEW_DATA)
                .oldDataQuotaBytes(500).itemBytes(1000).build());
    }

    @Test
    void validate_rateLimit_rejectsBucketSmallerThanOneItem() {
        assertThrows(IllegalStateException.class, () -> base()
                .policyType(PolicyType.RATE_LIMIT)
                .rateLimitBytesPerSec(400).rateLimitBurstSeconds(2.0).itemBytes(1000).build());
    }

    @Test
    void validate_roundRobin_requiresPositiveQuantum() {
        assertThrows(IllegalStateException.class, () -> base()
                .policyType(PolicyType.ROUND_ROBIN).roundRobinQuantumBytes(0).build());
    }

    @Test
    void validate_ignoresParametersOfOtherPolicies() {
        // FIFO không dùng oldDataQuota/rate-limit/quantum nên giá trị vô lý của chúng không được làm lỗi
        base().policyType(PolicyType.FIFO)
                .oldDataQuotaBytes(1).rateLimitBytesPerSec(1).roundRobinQuantumBytes(1).build();
    }

    // ---------------------------------------------------------------
    // NaN / ±Infinity
    // ---------------------------------------------------------------

    @Test
    void validate_rejectsNonFiniteNumbers() {
        double[] bad = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double v : bad) {
            assertThrows(IllegalStateException.class, () -> base().newDataLoadRatio(v).build());
            assertThrows(IllegalStateException.class, () -> base().horizonSeconds(v).build());
            assertThrows(IllegalStateException.class, () -> base()
                    .policyType(PolicyType.RATE_LIMIT).rateLimitBurstSeconds(v).build());
        }
    }

    @Test
    void validate_rejectsBasicInvalidValues() {
        assertThrows(IllegalStateException.class, () -> base().experimentId(" ").build());
        assertThrows(IllegalStateException.class, () -> base().newDataLoadRatio(-0.1).build());
        assertThrows(IllegalStateException.class, () -> base().itemBytes(0).build());
        assertThrows(IllegalStateException.class, () -> base().horizonSeconds(0).build());
        assertThrows(IllegalStateException.class, () -> base().repetitionIndex(-1).build());
    }
}