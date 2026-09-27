package org.fog.hk261backlog.topology;

/**
 * TopologyConfig — Cấu hình topology cho mô phỏng iFogSim2.
 *
 * Vai trò (theo Detail — Người 1):
 *   - Là lớp cấu hình DUY NHẤT cho phép thay đổi số biên, tài nguyên,
 *     băng thông, độ trễ mà KHÔNG cần sửa code cứng.
 *   - Được BacklogTopologyTest và ExperimentConfig sử dụng.
 *
 * Người 3, 4, 5 sẽ dựa vào các getter ở đây để biết topology có bao nhiêu biên,
 * tài nguyên ra sao. Vì vậy tuần 39 chỉ cần SKELETON đủ rõ chữ ký hàm.
 *
 * TODO (tuần 40-41): hoàn thiện validate(), thêm builder, hỗ trợ đọc từ file.
 */
public class TopologyConfig {

    // ===== Số lượng node =====
    /** Số máy chủ biên (edge node) trong topology. Mặc định: 3. */
    private int numEdgeNodes;

    /** Số sensor gắn vào MỖI biên. Mặc định: 1. */
    private int numSensorsPerEdge;

    // ===== Tài nguyên biên =====
    /** MIPS của mỗi PE trên biên. Mặc định: 1000. */
    private double edgeMips;

    /** Số PE (CPU core) trên mỗi biên. Mặc định: 2. */
    private int edgeNumPes;

    /** RAM (MB) của mỗi biên. Mặc định: 2048. */
    private int edgeRam;

    /** Dung lượng lưu trữ (MB) của mỗi biên. Mặc định: 100000. */
    private long edgeStorage;

    // ===== Tài nguyên trung tâm (cloud/center) =====
    /** MIPS của mỗi PE trên trung tâm. Mặc định: 10000. */
    private double cloudMips;

    /** Số PE trên trung tâm. Mặc định: 8. */
    private int cloudNumPes;

    /** RAM (MB) của trung tâm. Mặc định: 16384. */
    private int cloudRam;

    /** Dung lượng lưu trữ (MB) của trung tâm. Mặc định: 1000000. */
    private long cloudStorage;

    // ===== Mạng =====
    /** Băng thông edge → cloud (Mbps). Mặc định: 100. */
    private double edgeToCloudBandwidth;

    /** Độ trễ edge → cloud (ms). Mặc định: 50. */
    private double edgeToCloudLatency;

    /** Băng thông sensor → edge (Mbps). Mặc định: 10. */
    private double sensorToEdgeBandwidth;

    /** Độ trễ sensor → edge (ms). Mặc định: 5. */
    private double sensorToEdgeLatency;

    // ===== Thí nghiệm =====
    /** Seed ngẫu nhiên — bắt buộc để tái lập thí nghiệm. Mặc định: 42. */
    private long seed;

    // =====================================================================
    // Constructors
    // =====================================================================

    /** Constructor mặc định — dùng cho quick test. */
    public TopologyConfig() {
        this.numEdgeNodes = 3;
        this.numSensorsPerEdge = 1;
        this.edgeMips = 1000;
        this.edgeNumPes = 2;
        this.edgeRam = 2048;
        this.edgeStorage = 100_000L;
        this.cloudMips = 10_000;
        this.cloudNumPes = 8;
        this.cloudRam = 16_384;
        this.cloudStorage = 1_000_000L;
        this.edgeToCloudBandwidth = 100;
        this.edgeToCloudLatency = 50;
        this.sensorToEdgeBandwidth = 10;
        this.sensorToEdgeLatency = 5;
        this.seed = 42L;
    }

    /**
     * Constructor đầy đủ — dùng khi chạy thí nghiệm có kiểm soát.
     * (Skeleton tuần 39: chỉ khai báo, chưa cần validate.)
     */
    public TopologyConfig(int numEdgeNodes,
                          int numSensorsPerEdge,
                          double edgeMips, int edgeNumPes, int edgeRam, long edgeStorage,
                          double cloudMips, int cloudNumPes, int cloudRam, long cloudStorage,
                          double edgeToCloudBandwidth, double edgeToCloudLatency,
                          double sensorToEdgeBandwidth, double sensorToEdgeLatency,
                          long seed) {
        this.numEdgeNodes = numEdgeNodes;
        this.numSensorsPerEdge = numSensorsPerEdge;
        this.edgeMips = edgeMips;
        this.edgeNumPes = edgeNumPes;
        this.edgeRam = edgeRam;
        this.edgeStorage = edgeStorage;
        this.cloudMips = cloudMips;
        this.cloudNumPes = cloudNumPes;
        this.cloudRam = cloudRam;
        this.cloudStorage = cloudStorage;
        this.edgeToCloudBandwidth = edgeToCloudBandwidth;
        this.edgeToCloudLatency = edgeToCloudLatency;
        this.sensorToEdgeBandwidth = sensorToEdgeBandwidth;
        this.sensorToEdgeLatency = sensorToEdgeLatency;
        this.seed = seed;
    }

