package org.fog.hk261backlog.topology;

import org.fog.hk261backlog.backlog.BacklogQueue;
import org.fog.hk261backlog.experiment.ExperimentConfig;
import org.fog.hk261backlog.policy.SendingPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TickEngine — vòng lặp theo tick của bộ chạy mô phỏng chính (N1).
 *
 * Thực thi ĐÚNG các quy tắc của INTERFACE_CONTRACT:
 *   §2/§4.1  capacityBytes = bandwidth × tickSeconds (lấy từ TopologyConfig).
 *   §4.3     dữ liệu mới của tick chỉ gồm dữ liệu sinh ra TRONG tick đó; phần vượt
 *            dung lượng dành cho nó bị DROP (mất gói, không retry, không vào BacklogQueue).
 *   §4.4     policy chỉ nhận hàng đợi của các biên ĐANG KẾT NỐI, key tăng dần.
 *   §4.10    reset() được gọi đầu mỗi lần chạy (bộ chạy nên tạo policy mới mỗi lần).
 *   §4.12    HAI nhóm thứ tự gọi, KHÔNG dùng chung một nhánh:
 *             - FIFO / Rate-Limit / Round-Robin: gửi dữ liệu mới (min) → trừ khỏi capacity
 *               → select(backlogCapacity) → gửi → poll.
 *             - Priority: select(capacity NGUYÊN VẸN) → remainingForNew = max(0, capacity −
 *               byte cũ đã chọn) → gửi dữ liệu mới tối đa remainingForNew (dư bị DROP) → poll.
 *   §1/§4.9  mọi phép trừ dung lượng chặn ở 0; policy nhận capacity <= 0 phải trả danh sách rỗng.
 *
 * Độc lập với iFogSim/CloudSim và với code cụ thể của N3/N5/N6: mọi tiếp xúc với
 * module khác đi qua 3 interface nhỏ bên dưới (ConnectivityView, BacklogStore, TickObserver).
 * Nhờ đó logic hợp đồng ở đây kiểm thử được riêng và chỉ cần viết một lớp "keo" mỏng
 * (adapter) khi ghép với ReconnectionScheduler/BacklogQueue thật.
 */
public final class TickEngine {

    /**
     * Ai poll item đã chọn khỏi hàng đợi?
     * INTERFACE_CONTRACT §1 nói RUNNER poll sau khi gửi; §4.5 và §5 lại nói POLICY tự poll.
     * Hai chỗ mâu thuẫn — mặc định ở đây theo §1 (runner poll) VÀ có kiểm tra lúc chạy
     * (xem {@link #pollSelected}) để bắt ngay nếu policy đã tự poll. Khi N4/N5 chốt xong,
     * truyền cờ runnerPollsSelected tương ứng.
     */
    public static final boolean DEFAULT_RUNNER_POLLS_SELECTED = true;

    // =====================================================================
    // Cổng giao tiếp với module khác
    // =====================================================================

    /** Trạng thái kết nối của từng biên theo thời gian (adapter của DisruptionPlan/ReconnectionScheduler). */
    public interface ConnectivityView {
        /** Đầu mỗi tick: áp dụng mọi transition đã đến hạn (ReconnectionScheduler#applyDue). */
        void advanceTo(double now);

        /** Biên đang ONLINE tại thời điểm vừa advanceTo? (đúng thời điểm phục hồi tính là ONLINE.) */
        boolean isOnline(int edgeId);
    }

    /**
     * Kho backlog theo biên (adapter của BacklogQueue/BacklogItem thật). Item được truyền dạng
     * Object để TickEngine không phụ thuộc vị trí/kiểu cụ thể của BacklogItem.
     */
    public interface BacklogStore {
        /** Hàng đợi tồn đọng của một biên (đối tượng thật mà policy sẽ nhận). */
        BacklogQueue queueOf(int edgeId);

        /** Thêm một gói tồn đọng mới (dữ liệu sinh ra khi biên OFFLINE) vào cuối hàng đợi. */
        void enqueue(int edgeId, int itemBytes, double createdAtSeconds);

        int edgeOf(Object item);

        long bytesOf(Object item);

        double createdAtOf(Object item);

        /** Lấy ra (xoá) gói ở ĐẦU hàng đợi của biên; trả null nếu rỗng. */
        Object pollHead(int edgeId);

        long pendingBytes(int edgeId);

        int pendingItems(int edgeId);
    }

    /** Nhận số liệu mỗi tick (N6 gắn MetricsLogger vào đây). */
    public interface TickObserver {
        void onTick(TickRecord record);
    }

    // =====================================================================
    // Kết quả
    // =====================================================================

