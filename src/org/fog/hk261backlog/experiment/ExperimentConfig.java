package org.fog.hk261backlog.experiment;

import org.fog.hk261backlog.topology.TopologyConfig;

/**
 * ExperimentConfig — Mô tả đầy đủ MỘT kịch bản thí nghiệm.
 *
 * Vai trò (theo Detail — Người 1, tuần 40-41):
 *   Gói lại mọi thông tin cần để chạy một lần thí nghiệm: dùng topology nào,
 *   chính sách gửi bù nào, mức lệch tồn đọng giữa các biên ra sao, và tải
 *   dữ liệu mới sinh ra trong lúc chạy là bao nhiêu — đúng 3 trục khảo sát
 *   mà đề bài yêu cầu (số biên đã nằm sẵn trong TopologyConfig).
 *
 * N6 sẽ dùng lớp này trong ExperimentMatrix.java để sinh ra toàn bộ tổ hợp
 * cần chạy (policy × số biên × mức lệch tồn đọng × tải dữ liệu mới), mỗi tổ
 * hợp lặp lại nhiều lần với experimentId/repetitionIndex khác nhau.
 *
 * LƯU Ý PHẠM VI: lớp này chỉ MÔ TẢ kịch bản (POJO), không tự chạy mô phỏng.
 * Việc chạy thật nằm ở BacklogTopologyTest#runExperiment(ExperimentConfig).
 */
public class ExperimentConfig {

    /**
     * 4 chính sách gửi bù dữ liệu cần so sánh theo đề bài.
     * Tên enum khớp với 4 class N4/N5 đang cài đặt: FifoPolicy, RateLimitPolicy,
     * RoundRobinPolicy, PriorityNewDataPolicy.
     */
    public enum PolicyType {
        FIFO,
        RATE_LIMIT,
        ROUND_ROBIN,
        PRIORITY_NEW_DATA
    }

    /**
     * Mức chênh lệch tồn đọng giữa các biên — một trong 3 trục khảo sát theo
     * đề bài ("có biên tồn đọng ít, có biên tồn đọng rất nhiều"). Đây là mức
     * ĐỊNH TÍNH do N6 diễn giải thành tham số cụ thể (ví dụ hệ số nhân) bên
     * trong DisconnectionModel/ExperimentMatrix — ExperimentConfig chỉ chọn
     * mức nào trong 3 mức, không tự định nghĩa con số.
     */
    public enum BacklogImbalanceLevel {
        EVEN,       // các biên tồn đọng gần như nhau
        MODERATE,   // lệch vừa phải
        HIGH        // lệch mạnh — có biên tồn rất ít, có biên tồn rất nhiều
    }

    /** Định danh duy nhất cho lần chạy này, dùng làm tên file kết quả/tên dòng trong CSV. */
    private String experimentId;

    /** Topology dùng cho kịch bản này (đã gồm số biên, tài nguyên, băng thông, độ trễ, seed). */
    private TopologyConfig topologyConfig;

    /** Chính sách gửi bù dữ liệu đang được đánh giá trong lần chạy này. */
    private PolicyType policyType;

    /** Mức lệch tồn đọng giữa các biên. */
    private BacklogImbalanceLevel backlogImbalanceLevel;

    /**
     * Tải dữ liệu mới sinh ra trong lúc mô phỏng, tính theo bản ghi/giây mỗi
     * biên — cần khớp đơn vị với tham số tốc độ dữ liệu mới mà N5 đã làm
     * trong MockBacklogGenerator.java (xác nhận lại với N5 nếu đơn vị lệch).
     */
    private double newDataLoadRate;

    /** Số thứ tự lần lặp lại của CÙNG một tổ hợp tham số (0, 1, 2, ...) — phục vụ tính khoảng tin cậy. */
    private int repetitionIndex;

    /**
     * Seed ngẫu nhiên cho RIÊNG lần lặp này. Khác với TopologyConfig#getSeed()
     * (seed cho phần tài nguyên/mạng tĩnh) — seed này dùng cho phần NGẪU NHIÊN
     * của kịch bản (thời điểm mất kết nối, lượng dữ liệu mới...), để mỗi lần
     * lặp lại cho ra kịch bản ngẫu nhiên khác nhau nhưng vẫn tái lập được.
     */
    private long scenarioSeed;

    // =====================================================================
    // Constructors
    // =====================================================================

