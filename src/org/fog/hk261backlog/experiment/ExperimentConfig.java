package org.fog.hk261backlog.experiment;

import org.fog.hk261backlog.topology.TopologyConfig;

/**
 * ExperimentConfig — Mô tả đầy đủ MỘT kịch bản thí nghiệm.
 *
 * Gói lại mọi thông tin để chạy một lần: dùng topology nào (số biên, dung
 * lượng tiếp nhận của trung tâm), chính sách gửi bù nào + tham số của nó,
 * kiểu tái kết nối, mức lệch tồn đọng giữa các biên và tải dữ liệu mới —
 * đúng 3 trục khảo sát của đề bài (số biên nằm trong TopologyConfig).
 *
 * Đơn vị theo INTERFACE_CONTRACT §2: thời gian = giây mô phỏng, kích thước = byte.
 *
 * Phạm vi: lớp này chỉ MÔ TẢ kịch bản (POJO). Việc chạy nằm ở
 * BacklogTopologyTest#runExperiment (và TickEngine). N6 dùng lớp này trong
 * ExperimentMatrix để sinh các tổ hợp; mỗi lần lặp đổi repetitionIndex/seed.
 *
 * THAY ĐỔI so với bản tuần 40: newDataLoadRate (bản ghi/giây) được thay bằng
 * newDataLoadRatio (tỉ lệ so với dung lượng tiếp nhận của trung tâm), vì
 * policy làm việc theo byte và đề bài nêu trục "mức tải dữ liệu mới so với
 * năng lực xử lý của trung tâm".
 */
public class ExperimentConfig {

    /** 4 chính sách cần so sánh; tên khớp FifoPolicy, RateLimitPolicy, RoundRobinPolicy, PriorityNewDataPolicy. */
    public enum PolicyType {
        FIFO,
        RATE_LIMIT,
        ROUND_ROBIN,
        PRIORITY_NEW_DATA;

        /**
         * true nếu policy TỰ chia dung lượng giữa dữ liệu cũ/mới (chỉ Priority):
         * bộ chạy gọi select() TRƯỚC với capacity nguyên vẹn rồi mới gửi dữ liệu
         * mới (hợp đồng §4.12). 3 policy còn lại: gửi dữ liệu mới TRƯỚC, trừ
         * khỏi capacity rồi mới gọi select(). Hai nhóm KHÔNG dùng chung vòng lặp.
         */
        public boolean allocatesNewDataItself() {
            return this == PRIORITY_NEW_DATA;
        }
    }

    /** Mức chênh lệch tồn đọng giữa các biên (trục khảo sát). Diễn giải thành tham số cụ thể do N3/N6 đảm nhiệm. */
    public enum BacklogImbalanceLevel {
        EVEN,       // các biên tồn đọng gần như nhau
        MODERATE,   // lệch vừa phải
        HIGH        // lệch mạnh — có biên tồn rất ít, có biên tồn rất nhiều
    }

    /** Kiểu tái kết nối — khớp 3 chế độ sinh lịch của module disruption (scattered / clustered / fixed). */
    public enum ReconnectionMode {
        SCATTERED,
        CLUSTERED,
        FIXED
    }

    // ---------------------------------------------------------------------
    // Trường dữ liệu
    // ---------------------------------------------------------------------

    /** Định danh duy nhất cho lần chạy (tên dòng trong CSV). */
    private String experimentId;

    /** Topology + dung lượng tiếp nhận của trung tâm. */
    private TopologyConfig topologyConfig;

    private PolicyType policyType;
    private BacklogImbalanceLevel backlogImbalanceLevel;
    private ReconnectionMode reconnectionMode;

    /**
     * Tải dữ liệu mới, so với dung lượng tiếp nhận của trung tâm:
     * (tổng byte dữ liệu mới sinh ra mỗi tick trên TẤT CẢ biên) / capacityBytesPerTick.
     * 0.3 = 30% năng lực; >1 = quá tải (dữ liệu mới một mình vượt capacity → phần vượt bị DROP, §4.3).
     * Giá trị thực dùng là số nguyên gói/biên/tick — xem getEffectiveNewDataLoadRatio().
     */
    private double newDataLoadRatio;

    /** Kích thước CỐ ĐỊNH mỗi gói (byte). Gói không bị chia nhỏ (§4.6). */
    private int itemBytes;

    /** Độ dài quan sát (giây mô phỏng) — "horizon" của module disruption. */
    private double horizonSeconds;

