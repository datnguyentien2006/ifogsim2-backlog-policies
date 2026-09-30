package org.fog.hk261backlog.topology;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * TopologyConfig — Cấu hình topology cho mô phỏng iFogSim2.
 *
 * Vai trò (theo Detail — Người 1):
 *   - Là lớp cấu hình DUY NHẤT cho phép thay đổi số biên, tài nguyên,
 *     băng thông, độ trễ mà KHÔNG cần sửa code cứng.
 *   - Được BacklogTopologyTest và ExperimentConfig sử dụng.
 *
 * Người 3, 4, 5 sẽ dựa vào các getter ở đây để biết topology có bao nhiêu biên,
 * tài nguyên ra sao.
 *
 * Hoàn thiện tuần 40-41:
 *   - validate() kiểm tra đầy đủ mọi tham số (không chỉ phần lõi).
 *   - Builder fluent để tạo config gọn hơn khi ExperimentMatrix quét tổ hợp.
 *   - fromPropertiesFile()/toPropertiesFile() để lưu/đọc cấu hình từ file
 *     .properties, phục vụ việc N6 tái lập đúng một tổ hợp cụ thể sau này.
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
     * Gọi validate() ngay sau khi tạo để bắt lỗi sớm thay vì để lỗi
     * trôi tới lúc buildTopology() mới phát hiện.
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
        validate();
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
     * Hoàn thiện tuần 40-41: kiểm tra đầy đủ MỌI tham số, không chỉ phần lõi,
     * vì N6 sẽ dùng setter để quét ma trận thí nghiệm — một giá trị âm/0 lọt
     * qua đây sẽ chỉ nổ ra rất muộn bên trong CloudSim, khó debug hơn nhiều.
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
            throw new IllegalStateException("Tài nguyên biên (mips/numPes) phải > 0");
        }
        if (edgeRam <= 0) {
            throw new IllegalStateException("edgeRam phải > 0");
        }
        if (edgeStorage <= 0) {
            throw new IllegalStateException("edgeStorage phải > 0");
        }
        if (cloudMips <= 0 || cloudNumPes <= 0) {
            throw new IllegalStateException("Tài nguyên trung tâm (mips/numPes) phải > 0");
        }
        if (cloudRam <= 0) {
            throw new IllegalStateException("cloudRam phải > 0");
        }
        if (cloudStorage <= 0) {
            throw new IllegalStateException("cloudStorage phải > 0");
        }
        if (edgeToCloudBandwidth <= 0 || edgeToCloudLatency < 0) {
            throw new IllegalStateException("Tham số mạng edge→cloud không hợp lệ");
        }
        if (sensorToEdgeBandwidth <= 0 || sensorToEdgeLatency < 0) {
            throw new IllegalStateException("Tham số mạng sensor→edge không hợp lệ");
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
                ", edgeStorage=" + edgeStorage +
                ", cloudMips=" + cloudMips +
                ", cloudNumPes=" + cloudNumPes +
                ", cloudRam=" + cloudRam +
                ", cloudStorage=" + cloudStorage +
                ", edgeToCloudBandwidth=" + edgeToCloudBandwidth +
                ", edgeToCloudLatency=" + edgeToCloudLatency +
                ", sensorToEdgeBandwidth=" + sensorToEdgeBandwidth +
                ", sensorToEdgeLatency=" + sensorToEdgeLatency +
                ", seed=" + seed +
                '}';
    }

    // =====================================================================
    // Builder — để N6 tạo config gọn hơn khi quét ma trận thí nghiệm
    // =====================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final TopologyConfig cfg = new TopologyConfig(); // bắt đầu từ giá trị mặc định

        public Builder numEdgeNodes(int v) { cfg.setNumEdgeNodes(v); return this; }
        public Builder numSensorsPerEdge(int v) { cfg.setNumSensorsPerEdge(v); return this; }
        public Builder edgeMips(double v) { cfg.setEdgeMips(v); return this; }
        public Builder edgeNumPes(int v) { cfg.setEdgeNumPes(v); return this; }
        public Builder edgeRam(int v) { cfg.setEdgeRam(v); return this; }
        public Builder edgeStorage(long v) { cfg.setEdgeStorage(v); return this; }
        public Builder cloudMips(double v) { cfg.setCloudMips(v); return this; }
        public Builder cloudNumPes(int v) { cfg.setCloudNumPes(v); return this; }
        public Builder cloudRam(int v) { cfg.setCloudRam(v); return this; }
        public Builder cloudStorage(long v) { cfg.setCloudStorage(v); return this; }
        public Builder edgeToCloudBandwidth(double v) { cfg.setEdgeToCloudBandwidth(v); return this; }
        public Builder edgeToCloudLatency(double v) { cfg.setEdgeToCloudLatency(v); return this; }
        public Builder sensorToEdgeBandwidth(double v) { cfg.setSensorToEdgeBandwidth(v); return this; }
        public Builder sensorToEdgeLatency(double v) { cfg.setSensorToEdgeLatency(v); return this; }
        public Builder seed(long v) { cfg.setSeed(v); return this; }

        /** Gọi validate() rồi trả về config — lỗi sẽ nổ ngay tại nơi build(), không phải muộn hơn. */
        public TopologyConfig build() {
            cfg.validate();
            return cfg;
        }
    }

    // =====================================================================
    // Đọc / ghi file .properties — phục vụ tái lập một tổ hợp thí nghiệm
    // =====================================================================

    /**
     * Đọc cấu hình từ file .properties. Các khoá nhận diện trùng tên field,
     * ví dụ: numEdgeNodes=5, edgeMips=1200.0, seed=123
     * Field nào không có trong file sẽ giữ giá trị mặc định.
     */
    public static TopologyConfig fromPropertiesFile(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            p.load(in);
        }
        TopologyConfig cfg = new TopologyConfig();
        if (p.containsKey("numEdgeNodes")) cfg.setNumEdgeNodes(Integer.parseInt(p.getProperty("numEdgeNodes")));
        if (p.containsKey("numSensorsPerEdge")) cfg.setNumSensorsPerEdge(Integer.parseInt(p.getProperty("numSensorsPerEdge")));
        if (p.containsKey("edgeMips")) cfg.setEdgeMips(Double.parseDouble(p.getProperty("edgeMips")));
        if (p.containsKey("edgeNumPes")) cfg.setEdgeNumPes(Integer.parseInt(p.getProperty("edgeNumPes")));
        if (p.containsKey("edgeRam")) cfg.setEdgeRam(Integer.parseInt(p.getProperty("edgeRam")));
        if (p.containsKey("edgeStorage")) cfg.setEdgeStorage(Long.parseLong(p.getProperty("edgeStorage")));
        if (p.containsKey("cloudMips")) cfg.setCloudMips(Double.parseDouble(p.getProperty("cloudMips")));
        if (p.containsKey("cloudNumPes")) cfg.setCloudNumPes(Integer.parseInt(p.getProperty("cloudNumPes")));
        if (p.containsKey("cloudRam")) cfg.setCloudRam(Integer.parseInt(p.getProperty("cloudRam")));
        if (p.containsKey("cloudStorage")) cfg.setCloudStorage(Long.parseLong(p.getProperty("cloudStorage")));
        if (p.containsKey("edgeToCloudBandwidth")) cfg.setEdgeToCloudBandwidth(Double.parseDouble(p.getProperty("edgeToCloudBandwidth")));
        if (p.containsKey("edgeToCloudLatency")) cfg.setEdgeToCloudLatency(Double.parseDouble(p.getProperty("edgeToCloudLatency")));
        if (p.containsKey("sensorToEdgeBandwidth")) cfg.setSensorToEdgeBandwidth(Double.parseDouble(p.getProperty("sensorToEdgeBandwidth")));
        if (p.containsKey("sensorToEdgeLatency")) cfg.setSensorToEdgeLatency(Double.parseDouble(p.getProperty("sensorToEdgeLatency")));
        if (p.containsKey("seed")) cfg.setSeed(Long.parseLong(p.getProperty("seed")));
        cfg.validate();
        return cfg;
    }

    /** Ghi cấu hình hiện tại ra file .properties — dùng để lưu lại đúng tổ hợp đã chạy. */
    public void toPropertiesFile(String path) throws IOException {
        Properties p = new Properties();
        p.setProperty("numEdgeNodes", String.valueOf(numEdgeNodes));
        p.setProperty("numSensorsPerEdge", String.valueOf(numSensorsPerEdge));
        p.setProperty("edgeMips", String.valueOf(edgeMips));
        p.setProperty("edgeNumPes", String.valueOf(edgeNumPes));
        p.setProperty("edgeRam", String.valueOf(edgeRam));
        p.setProperty("edgeStorage", String.valueOf(edgeStorage));
        p.setProperty("cloudMips", String.valueOf(cloudMips));
        p.setProperty("cloudNumPes", String.valueOf(cloudNumPes));
        p.setProperty("cloudRam", String.valueOf(cloudRam));
        p.setProperty("cloudStorage", String.valueOf(cloudStorage));
        p.setProperty("edgeToCloudBandwidth", String.valueOf(edgeToCloudBandwidth));
        p.setProperty("edgeToCloudLatency", String.valueOf(edgeToCloudLatency));
        p.setProperty("sensorToEdgeBandwidth", String.valueOf(sensorToEdgeBandwidth));
        p.setProperty("sensorToEdgeLatency", String.valueOf(sensorToEdgeLatency));
        p.setProperty("seed", String.valueOf(seed));
        try (var out = Files.newOutputStream(Path.of(path))) {
            p.store(out, "TopologyConfig — sinh tự động, xem TopologyConfig#toPropertiesFile");
        }
    }
}