    /** Constructor mặc định — dùng cho quick test. */
    public ExperimentConfig() {
        this.experimentId = "exp-default";
        this.topologyConfig = new TopologyConfig();
        this.policyType = PolicyType.FIFO;
        this.backlogImbalanceLevel = BacklogImbalanceLevel.EVEN;
        this.newDataLoadRate = 1.0;
        this.repetitionIndex = 0;
        this.scenarioSeed = 42L;
    }

    /**
     * Constructor đầy đủ — dùng khi N6 sinh từng kịch bản trong ExperimentMatrix.
     * Gọi validate() ngay để lỗi nổ ra tại nơi tạo, không phải muộn hơn khi
     * runExperiment() mới chạy.
     */
    public ExperimentConfig(String experimentId,
                             TopologyConfig topologyConfig,
                             PolicyType policyType,
                             BacklogImbalanceLevel backlogImbalanceLevel,
                             double newDataLoadRate,
                             int repetitionIndex,
                             long scenarioSeed) {
        this.experimentId = experimentId;
        this.topologyConfig = topologyConfig;
        this.policyType = policyType;
        this.backlogImbalanceLevel = backlogImbalanceLevel;
        this.newDataLoadRate = newDataLoadRate;
        this.repetitionIndex = repetitionIndex;
        this.scenarioSeed = scenarioSeed;
        validate();
    }

    // =====================================================================
    // Getters
    // =====================================================================

    public String getExperimentId() { return experimentId; }
    public TopologyConfig getTopologyConfig() { return topologyConfig; }
    public PolicyType getPolicyType() { return policyType; }
    public BacklogImbalanceLevel getBacklogImbalanceLevel() { return backlogImbalanceLevel; }
    public double getNewDataLoadRate() { return newDataLoadRate; }
    public int getRepetitionIndex() { return repetitionIndex; }
    public long getScenarioSeed() { return scenarioSeed; }

    // =====================================================================
    // Setters — dùng khi ExperimentMatrix (N6) quét tổ hợp
    // =====================================================================

    public void setExperimentId(String v) { this.experimentId = v; }
    public void setTopologyConfig(TopologyConfig v) { this.topologyConfig = v; }
    public void setPolicyType(PolicyType v) { this.policyType = v; }
    public void setBacklogImbalanceLevel(BacklogImbalanceLevel v) { this.backlogImbalanceLevel = v; }
    public void setNewDataLoadRate(double v) { this.newDataLoadRate = v; }
    public void setRepetitionIndex(int v) { this.repetitionIndex = v; }
    public void setScenarioSeed(long v) { this.scenarioSeed = v; }

    // =====================================================================
    // Tiện ích
    // =====================================================================

    /**
     * Kiểm tra kịch bản có hợp lệ trước khi chạy.
     *
     * @return true nếu hợp lệ
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
        if (newDataLoadRate < 0) {
            throw new IllegalStateException("newDataLoadRate phải >= 0");
        }
        if (repetitionIndex < 0) {
            throw new IllegalStateException("repetitionIndex phải >= 0");
        }
        return true;
    }

    @Override
    public String toString() {
        return "ExperimentConfig{" +
                "experimentId='" + experimentId + '\'' +
                ", policyType=" + policyType +
                ", backlogImbalanceLevel=" + backlogImbalanceLevel +
                ", newDataLoadRate=" + newDataLoadRate +
                ", repetitionIndex=" + repetitionIndex +
                ", scenarioSeed=" + scenarioSeed +
                ", topologyConfig=" + topologyConfig +
                '}';
    }

    // =====================================================================
    // Builder — để N6 tạo nhanh từng kịch bản khi quét ma trận
    // =====================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final ExperimentConfig cfg = new ExperimentConfig(); // bắt đầu từ giá trị mặc định

        public Builder experimentId(String v) { cfg.setExperimentId(v); return this; }
        public Builder topologyConfig(TopologyConfig v) { cfg.setTopologyConfig(v); return this; }
        public Builder policyType(PolicyType v) { cfg.setPolicyType(v); return this; }
        public Builder backlogImbalanceLevel(BacklogImbalanceLevel v) { cfg.setBacklogImbalanceLevel(v); return this; }
        public Builder newDataLoadRate(double v) { cfg.setNewDataLoadRate(v); return this; }
        public Builder repetitionIndex(int v) { cfg.setRepetitionIndex(v); return this; }
        public Builder scenarioSeed(long v) { cfg.setScenarioSeed(v); return this; }

        /** Gọi validate() rồi trả về config — lỗi sẽ nổ ngay tại nơi build(). */
        public ExperimentConfig build() {
            cfg.validate();
            return cfg;
        }
    }
}