    // --- Tham số riêng của từng policy (tên theo INTERFACE_CONTRACT §4.7-§5) ---
    /** Priority: hạn mức byte cố định dành cho dữ liệu cũ (oldDataQuotaBytes). */
    private long oldDataQuotaBytes;
    /** Rate-Limit: tốc độ nạp token R (byte/giây). */
    private long rateLimitBytesPerSec;
    /** Rate-Limit: độ sâu bucket = R × burstSeconds (§4.7). */
    private double rateLimitBurstSeconds;
    /** Round-Robin: quantumBytes mỗi lượt của một biên (§5). */
    private long roundRobinQuantumBytes;

    /** Số thứ tự lần lặp của CÙNG tổ hợp tham số (0, 1, 2, ...). */
    private int repetitionIndex;

    /** Seed cho phần NGẪU NHIÊN của kịch bản (lịch mất kết nối...). Khác seed tĩnh của TopologyConfig. */
    private long scenarioSeed;

    // ---------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------

    /** Constructor mặc định — dùng cho quick test. */
    public ExperimentConfig() {
        this.experimentId = "exp-default";
        this.topologyConfig = new TopologyConfig();
        this.policyType = PolicyType.FIFO;
        this.backlogImbalanceLevel = BacklogImbalanceLevel.EVEN;
        this.reconnectionMode = ReconnectionMode.SCATTERED;
        this.newDataLoadRatio = 0.3;
        this.itemBytes = 1_000;
        this.horizonSeconds = 300.0;
        this.oldDataQuotaBytes = 30_000L;
        this.rateLimitBytesPerSec = 50_000L;
        this.rateLimitBurstSeconds = 2.0;
        this.roundRobinQuantumBytes = 5_000L;
        this.repetitionIndex = 0;
        this.scenarioSeed = 42L;
    }

    /** Constructor rút gọn (tương thích bản trước): các tham số còn lại lấy mặc định. */
    public ExperimentConfig(String experimentId,
                            TopologyConfig topologyConfig,
                            PolicyType policyType,
                            BacklogImbalanceLevel backlogImbalanceLevel,
                            double newDataLoadRatio,
                            int repetitionIndex,
                            long scenarioSeed) {
        this();
        this.experimentId = experimentId;
        this.topologyConfig = topologyConfig;
        this.policyType = policyType;
        this.backlogImbalanceLevel = backlogImbalanceLevel;
        this.newDataLoadRatio = newDataLoadRatio;
        this.repetitionIndex = repetitionIndex;
        this.scenarioSeed = scenarioSeed;
        validate();
    }

    // ---------------------------------------------------------------------
    // Getters
    // ---------------------------------------------------------------------

    public String getExperimentId() { return experimentId; }
    public TopologyConfig getTopologyConfig() { return topologyConfig; }
    public PolicyType getPolicyType() { return policyType; }
    public BacklogImbalanceLevel getBacklogImbalanceLevel() { return backlogImbalanceLevel; }
    public ReconnectionMode getReconnectionMode() { return reconnectionMode; }
    public double getNewDataLoadRatio() { return newDataLoadRatio; }
    public int getItemBytes() { return itemBytes; }
    public double getHorizonSeconds() { return horizonSeconds; }
    public long getOldDataQuotaBytes() { return oldDataQuotaBytes; }
    public long getRateLimitBytesPerSec() { return rateLimitBytesPerSec; }
    public double getRateLimitBurstSeconds() { return rateLimitBurstSeconds; }
    public long getRoundRobinQuantumBytes() { return roundRobinQuantumBytes; }
    public int getRepetitionIndex() { return repetitionIndex; }
    public long getScenarioSeed() { return scenarioSeed; }

    // ---------------------------------------------------------------------
    // Giá trị dẫn xuất — bộ chạy (TickEngine) dùng
    // ---------------------------------------------------------------------

    /** capacityBytes của một tick (= bandwidth × tickSeconds, hợp đồng §2). */
    public long getCapacityBytesPerTick() {
        return topologyConfig.getCapacityBytesPerTick();
    }

    /** Số tick của một lần chạy = ceil(horizon / tickSeconds). */
    public long getTotalTicks() {
        return (long) Math.ceil(horizonSeconds / topologyConfig.getTickSeconds() - 1e-9);
    }

