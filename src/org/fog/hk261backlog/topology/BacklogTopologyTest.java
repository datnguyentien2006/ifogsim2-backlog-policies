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
import org.fog.hk261backlog.experiment.ExperimentConfig;
import org.fog.hk261backlog.policy.SendingPolicy;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * BacklogTopologyTest — Bộ chạy mô phỏng chính (N1).
 *
 * Nhận ĐẦY ĐỦ một kịch bản thí nghiệm (ExperimentConfig: chính sách nào, bao nhiêu biên,
 * mức lệch tồn đọng, tải dữ liệu mới...) và chạy ra kết quả đo lường (TickEngine.Result).
 *
 * Phân vai (đúng hợp đồng giao diện):
 *   - buildTopology(): dựng 1 cloud + N biên trong iFogSim/CloudSim (cấu trúc topology).
 *     Cũng là nơi gọi CloudSim.init(...) — cần làm TRƯỚC khi tạo ReconnectionScheduler
 *     (entity của CloudSim) trong connectivityFactory.
 *   - runExperiment(...): dựng topology → tạo policy MỚI + reset (§4.10) → chạy TickEngine
 *     (vòng lặp theo tick, thực thi §2, §4.3, §4.4, §4.12). Vòng tick chạy trên đồng hồ
 *     riêng, gọi ReconnectionScheduler#applyDue(now) đầu mỗi tick (thiết kế disruption §4),
 *     nên KHÔNG cần CloudSim.startSimulation() để có kết quả.
 *   - Mô hình theo hợp đồng §4.2: nút thắt chỉ là khả năng tiếp nhận của trung tâm
 *     (TopologyConfig.cloudIngestBytesPerSec). Băng thông/độ trễ từng biên khai báo cho
 *     iFogSim KHÔNG ảnh hưởng số đo.
 *
 * Phần cần "keo" với code của người khác (không có ở đây vì phụ thuộc API thật của
 * N3/N5): xem Dependencies — ConnectivityView (bọc DisruptionPlan/ReconnectionScheduler)
 * và BacklogStore (bọc BacklogQueue/BacklogItem).
 *
 * Cách chạy (smoke test, từ thư mục gốc repo; Windows dùng ';' thay cho ':'):
 *   java -cp "out/production/iFogSim:jars/*:jars/commons-math3-3.5/commons-math3-3.5.jar" \
 *        org.fog.hk261backlog.topology.BacklogTopologyTest
 *
 * ⚠️ createFogDevice() viết theo pattern chuẩn của ví dụ iFogSim; nếu repo đã clone có
 *   chữ ký khác, sửa theo repo (repo là nguồn sự thật).
 */
public class BacklogTopologyTest {

    // =====================================================================
    // Hằng số
    // =====================================================================

    /** Giữ để tương thích; số tick thật lấy từ ExperimentConfig#getTotalTicks(). Không còn dùng. */
    @Deprecated
    public static final int MAX_SIMULATION_TICKS = 10_000;

    /** Tick mặc định (giây). Giá trị thực dùng là TopologyConfig#getTickSeconds(). */
    public static final double TICK_INTERVAL_SEC = TopologyConfig.DEFAULT_TICK_SECONDS;

    /** Băng thông nội bộ host (không phải link mạng giữa các thiết bị) — giá trị mẫu chuẩn của iFogSim. */
    private static final int INTERNAL_HOST_BANDWIDTH = 10_000;

    /** Công suất cho FogLinearPowerModel — chỉ số MỞ RỘNG (năng lượng), không bắt buộc theo đề bài. */
    private static final double BUSY_POWER_W = 107.339;
    private static final double IDLE_POWER_W = 83.4333;

    // =====================================================================
    // Các thành phần do bên ngoài cung cấp cho mỗi lần chạy
    // =====================================================================

    /**
     * Gói các thành phần của MỘT lần chạy. N6 (ExperimentRunner) dựng đối tượng này cho từng
     * kịch bản; các factory được gọi MỚI cho mỗi lần chạy để không rò trạng thái giữa các lần.
     */
    public static final class Dependencies {
        private final Supplier<SendingPolicy> policyFactory;
        private final Function<ExperimentConfig, TickEngine.ConnectivityView> connectivityFactory;
        private final Function<ExperimentConfig, TickEngine.BacklogStore> storeFactory;
        private TickEngine.TickObserver observer;
        private boolean runnerPollsSelected = TickEngine.DEFAULT_RUNNER_POLLS_SELECTED;
        private List<Integer> edgeIds;