    /** Số liệu của MỘT tick. */
    public static final class TickRecord {
        public final long tick;
        public final double nowSeconds;
        public final long capacityBytes;
        public final int onlineEdges;
        /** Dữ liệu mới sinh ra trên các biên đang online trong tick. */
        public final long newDataBytes;
        public final long newDataSentBytes;
        /** Phần dữ liệu mới bị drop (metric droppedNewDataBytes theo hợp đồng). */
        public final long newDataDroppedBytes;
        /** Dữ liệu sinh ra trên biên OFFLINE, được đưa vào backlog trong tick. */
        public final long backlogEnqueuedBytes;
        /** Ngân sách byte mà policy được phép dùng cho backlog (đã trừ dữ liệu mới với nhóm A). */
        public final long backlogBudgetBytes;
        public final long backlogSentBytes;
        public final int backlogSentItems;
        /** Tổng backlog còn tồn (mọi biên) SAU tick. */
        public final long backlogPendingBytes;

        TickRecord(long tick, double nowSeconds, long capacityBytes, int onlineEdges,
                   long newDataBytes, long newDataSentBytes, long newDataDroppedBytes,
                   long backlogEnqueuedBytes, long backlogBudgetBytes,
                   long backlogSentBytes, int backlogSentItems, long backlogPendingBytes) {
            this.tick = tick;
            this.nowSeconds = nowSeconds;
            this.capacityBytes = capacityBytes;
            this.onlineEdges = onlineEdges;
            this.newDataBytes = newDataBytes;
            this.newDataSentBytes = newDataSentBytes;
            this.newDataDroppedBytes = newDataDroppedBytes;
            this.backlogEnqueuedBytes = backlogEnqueuedBytes;
            this.backlogBudgetBytes = backlogBudgetBytes;
            this.backlogSentBytes = backlogSentBytes;
            this.backlogSentItems = backlogSentItems;
            this.backlogPendingBytes = backlogPendingBytes;
        }
    }

    /** Kết quả tổng hợp một lần chạy. N6 có thể dùng trực tiếp hoặc tự tính thêm từ TickObserver. */
    public static final class Result {
        private final String experimentId;
        private final String policyName;
        private final long ticks;
        private final double tickSeconds;
        private final long capacityBytesPerTick;
        private final long newDataGeneratedOnlineBytes;
        private final long newDataSentBytes;
        private final long newDataDroppedBytes;
        private final long backlogEnqueuedBytes;
        private final long backlogSentBytes;
        private final long backlogSentItems;
        private final long backlogRemainingBytes;
        private final long backlogRemainingItems;
        private final double[] backlogDelaysSorted;
        private final Map<Integer, Long> backlogSentBytesByEdge;
        private final Map<Integer, Long> backlogRemainingBytesByEdge;

        Result(String experimentId, String policyName, long ticks, double tickSeconds,
               long capacityBytesPerTick, long newDataGeneratedOnlineBytes, long newDataSentBytes,
               long newDataDroppedBytes, long backlogEnqueuedBytes, long backlogSentBytes,
               long backlogSentItems, long backlogRemainingBytes, long backlogRemainingItems,
               double[] backlogDelays, Map<Integer, Long> sentByEdge, Map<Integer, Long> remainingByEdge) {
            this.experimentId = experimentId;
            this.policyName = policyName;
            this.ticks = ticks;
            this.tickSeconds = tickSeconds;
            this.capacityBytesPerTick = capacityBytesPerTick;
            this.newDataGeneratedOnlineBytes = newDataGeneratedOnlineBytes;
            this.newDataSentBytes = newDataSentBytes;
            this.newDataDroppedBytes = newDataDroppedBytes;
            this.backlogEnqueuedBytes = backlogEnqueuedBytes;
            this.backlogSentBytes = backlogSentBytes;
            this.backlogSentItems = backlogSentItems;
            this.backlogRemainingBytes = backlogRemainingBytes;
            this.backlogRemainingItems = backlogRemainingItems;
            double[] d = backlogDelays.clone();
            Arrays.sort(d);
            this.backlogDelaysSorted = d;
            this.backlogSentBytesByEdge = Collections.unmodifiableMap(new TreeMap<>(sentByEdge));
            this.backlogRemainingBytesByEdge = Collections.unmodifiableMap(new TreeMap<>(remainingByEdge));
        }