    // =====================================================================
    // Getters — Người 3/4/5/6 sẽ gọi các hàm này
    // =====================================================================

    public int getNumEdgeNodes() { return numEdgeNodes; }
    public int getNumSensorsPerEdge() { return numSensorsPerEdge; }

    public double getEdgeMips() { return edgeMips; }
    public int getEdgeNumPes() { return edgeNumPes; }
    public int getEdgeRam() { return edgeRam; }
    public long getEdgeStorage() { return edgeStorage; }

    public double getCloudMips() { return cloudMips; }
    public int getCloudNumPes() { return cloudNumPes; }
    public int getCloudRam() { return cloudRam; }
    public long getCloudStorage() { return cloudStorage; }

    public double getEdgeToCloudBandwidth() { return edgeToCloudBandwidth; }
    public double getEdgeToCloudLatency() { return edgeToCloudLatency; }
    public double getSensorToEdgeBandwidth() { return sensorToEdgeBandwidth; }
    public double getSensorToEdgeLatency() { return sensorToEdgeLatency; }

    public long getSeed() { return seed; }

    // =====================================================================
    // Setters — dùng khi ExperimentMatrix quét tổ hợp
    // =====================================================================

    public void setNumEdgeNodes(int n) { this.numEdgeNodes = n; }
    public void setNumSensorsPerEdge(int n) { this.numSensorsPerEdge = n; }
    public void setEdgeMips(double v) { this.edgeMips = v; }
    public void setEdgeNumPes(int v) { this.edgeNumPes = v; }
    public void setEdgeRam(int v) { this.edgeRam = v; }
    public void setEdgeStorage(long v) { this.edgeStorage = v; }
    public void setCloudMips(double v) { this.cloudMips = v; }
    public void setCloudNumPes(int v) { this.cloudNumPes = v; }
    public void setCloudRam(int v) { this.cloudRam = v; }
    public void setCloudStorage(long v) { this.cloudStorage = v; }
    public void setEdgeToCloudBandwidth(double v) { this.edgeToCloudBandwidth = v; }
    public void setEdgeToCloudLatency(double v) { this.edgeToCloudLatency = v; }
    public void setSensorToEdgeBandwidth(double v) { this.sensorToEdgeBandwidth = v; }
    public void setSensorToEdgeLatency(double v) { this.sensorToEdgeLatency = v; }
    public void setSeed(long v) { this.seed = v; }

    // =====================================================================
    // Tiện ích
    // =====================================================================

    /**
     * Kiểm tra cấu hình có hợp lệ trước khi dựng topology.
     * TODO (tuần 40-41): bổ sung kiểm tra chi tiết.
     *
     * @return true nếu hợp lệ
     * @throws IllegalStateException nếu có tham số vô lý
     */
    public boolean validate() {
        if (numEdgeNodes <= 0) {
            throw new IllegalStateException("numEdgeNodes phải > 0");
        }
        if (numSensorsPerEdge < 0) {
            throw new IllegalStateException("numSensorsPerEdge phải >= 0");
        }
        if (edgeMips <= 0 || edgeNumPes <= 0) {
            throw new IllegalStateException("Tài nguyên biên phải > 0");
        }
        if (cloudMips <= 0 || cloudNumPes <= 0) {
            throw new IllegalStateException("Tài nguyên trung tâm phải > 0");
        }
        if (edgeToCloudBandwidth <= 0 || edgeToCloudLatency < 0) {
            throw new IllegalStateException("Tham số mạng edge→cloud không hợp lệ");
        }
        return true;
    }

    @Override
    public String toString() {
        return "TopologyConfig{" +
                "numEdgeNodes=" + numEdgeNodes +
                ", numSensorsPerEdge=" + numSensorsPerEdge +
                ", edgeMips=" + edgeMips +
                ", edgeNumPes=" + edgeNumPes +
                ", edgeRam=" + edgeRam +
                ", cloudMips=" + cloudMips +
                ", cloudNumPes=" + cloudNumPes +
                ", edgeToCloudBandwidth=" + edgeToCloudBandwidth +
                ", edgeToCloudLatency=" + edgeToCloudLatency +
                ", seed=" + seed +
                '}';
    }
}