    /**
     * Số gói dữ liệu mới MỖI BIÊN sinh ra trong một tick:
     * round(ratio × capacity / (số biên × itemBytes)). Làm tròn về số nguyên gói
     * vì gói không bị chia nhỏ — dùng getEffectiveNewDataLoadRatio() để biết tải thực.
     */
    public long getNewItemsPerEdgePerTick() {
        double perEdgeBytes = newDataLoadRatio * getCapacityBytesPerTick()
                / topologyConfig.getNumEdgeNodes();
        return Math.round(perEdgeBytes / itemBytes);
    }

    /** Byte dữ liệu mới mỗi biên sinh ra mỗi tick (đã làm tròn theo gói). */
    public long getNewDataBytesPerEdgePerTick() {
        return getNewItemsPerEdgePerTick() * itemBytes;
    }

    /** Tải dữ liệu mới thực tế sau khi làm tròn theo gói, so với capacity mỗi tick. */
    public double getEffectiveNewDataLoadRatio() {
        return (double) getNewDataBytesPerEdgePerTick() * topologyConfig.getNumEdgeNodes()
                / getCapacityBytesPerTick();
    }

    // ---------------------------------------------------------------------
    // Setters — N6 dùng khi quét tổ hợp
    // ---------------------------------------------------------------------

    public void setExperimentId(String v) { this.experimentId = v; }
    public void setTopologyConfig(TopologyConfig v) { this.topologyConfig = v; }
    public void setPolicyType(PolicyType v) { this.policyType = v; }
    public void setBacklogImbalanceLevel(BacklogImbalanceLevel v) { this.backlogImbalanceLevel = v; }
    public void setReconnectionMode(ReconnectionMode v) { this.reconnectionMode = v; }
    public void setNewDataLoadRatio(double v) { this.newDataLoadRatio = v; }
    public void setItemBytes(int v) { this.itemBytes = v; }
    public void setHorizonSeconds(double v) { this.horizonSeconds = v; }
    public void setOldDataQuotaBytes(long v) { this.oldDataQuotaBytes = v; }
    public void setRateLimitBytesPerSec(long v) { this.rateLimitBytesPerSec = v; }
    public void setRateLimitBurstSeconds(double v) { this.rateLimitBurstSeconds = v; }
    public void setRoundRobinQuantumBytes(long v) { this.roundRobinQuantumBytes = v; }
    public void setRepetitionIndex(int v) { this.repetitionIndex = v; }
    public void setScenarioSeed(long v) { this.scenarioSeed = v; }

    // ---------------------------------------------------------------------
    // Kiểm tra hợp lệ
    // ---------------------------------------------------------------------