        public String getExperimentId() { return experimentId; }
        public String getPolicyName() { return policyName; }
        public long getTicks() { return ticks; }
        public double getTickSeconds() { return tickSeconds; }
        public long getCapacityBytesPerTick() { return capacityBytesPerTick; }
        public long getNewDataGeneratedOnlineBytes() { return newDataGeneratedOnlineBytes; }
        public long getNewDataSentBytes() { return newDataSentBytes; }
        public long getNewDataDroppedBytes() { return newDataDroppedBytes; }
        public long getBacklogEnqueuedBytes() { return backlogEnqueuedBytes; }
        public long getBacklogSentBytes() { return backlogSentBytes; }
        public long getBacklogSentItems() { return backlogSentItems; }
        public long getBacklogRemainingBytes() { return backlogRemainingBytes; }
        public long getBacklogRemainingItems() { return backlogRemainingItems; }
        public Map<Integer, Long> getBacklogSentBytesByEdge() { return backlogSentBytesByEdge; }
        public Map<Integer, Long> getBacklogRemainingBytesByEdge() { return backlogRemainingBytesByEdge; }

        /** Độ trễ (giây) của từng gói backlog đã gửi = thời điểm gửi − thời điểm sinh, đã sắp tăng dần. */
        public double[] getBacklogDelaysSorted() { return backlogDelaysSorted.clone(); }

        public double getAvgBacklogDelaySeconds() {
            if (backlogDelaysSorted.length == 0) return 0.0;
            double s = 0;
            for (double v : backlogDelaysSorted) s += v;
            return s / backlogDelaysSorted.length;
        }

        public double getMaxBacklogDelaySeconds() {
            return backlogDelaysSorted.length == 0 ? 0.0 : backlogDelaysSorted[backlogDelaysSorted.length - 1];
        }

        /** Phân vị theo nearest-rank, p trong (0, 100]; ví dụ 95 → độ trễ đuôi P95. */
        public double getBacklogDelayPercentileSeconds(double p) {
            if (!(p > 0 && p <= 100)) throw new IllegalArgumentException("p phải trong (0, 100]");
            if (backlogDelaysSorted.length == 0) return 0.0;
            int rank = (int) Math.ceil(p / 100.0 * backlogDelaysSorted.length);
            return backlogDelaysSorted[Math.max(0, rank - 1)];
        }

        /** Tỉ lệ dữ liệu mới bị drop trên tổng dữ liệu mới sinh ra khi biên online. */
        public double getNewDataDropRatio() {
            return newDataGeneratedOnlineBytes == 0 ? 0.0
                    : (double) newDataDroppedBytes / newDataGeneratedOnlineBytes;
        }

        /** Mức dùng dung lượng trung tâm = (dữ liệu mới đã gửi + backlog đã gửi) / (capacity × số tick). */
        public double getCapacityUtilization() {
            double total = (double) capacityBytesPerTick * ticks;
            return total == 0 ? 0.0 : (newDataSentBytes + backlogSentBytes) / total;
        }

        @Override
        public String toString() {
            return "Result{experimentId=" + experimentId + ", policy=" + policyName + ", ticks=" + ticks
                    + ", newGenerated=" + newDataGeneratedOnlineBytes + ", newSent=" + newDataSentBytes
                    + ", newDropped=" + newDataDroppedBytes + ", backlogEnqueued=" + backlogEnqueuedBytes
                    + ", backlogSent=" + backlogSentBytes + ", backlogRemaining=" + backlogRemainingBytes
                    + ", avgDelay=" + getAvgBacklogDelaySeconds() + ", maxDelay=" + getMaxBacklogDelaySeconds()
                    + ", utilization=" + getCapacityUtilization() + "}";
        }
    }

    // =====================================================================
    // Trạng thái engine
    // =====================================================================

    private final ExperimentConfig cfg;
    private final SendingPolicy policy;
    private final ConnectivityView connectivity;
    private final BacklogStore store;
    private final TickObserver observer;
    private final List<Integer> edgeIds;
    private final boolean runnerPollsSelected;

    /**
     * @param cfg                 kịch bản (sẽ validate)
     * @param policy              policy MỚI cho lần chạy này (hợp đồng §4.10)
     * @param connectivity        trạng thái kết nối theo thời gian
     * @param store               kho backlog theo biên
     * @param observer            nhận số liệu mỗi tick, có thể null
     * @param edgeIds             id các biên theo đúng id module disruption dùng; null → 0..numEdgeNodes-1
     * @param runnerPollsSelected true: runner poll item đã chọn (hợp đồng §1); false: policy tự poll
     */
    public TickEngine(ExperimentConfig cfg, SendingPolicy policy, ConnectivityView connectivity,
                      BacklogStore store, TickObserver observer, List<Integer> edgeIds,
                      boolean runnerPollsSelected) {
        if (cfg == null || policy == null || connectivity == null || store == null) {
            throw new IllegalArgumentException("cfg, policy, connectivity, store không được null");
        }
        cfg.validate();
        this.cfg = cfg;
        this.policy = policy;
        this.connectivity = connectivity;
        this.store = store;
        this.observer = observer;
        this.runnerPollsSelected = runnerPollsSelected;

        int n = cfg.getTopologyConfig().getNumEdgeNodes();
        List<Integer> ids = new ArrayList<>();
        if (edgeIds == null) {
            for (int i = 0; i < n; i++) ids.add(i);
        } else {
            ids.addAll(edgeIds);
            Collections.sort(ids);
        }
        if (ids.size() != n || new HashSet<>(ids).size() != n) {
            throw new IllegalArgumentException("edgeIds phải có đúng " + n + " id khác nhau");
        }
        this.edgeIds = Collections.unmodifiableList(ids);
    }