        /**
         * @param policyFactory       tạo policy MỚI theo expConfig.getPolicyType() và tham số riêng
         *                            (oldDataQuotaBytes, R/burstSeconds, quantumBytes...) — hợp đồng §4.10
         * @param connectivityFactory dựng DisruptionPlan từ (reconnectionMode, backlogImbalanceLevel,
         *                            scenarioSeed, horizonSeconds) rồi bọc ReconnectionScheduler. Được gọi
         *                            SAU buildTopology() nên CloudSim đã init.
         * @param storeFactory        tạo kho backlog (BacklogQueue theo biên) rỗng cho lần chạy
         */
        public Dependencies(Supplier<SendingPolicy> policyFactory,
                            Function<ExperimentConfig, TickEngine.ConnectivityView> connectivityFactory,
                            Function<ExperimentConfig, TickEngine.BacklogStore> storeFactory) {
            if (policyFactory == null || connectivityFactory == null || storeFactory == null) {
                throw new IllegalArgumentException("Các factory không được null");
            }
            this.policyFactory = policyFactory;
            this.connectivityFactory = connectivityFactory;
            this.storeFactory = storeFactory;
        }

        /** Gắn bộ nhận số liệu từng tick (MetricsLogger của N6). */
        public Dependencies withObserver(TickEngine.TickObserver o) { this.observer = o; return this; }

        /** false nếu policy tự poll item đã chọn (xem ghi chú mâu thuẫn §1 vs §4.5 trong TickEngine). */
        public Dependencies withRunnerPollsSelected(boolean v) { this.runnerPollsSelected = v; return this; }

        /** Id các biên theo đúng id module disruption dùng; mặc định 0..numEdgeNodes-1. */
        public Dependencies withEdgeIds(List<Integer> ids) { this.edgeIds = ids; return this; }
    }

    // =====================================================================
    // Trạng thái
    // =====================================================================

    /** Danh sách toàn bộ FogDevice (cloud ở index 0, các biên theo sau). */
    private List<FogDevice> fogDevices;

    /** Cấu hình topology đang dùng. */
    private TopologyConfig config;

    /** Kịch bản đang chạy (null nếu chỉ build topology trần). */
    private ExperimentConfig currentExperiment;

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
    // API công khai
    // =====================================================================

    /**
     * Khởi tạo CloudSim và dựng topology: 1 cloud (level 0) + numEdgeNodes biên (level 1).
     *
     * @return danh sách FogDevice (cloud ở index 0, các biên theo sau)
     */
    public List<FogDevice> buildTopology() {
        int numUsers = 1;
        Calendar calendar = Calendar.getInstance();
        boolean traceFlag = false;
        CloudSim.init(numUsers, calendar, traceFlag);

        fogDevices.clear();

        FogDevice cloud = createFogDevice(
                "cloud",
                config.getCloudMips(), config.getCloudNumPes(),
                config.getCloudRam(), config.getCloudStorage(),
                config.getEdgeToCloudBandwidth(), 0.0, // cloud không có uplink
                0, -1);
        fogDevices.add(cloud);

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
        return fogDevices;
    }

    /**
     * Chạy MỘT kịch bản thí nghiệm đầy đủ và trả về kết quả đo.
     * Đây là điểm vào duy nhất cho N6 (ExperimentRunner) và N2 (IntegrationTest).
     *
     * Thứ tự: validate → buildTopology() (CloudSim.init) → policy mới → connectivity → store
     * → TickEngine.run() (policy.reset() được gọi đầu lần chạy).
     *
     * LƯU Ý đổi chữ ký so với bản tuần 40 (runExperiment(ExperimentConfig) trả List&lt;FogDevice&gt;):
     * chạy thật cần policy/connectivity/store do N3/N4/N5 cung cấp nên phải truyền thêm
     * Dependencies; kết quả là TickEngine.Result thay vì danh sách thiết bị
     * (danh sách thiết bị vẫn lấy qua getFogDevices()).
     */
    public TickEngine.Result runExperiment(ExperimentConfig expConfig, Dependencies deps) {
        if (expConfig == null) {
            throw new IllegalArgumentException("ExperimentConfig không được null");
        }
        if (deps == null) {
            throw new IllegalArgumentException("Dependencies không được null");
        }
        expConfig.validate();

        this.currentExperiment = expConfig;
        this.config = expConfig.getTopologyConfig();

        buildTopology(); // CloudSim.init trước khi connectivityFactory tạo ReconnectionScheduler

        SendingPolicy policy = deps.policyFactory.get();                // mới mỗi lần chạy (§4.10)
        TickEngine.ConnectivityView connectivity = deps.connectivityFactory.apply(expConfig);
        TickEngine.BacklogStore store = deps.storeFactory.apply(expConfig);

        TickEngine engine = new TickEngine(expConfig, policy, connectivity, store,
                deps.observer, deps.edgeIds, deps.runnerPollsSelected);
        return engine.run();
    }

