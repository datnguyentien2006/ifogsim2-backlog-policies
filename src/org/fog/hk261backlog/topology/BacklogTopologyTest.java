package org.fog.hk261backlog.topology;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * BacklogTopologyTest — Bộ chạy mô phỏng chính.
 *
 * Vai trò (theo Detail — Người 1):
 *   - Tuần 39: SKELETON (đã publish).
 *   - Tuần 40-41: hoàn thiện topology + runner (FILE NÀY).
 *   - Tuần 42: nối luồng dữ liệu thật
 *     Sensor → mất kết nối → BacklogQueue thật → tái kết nối
 *     → policy (N4/N5) → center.
 *
 * ĐÃ HOÀN THIỆN tuần 40-41: buildTopology() dựng đúng 1 cloud + N biên
 * (đọc từ TopologyConfig), nối quan hệ cha-con qua childToLatencyMap.
 * runSimulation() hiện chỉ smoke-test CloudSim khởi động/dừng sạch —
 * việc gắn Sensor + DisconnectionModel + SendingPolicy là phạm vi tuần 42,
 * cố tình CHƯA làm ở đây vì Application/tuple type chưa được định nghĩa.
 *
 * ⚠️ QUAN TRỌNG TRƯỚC KHI MERGE:
 *   Phần createFogDevice() dưới đây viết theo đúng pattern chuẩn dùng trong
 *   các ví dụ chính thức của iFogSim (VRGameFog, DCNSFog...). Vì môi trường
 *   viết code này không có mạng để tải/biên dịch thử với iFogSim2 thật, hãy
 *   mở file tham khảo N3 đang dùng — src/org/fog/test/perfeval/DCNSFog.java —
 *   và đối chiếu TỪNG chữ ký hàm/constructor (đặc biệt FogDeviceCharacteristics,
 *   PowerHost, FogLinearPowerModel, AppModuleAllocationPolicy) trước khi coi
 *   đây là bản hoàn thiện. Nếu có lệch tên lớp/tham số, sửa theo đúng bản
 *   trong repo đã clone — repo là nguồn sự thật, không phải file này.
 *
 * Cách chạy:
 *   java -cp "bin:jars/*" org.fog.hk261backlog.topology.BacklogTopologyTest
 */
public class BacklogTopologyTest {

    // =====================================================================
    // Hằng số cấu hình mô phỏng
    // =====================================================================

    /** Số bước lập lịch (tick) tối đa cho một lần chạy. */
    public static final int MAX_SIMULATION_TICKS = 10_000;

    /** Khoảng thời gian mỗi tick (giây mô phỏng) — cũng dùng làm schedulingInterval của FogDevice. */
    public static final double TICK_INTERVAL_SEC = 1.0;

    /** Băng thông nội bộ host (không phải link mạng giữa các thiết bị) — giá trị mẫu chuẩn của iFogSim. */
    private static final int INTERNAL_HOST_BANDWIDTH = 10_000;

    /** Công suất hoạt động (W) dùng cho FogLinearPowerModel — TODO: xác nhận lại theo DCNSFog.java nếu nhóm cần số liệu năng lượng chính xác (đây là chỉ số MỞ RỘNG, không bắt buộc theo đề bài). */
    private static final double BUSY_POWER_W = 107.339;
    private static final double IDLE_POWER_W = 83.4333;

    // =====================================================================
    // Trạng thái mô phỏng
    // =====================================================================

    /** Danh sách toàn bộ FogDevice trong topology (cloud ở index 0, các biên theo sau). */
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
     * Khởi tạo CloudSim và dựng topology từ config: 1 cloud (level 0) +
     * numEdgeNodes biên (level 1), mỗi biên nối vào cloud với bandwidth/latency
     * lấy từ config.
     *
     * @return danh sách FogDevice đã tạo (cloud ở index 0, các biên theo sau)
     */
    public List<FogDevice> buildTopology() {
        int numUsers = 1;
        Calendar calendar = Calendar.getInstance();
        boolean traceFlag = false;
        CloudSim.init(numUsers, calendar, traceFlag);

        fogDevices.clear();

        // --- Cloud (level 0, không có parent) ---
        FogDevice cloud = createFogDevice(
                "cloud",
                config.getCloudMips(), config.getCloudNumPes(),
                config.getCloudRam(), config.getCloudStorage(),
                config.getEdgeToCloudBandwidth(), 0.0, // latency uplink không áp dụng cho cloud
                0, -1);
        fogDevices.add(cloud);

        // --- Các biên (level 1, con của cloud) ---
        for (int i = 0; i < config.getNumEdgeNodes(); i++) {
            FogDevice edge = createFogDevice(
                    "edge-" + i,
                    config.getEdgeMips(), config.getEdgeNumPes(),
                    config.getEdgeRam(), config.getEdgeStorage(),
                    config.getEdgeToCloudBandwidth(), config.getEdgeToCloudLatency(),
                    1, cloud.getId());
            connectChildToParent(edge, cloud, config.getEdgeToCloudLatency());
            fogDevices.add(edge);
        }

        // TODO (tuần 42 — phạm vi tích hợp dữ liệu thật, KHÔNG làm ở đây):
        //   - Gắn numSensorsPerEdge Sensor vào mỗi edge (cần Application + tuple type).
        //   - Nối Sensor → DisconnectionModel (N3) → BacklogQueue thật (N5)
        //     → SendingPolicy (N4/N5) → cloud, đúng theo mô tả trong Detail.

        return fogDevices;
    }