    public TickEngine(ExperimentConfig cfg, SendingPolicy policy, ConnectivityView connectivity,
                      BacklogStore store, TickObserver observer) {
        this(cfg, policy, connectivity, store, observer, null, DEFAULT_RUNNER_POLLS_SELECTED);
    }

    // =====================================================================
    // Chạy
    // =====================================================================

    public Result run() {
        policy.reset(); // hợp đồng §4.10: mỗi lần chạy mới

        final TopologyConfig topo = cfg.getTopologyConfig();
        final long capacity = topo.getCapacityBytesPerTick();
        final double tickSeconds = topo.getTickSeconds();
        final long ticks = cfg.getTotalTicks();
        final int itemBytes = cfg.getItemBytes();
        final long newItemsPerEdge = cfg.getNewItemsPerEdgePerTick();
        if (newItemsPerEdge > Integer.MAX_VALUE) {
            throw new IllegalStateException("Số gói mới mỗi biên mỗi tick quá lớn: " + newItemsPerEdge);
        }
        final long newBytesPerEdge = newItemsPerEdge * itemBytes;
        final boolean policyAllocatesNew = cfg.getPolicyType().allocatesNewDataItself();

        long totNewGenerated = 0, totNewSent = 0, totNewDropped = 0;
        long totEnqueued = 0, totBacklogSent = 0, totBacklogItemsSent = 0;
        List<Double> delays = new ArrayList<>();
        Map<Integer, Long> sentByEdge = new TreeMap<>();
        for (int e : edgeIds) sentByEdge.put(e, 0L);

        for (long k = 0; k < ticks; k++) {
            final double now = k * tickSeconds;

            // 1) Áp dụng transition đến hạn TRƯỚC khi đọc trạng thái (thiết kế disruption §4).
            connectivity.advanceTo(now);

            // 2) Phân loại biên; dữ liệu sinh ra khi OFFLINE → backlog (cũ); khi ONLINE → dữ liệu mới.
            Set<Integer> onlineSet = new HashSet<>();
            Map<Integer, BacklogQueue> queues = new TreeMap<>(); // §4.4: chỉ biên online, id tăng dần
            long newDataBytes = 0;
            long enqueuedThisTick = 0;
            for (int e : edgeIds) {
                if (connectivity.isOnline(e)) {
                    onlineSet.add(e);
                    queues.put(e, store.queueOf(e));
                    newDataBytes += newBytesPerEdge;
                } else {
                    for (long i = 0; i < newItemsPerEdge; i++) {
                        store.enqueue(e, itemBytes, now);
                    }
                    enqueuedThisTick += newBytesPerEdge;
                }
            }

            // 3) Hai nhóm thứ tự gọi (§4.12) — KHÔNG dùng chung nhánh.
            long newSent;
            long newDropped;
            long backlogBudget;
            List<?> selected;
            long selectedBytes;
            if (!policyAllocatesNew) {
                // FIFO / Rate-Limit / Round-Robin: dữ liệu mới đi TRƯỚC.
                newSent = Math.min(newDataBytes, capacity);
                newDropped = newDataBytes - newSent;                    // §4.3: drop
                backlogBudget = Math.max(0L, capacity - newSent);       // §1/§4.9: chặn ở 0
                selected = policy.select(now, backlogBudget, queues, newDataBytes);
                selectedBytes = checkSelection(selected, backlogBudget, onlineSet, k);
            } else {
                // Priority: select() TRƯỚC với capacity nguyên vẹn, dữ liệu mới nhận phần còn lại.
                backlogBudget = capacity;
                selected = policy.select(now, capacity, queues, newDataBytes);
                selectedBytes = checkSelection(selected, capacity, onlineSet, k);
                long remainingForNew = Math.max(0L, capacity - selectedBytes);
                newSent = Math.min(newDataBytes, remainingForNew);
                newDropped = newDataBytes - newSent;                    // §4.3 / §4.12 bước 3
            }

            // 4) Gửi toàn bộ danh sách trả về, rồi poll (nếu runner chịu trách nhiệm).
            for (Object item : selected) {
                int e = store.edgeOf(item);
                delays.add(now - store.createdAtOf(item));
                sentByEdge.merge(e, store.bytesOf(item), Long::sum);
            }
            if (runnerPollsSelected) {
                pollSelected(selected, k);
            }

            totNewGenerated += newDataBytes;
            totNewSent += newSent;
            totNewDropped += newDropped;
            totEnqueued += enqueuedThisTick;
            totBacklogSent += selectedBytes;
            totBacklogItemsSent += selected.size();

            if (observer != null) {
                long pending = 0;
                for (int e : edgeIds) pending += store.pendingBytes(e);
                observer.onTick(new TickRecord(k, now, capacity, onlineSet.size(),
                        newDataBytes, newSent, newDropped, enqueuedThisTick,
                        backlogBudget, selectedBytes, selected.size(), pending));
            }
        }

        long remBytes = 0, remItems = 0;
        Map<Integer, Long> remainingByEdge = new TreeMap<>();
        for (int e : edgeIds) {
            long b = store.pendingBytes(e);
            remainingByEdge.put(e, b);
            remBytes += b;
            remItems += store.pendingItems(e);
        }
        double[] d = new double[delays.size()];
        for (int i = 0; i < d.length; i++) d[i] = delays.get(i);

        return new Result(cfg.getExperimentId(), policy.name(), ticks, tickSeconds, capacity,
                totNewGenerated, totNewSent, totNewDropped, totEnqueued, totBacklogSent,
                totBacklogItemsSent, remBytes, remItems, d, sentByEdge, remainingByEdge);
    }

