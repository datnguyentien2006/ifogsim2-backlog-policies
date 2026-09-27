package org.fog.hk261backlog.topology;

import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.FogDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * BacklogTopologyTest — Bộ chạy mô phỏng chính (skeleton).
 *
 * Vai trò (theo Detail — Người 1):
 *   - Tuần 39: chỉ cần SKELETON để Người 3/4/5 biết sẽ gọi hàm nào,
 *     truyền vào gì, nhận về gì.
 *   - Tuần 40-41: hoàn thiện topology + runner.
 *   - Tuần 42: nối luồng dữ liệu thật
 *     Sensor → mất kết nối → BacklogQueue thật → tái kết nối
 *     → policy (N4/N5) → center.
 *
 * Cách chạy:
 *   java -cp "bin:jars/*" org.fog.hk261backlog.topology.BacklogTopologyTest
 *
 * TODO (tuần 40-41):
 *   - Hoàn thiện createFogDevice() theo API thực tế của iFogSim2.
 *   - Thêm tham số dòng lệnh để chọn TopologyConfig từ file.
 *   - Tích hợp với DisconnectionModel (N3) và SendingPolicy (N4/N5).
 */
public class BacklogTopologyTest {

    // =====================================================================
    // Hằng số cấu hình mô phỏng
    // =====================================================================

    /** Số bước lập lịch (tick) tối đa cho một lần chạy. */
    public static final int MAX_SIMULATION_TICKS = 10_000;

    /** Khoảng thời gian mỗi tick (giây mô phỏng). */
    public static final double TICK_INTERVAL_SEC = 1.0;

    // =====================================================================
    // Trạng thái mô phỏng
    // =====================================================================

    /** Danh sách toàn bộ FogDevice trong topology (cloud + các biên). */
    private List<FogDevice> fogDevices;

    /** Cấu hình topology đang dùng. */
    private TopologyConfig config;

    // =====================================================================
    // Constructor
    // =====================================================================

    public BacklogTopologyTest(TopologyConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("TopologyConfig không được null");
        }
        config.validate();
        this.config = config;
        this.fogDevices = new ArrayList<>();
    }

    // =====================================================================
    // API công khai — Người 3/4/5/6 sẽ gọi
    // =====================================================================

    /**
     * Khởi tạo CloudSim và dựng topology từ config.
     *
     * @return danh sách FogDevice đã tạo (cloud ở index 0, các biên theo sau)
     */
    public List<FogDevice> buildTopology() {
        // TODO (tuần 40-41): gọi CloudSim.init(...), tạo cloud, tạo các biên,
        // nối các biên vào cloud qua link có bandwidth/latency từ config.
        throw new UnsupportedOperationException(
                "Skeleton tuần 39 — sẽ hoàn thiện ở tuần 40-41");
    }

    /**
     * Chạy mô phỏng từ đầu đến cuối với topology đã dựng.
     *
     * TODO (tuần 42): tích hợp DisconnectionModel + BacklogQueue + SendingPolicy.
     */
    public void runSimulation() {
        throw new UnsupportedOperationException(
                "Skeleton tuần 39 — sẽ hoàn thiện ở tuần 42");
    }

    /**
     * Trả về danh sách FogDevice hiện có (sau khi buildTopology).
     */
    public List<FogDevice> getFogDevices() {
        return fogDevices;
    }

    /**
     * Trả về cấu hình topology đang dùng.
     */
    public TopologyConfig getConfig() {
        return config;
    }

    // =====================================================================
    // Helper nội bộ — sẽ hoàn thiện ở tuần 40-41
    // =====================================================================

    /**
     * Tạo một FogDevice với tài nguyên cho trước.
     *
     * @param name        tên định danh (ví dụ "cloud", "edge-0")
     * @param mips        MIPS mỗi PE
     * @param numPes      số PE
     * @param ram         RAM (MB)
     * @param storage     dung lượng lưu trữ (MB)
     * @param bandwidth   băng thông uplink (Mbps)
     * @param latency     độ trễ uplink (ms)
     * @param level       cấp trong cây topology (0 = cloud)
     * @param parentId    id của node cha (-1 nếu là cloud)
     * @return FogDevice đã tạo
     */
    protected FogDevice createFogDevice(String name,
                                        double mips, int numPes,
                                        int ram, long storage,
                                        double bandwidth, double latency,
                                        int level, int parentId) {
        // TODO (tuần 40-41): triển khai theo API iFogSim2.
        throw new UnsupportedOperationException("Skeleton tuần 39");
    }

    // =====================================================================
    // Entry point — chỉ để smoke test
    // =====================================================================

    public static void main(String[] args) {
        System.out.println("[BacklogTopologyTest] Skeleton tuần 39 — chưa chạy mô phỏng.");

        TopologyConfig cfg = new TopologyConfig();
        System.out.println("Config mặc định: " + cfg);

        BacklogTopologyTest runner = new BacklogTopologyTest(cfg);
        System.out.println("Đã khởi tạo runner với " + cfg.getNumEdgeNodes() + " biên.");

        // TODO (tuần 40-41): bỏ comment khi buildTopology() hoàn thiện.
        // List<FogDevice> devices = runner.buildTopology();
        // runner.runSimulation();
    }
}