    /**
     * Smoke test: chỉ khởi động/dừng CloudSim với topology đã dựng (không có logic backlog).
     * Không cần cho runExperiment().
     */
    public void runSimulation() {
        if (fogDevices.isEmpty()) {
            throw new IllegalStateException("Gọi buildTopology() hoặc runExperiment() trước khi runSimulation()");
        }
        CloudSim.startSimulation();
        CloudSim.stopSimulation();
    }

    public List<FogDevice> getFogDevices() {
        return fogDevices;
    }

    public TopologyConfig getConfig() {
        return config;
    }

    /** Kịch bản đang chạy, hoặc null nếu chưa gọi runExperiment(). */
    public ExperimentConfig getCurrentExperiment() {
        return currentExperiment;
    }

    // =====================================================================
    // Helper nội bộ
    // =====================================================================

    /**
     * Tạo một FogDevice theo pattern dựng PowerHost + FogDeviceCharacteristics chuẩn của iFogSim.
     *
     * @param name      tên định danh ("cloud", "edge-0")
     * @param mips      MIPS mỗi PE
     * @param numPes    số PE
     * @param ram       RAM (MB)
     * @param storage   dung lượng lưu trữ (MB)
     * @param bandwidth băng thông uplink/downlink tới parent (Mbps) — chỉ khai báo cho iFogSim
     * @param latency   độ trễ uplink tới parent (ms) — chỉ khai báo cho iFogSim
     * @param level     cấp trong cây (0 = cloud)
     * @param parentId  id node cha (-1 nếu là cloud)
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
        List<Storage> storageList = new ArrayList<>(); // storage vật lý gắn ngoài: không cần

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                arch, os, vmm, host, timeZone,
                costPerSec, costPerMem, costPerStorage, costPerBw);

        FogDevice fogDevice;
        try {
            fogDevice = new FogDevice(
                    name, characteristics,
                    new AppModuleAllocationPolicy(hostList),
                    storageList,
                    config.getTickSeconds(),
                    bandwidth,  // uplinkBandwidth
                    bandwidth,  // downlinkBandwidth (dùng chung giá trị)
                    latency,    // uplinkLatency
                    0.0);       // ratePerMips — đề bài không yêu cầu chi phí
        } catch (Exception e) {
            throw new RuntimeException("Không tạo được FogDevice '" + name + "'", e);
        }

        fogDevice.setLevel(level);
        fogDevice.setParentId(parentId);
        return fogDevice;
    }

    /** Gán parentId cho con, thêm id con vào childrenIds của cha và ghi latency vào childToLatencyMap của cha. */
    private void connectChildToParent(FogDevice child, FogDevice parent, double latency) {
        child.setParentId(parent.getId());
        parent.getChildrenIds().add(child.getId());
        parent.getChildToLatencyMap().put(child.getId(), latency);
    }

    // =====================================================================
    // Entry point — smoke test cấu trúc topology
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
        System.out.println("Smoke test xong — CloudSim khởi động/dừng sạch.");

        // Ví dụ chạy một kịch bản (cần Dependencies bọc code của N3/N4/N5):
        // ExperimentConfig exp = ExperimentConfig.builder()
        //         .experimentId("exp-001").topologyConfig(cfg)
        //         .policyType(ExperimentConfig.PolicyType.FIFO)
        //         .backlogImbalanceLevel(ExperimentConfig.BacklogImbalanceLevel.MODERATE)
        //         .newDataLoadRatio(0.3).horizonSeconds(300).scenarioSeed(123L).build();
        // TickEngine.Result r = runner.runExperiment(exp, new Dependencies(
        //         () -> /* new FifoPolicy() */ null,
        //         c -> /* bọc ReconnectionScheduler */ null,
        //         c -> /* bọc BacklogQueue */ null));
        // System.out.println(r);
    }
}