    // =====================================================================
    // Kiểm tra hợp đồng của policy
    // =====================================================================

    /**
     * Kiểm tra danh sách policy trả về theo hợp đồng §1: không null, mỗi item thuộc một biên đang
     * online (§4.4), tổng byte <= ngân sách (và danh sách rỗng khi ngân sách <= 0).
     * Vi phạm → ném IllegalStateException ngay (lỗi policy, không được âm thầm bỏ qua).
     *
     * @return tổng byte các item được chọn
     */
    private long checkSelection(List<?> selected, long budget, Set<Integer> onlineSet, long tick) {
        String where = " [policy=" + policy.name() + ", tick=" + tick + "]";
        if (selected == null) {
            throw new IllegalStateException("select() trả null — hợp đồng §1 yêu cầu không bao giờ null" + where);
        }
        if (budget <= 0 && !selected.isEmpty()) {
            throw new IllegalStateException("capacity <= 0 nhưng select() vẫn trả item — hợp đồng §1" + where);
        }
        long total = 0;
        for (Object item : selected) {
            if (item == null) {
                throw new IllegalStateException("select() trả danh sách chứa null" + where);
            }
            int e = store.edgeOf(item);
            if (!onlineSet.contains(e)) {
                throw new IllegalStateException("select() chọn item của biên " + e
                        + " không online/không có trong queues — hợp đồng §4.4" + where);
            }
            long b = store.bytesOf(item);
            if (b <= 0) {
                throw new IllegalStateException("item có kích thước <= 0" + where);
            }
            total += b;
        }
        if (total > budget) {
            throw new IllegalStateException("Tổng byte policy chọn (" + total + ") vượt ngân sách ("
                    + budget + ") — hợp đồng §1" + where);
        }
        return total;
    }

    /**
     * Poll từng item đã chọn khỏi đầu hàng đợi biên của nó và đối chiếu bằng tham chiếu. Nếu
     * không khớp nghĩa là: policy đã tự poll (mâu thuẫn §1 với §4.5/§5), hoặc policy trả item
     * không theo thứ tự đầu hàng đợi của biên → báo lỗi rõ ràng thay vì làm lệch dữ liệu.
     */
    private void pollSelected(List<?> selected, long tick) {
        for (Object item : selected) {
            int e = store.edgeOf(item);
            Object removed = store.pollHead(e);
            if (removed != item) {
                throw new IllegalStateException("Poll không khớp item đã chọn ở biên " + e
                        + " [policy=" + policy.name() + ", tick=" + tick + "]. Nguyên nhân thường gặp: "
                        + "policy đã tự poll (đặt runnerPollsSelected=false) hoặc trả item không theo "
                        + "thứ tự đầu hàng đợi của biên.");
            }
        }
    }
}