    /**
     * Chạy mô phỏng từ đầu đến cuối với topology đã dựng.
     *
     * Hiện tại (tuần 40-41): chỉ khởi động/dừng CloudSim để smoke-test topology
     * hợp lệ, CHƯA có logic backlog/policy thật.
     * TODO (tuần 42): tích hợp DisconnectionModel + BacklogQueue + SendingPolicy.
     */
    public void runSimulation() {
        if (fogDevices.isEmpty()) {
            throw new IllegalStateException("Gọi buildTopology() trước khi runSimulation()");
        }
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
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
    // Helper nội bộ
    // =====================================================================

    /**
     * Tạo một FogDevice với tài nguyên cho trước, theo đúng pattern dựng
     * PowerHost + FogDeviceCharacteristics chuẩn của iFogSim.
     *
     * @param name        tên định danh (ví dụ "cloud", "edge-0")
     * @param mips        MIPS mỗi PE
     * @param numPes      số PE
     * @param ram         RAM (MB)
     * @param storage     dung lượng lưu trữ (MB)
     * @param bandwidth   băng thông uplink/downlink tới parent (Mbps) — dùng
     *                    chung một giá trị cho cả 2 chiều vì đề bài không
     *                    yêu cầu tách riêng
     * @param latency     độ trễ uplink tới parent (ms)
     * @param level       cấp trong cây topology (0 = cloud)
     * @param parentId    id của node cha (-1 nếu là cloud)
     * @return FogDevice đã tạo
     */
    protected FogDevice createFogDevice(String name,
                                        double mips, int numPes,
                                        int ram, long storage,
                                        double bandwidth, double latency,
                                        int level, int parentId) {
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < numPes; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mips)));
        }

        int hostId = FogUtils.generateEntityId();

        PowerHost host = new PowerHost(
                hostId,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(INTERNAL_HOST_BANDWIDTH),
                storage,
                peList,
                new StreamOperatorScheduler(peList),
                new FogLinearPowerModel(BUSY_POWER_W, IDLE_POWER_W)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";
        double timeZone = 10.0;
        double costPerSec = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        // Storage vật lý gắn ngoài (khác với "storage" nội bộ của host ở trên) —
        // để rỗng vì đề bài không yêu cầu mô phỏng lưu trữ ngoài.
        List<Storage> storageList = new ArrayList<>();

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, timeZone,
                costPerSec, costPerMem, costPerStorage, costPerBw);

        FogDevice fogDevice;
        try {
            fogDevice = new FogDevice(
                    name, characteristics,
                    new AppModuleAllocationPolicy(hostList),
                    storageList,
                    TICK_INTERVAL_SEC,
                    bandwidth,  // uplinkBandwidth
                    bandwidth,  // downlinkBandwidth (dùng chung giá trị với uplink)
                    latency,    // uplinkLatency
                    0.0);       // ratePerMips — không tính phí xử lý, đề bài không yêu cầu
        } catch (Exception e) {
            throw new RuntimeException("Không tạo được FogDevice '" + name + "'", e);
        }

        fogDevice.setLevel(level);
        fogDevice.setParentId(parentId);
        return fogDevice;
    }

    /**
     * Đăng ký quan hệ cha-con giữa 2 FogDevice: gán parentId cho con, thêm id
     * con vào danh sách childrenIds của cha, và ghi độ trễ của liên kết vào
     * childToLatencyMap của cha — 3 việc này CloudSim/iFogSim dùng để tính
     * latency khi định tuyến tuple giữa các thiết bị.
     */
    private void connectChildToParent(FogDevice child, FogDevice parent, double latency) {
        child.setParentId(parent.getId());
        parent.getChildrenIds().add(child.getId());
        parent.getChildToLatencyMap().put(child.getId(), latency);
    }

    // =====================================================================
    // Entry point — smoke test
    // =====================================================================

    public static void main(String[] args) {
        TopologyConfig cfg = new TopologyConfig();
        System.out.println("Config mặc định: " + cfg);

        BacklogTopologyTest runner = new BacklogTopologyTest(cfg);
        List<FogDevice> devices = runner.buildTopology();

        System.out.println("Đã dựng topology với " + devices.size() + " thiết bị:");
        for (FogDevice d : devices) {
            System.out.println("  - " + d.getName() + " (id=" + d.getId()
                    + ", level=" + d.getLevel() + ", parentId=" + d.getParentId() + ")");
        }

        runner.runSimulation();
        System.out.println("Smoke test xong — CloudSim khởi động/dừng sạch, chưa có logic backlog.");
    }
}