    /**
     * Kiểm tra kịch bản trước khi chạy. Ngoài kiểm tra cơ bản, thực thi các
     * ràng buộc cấu hình mà INTERFACE_CONTRACT yêu cầu "cấu hình phải đảm bảo":
     *   - §4.6: itemBytes (kích thước tối đa 1 gói) <= capacityBytes;
     *     với Priority thêm itemBytes <= effectiveOldQuota = min(oldDataQuotaBytes, capacityBytes).
     *   - Rate-Limit (suy ra từ §4.7): độ sâu bucket = R × burstSeconds phải chứa
     *     được ít nhất 1 gói, nếu không gói đó không bao giờ được gửi.
     * Chỉ kiểm tra tham số của policy ĐANG được chọn.
     *
     * @throws IllegalStateException nếu có tham số vô lý
     */
    public boolean validate() {
        if (experimentId == null || experimentId.isBlank()) {
            throw new IllegalStateException("experimentId không được rỗng");
        }
        if (topologyConfig == null) {
            throw new IllegalStateException("topologyConfig không được null");
        }
        topologyConfig.validate();
        if (policyType == null) {
            throw new IllegalStateException("policyType không được null");
        }
        if (backlogImbalanceLevel == null) {
            throw new IllegalStateException("backlogImbalanceLevel không được null");
        }
        if (reconnectionMode == null) {
            throw new IllegalStateException("reconnectionMode không được null");
        }
        if (Double.isNaN(newDataLoadRatio) || Double.isInfinite(newDataLoadRatio) || newDataLoadRatio < 0) {
            throw new IllegalStateException("newDataLoadRatio phải hữu hạn và >= 0");
        }
        if (itemBytes <= 0) {
            throw new IllegalStateException("itemBytes phải > 0");
        }
        if (!Double.isFinite(horizonSeconds) || horizonSeconds <= 0) {
            throw new IllegalStateException("horizonSeconds phải > 0 và hữu hạn");
        }
        if (repetitionIndex < 0) {
            throw new IllegalStateException("repetitionIndex phải >= 0");
        }

        long capacity = getCapacityBytesPerTick();
        if (itemBytes > capacity) {
            throw new IllegalStateException("itemBytes (" + itemBytes + ") phải <= capacityBytesPerTick ("
                    + capacity + ") — hợp đồng §4.6");
        }

        switch (policyType) {
            case PRIORITY_NEW_DATA: {
                if (oldDataQuotaBytes <= 0) {
                    throw new IllegalStateException("oldDataQuotaBytes phải > 0");
                }
                long effectiveOldQuota = Math.min(oldDataQuotaBytes, capacity);
                if (itemBytes > effectiveOldQuota) {
                    throw new IllegalStateException("itemBytes (" + itemBytes
                            + ") phải <= effectiveOldQuota (" + effectiveOldQuota + ") — hợp đồng §4.6");
                }
                break;
            }
            case RATE_LIMIT: {
                if (rateLimitBytesPerSec <= 0 || !Double.isFinite(rateLimitBurstSeconds) || rateLimitBurstSeconds <= 0) {
                    throw new IllegalStateException("rateLimitBytesPerSec phải > 0 và rateLimitBurstSeconds phải hữu hạn và > 0");
                }
                double bucketDepth = rateLimitBytesPerSec * rateLimitBurstSeconds;
                if (itemBytes > Math.floor(bucketDepth)) {
                    throw new IllegalStateException("Độ sâu bucket R×burstSeconds (" + bucketDepth
                            + ") phải >= itemBytes (" + itemBytes + "), nếu không gói không bao giờ được gửi");
                }
                break;
            }
            case ROUND_ROBIN: {
                if (roundRobinQuantumBytes <= 0) {
                    throw new IllegalStateException("roundRobinQuantumBytes phải > 0");
                }
                break;
            }
            case FIFO:
            default:
                break;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ExperimentConfig{" +
                "experimentId='" + experimentId + '\'' +
                ", policyType=" + policyType +
                ", backlogImbalanceLevel=" + backlogImbalanceLevel +
                ", reconnectionMode=" + reconnectionMode +
                ", newDataLoadRatio=" + newDataLoadRatio +
                ", itemBytes=" + itemBytes +
                ", horizonSeconds=" + horizonSeconds +
                ", oldDataQuotaBytes=" + oldDataQuotaBytes +
                ", rateLimitBytesPerSec=" + rateLimitBytesPerSec +
                ", rateLimitBurstSeconds=" + rateLimitBurstSeconds +
                ", roundRobinQuantumBytes=" + roundRobinQuantumBytes +
                ", repetitionIndex=" + repetitionIndex +
                ", scenarioSeed=" + scenarioSeed +
                ", topologyConfig=" + topologyConfig +
                '}';
    }

    // ---------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ExperimentConfig cfg = new ExperimentConfig();

        public Builder experimentId(String v) { cfg.setExperimentId(v); return this; }
        public Builder topologyConfig(TopologyConfig v) { cfg.setTopologyConfig(v); return this; }
        public Builder policyType(PolicyType v) { cfg.setPolicyType(v); return this; }
        public Builder backlogImbalanceLevel(BacklogImbalanceLevel v) { cfg.setBacklogImbalanceLevel(v); return this; }
        public Builder reconnectionMode(ReconnectionMode v) { cfg.setReconnectionMode(v); return this; }
        public Builder newDataLoadRatio(double v) { cfg.setNewDataLoadRatio(v); return this; }
        public Builder itemBytes(int v) { cfg.setItemBytes(v); return this; }
        public Builder horizonSeconds(double v) { cfg.setHorizonSeconds(v); return this; }
        public Builder oldDataQuotaBytes(long v) { cfg.setOldDataQuotaBytes(v); return this; }
        public Builder rateLimitBytesPerSec(long v) { cfg.setRateLimitBytesPerSec(v); return this; }
        public Builder rateLimitBurstSeconds(double v) { cfg.setRateLimitBurstSeconds(v); return this; }
        public Builder roundRobinQuantumBytes(long v) { cfg.setRoundRobinQuantumBytes(v); return this; }
        public Builder repetitionIndex(int v) { cfg.setRepetitionIndex(v); return this; }
        public Builder scenarioSeed(long v) { cfg.setScenarioSeed(v); return this; }

        /** Gọi validate() rồi trả về config — lỗi nổ ngay tại nơi build(). */
        public ExperimentConfig build() {
            cfg.validate();
            return cfg;
        }
    }
}
