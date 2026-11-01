# TÀI LIỆU GIẢI THÍCH MÃ NGUỒN VÀ HƯỚNG DẪN THỰC NGHIỆM — PHÂN CÔNG N6
**Đồ án Mạng Máy Tính (CO3119) — HK261 — Đề tài 2**  
*Mô phỏng và so sánh chính sách gửi bù dữ liệu trên iFogSim2*  
*Người phụ trách: N6 — Lê Văn Nguyên Phúc (MSSV: 2412716)*

---

## 1. TỔNG QUAN VAI TRÒ VÀ KHU VỰC SỞ HỮU CỦA N6

Trong hệ thống mô phỏng `ifogsim2-backlog-policies`, **N6** chịu trách nhiệm toàn bộ vòng đo lường (Measurement & Accounting), hạ tầng xuất nhập kết quả (CSV Persistence), thiết kế không gian thí nghiệm (Scenario Grid), bộ điều phối chạy thực nghiệm hàng loạt (Experiment Runner), và module phân tích số liệu / trực quan hóa đồ thị (Analysis & Figures).

### Danh mục các tệp thuộc quyền sở hữu của N6:
1. `sim/src/backfill/metrics/UnitRegistry.java`: Trung tâm đo lường duy nhất của hệ thống, quản lý vòng đời từng đơn vị dữ liệu (`EgressUnit`), tính độ trễ (delay percentile bằng reservoir sampling), tích phân diện tích tồn đọng (`backlogArea`), đỉnh tồn đọng (`backlogPeak`), phát hiện thời gian xả tồn đọng (`drainMean`/`drainMax`), và tính chỉ số công bằng Jain (`jainBackfill`).
2. `sim/src/backfill/util/Csv.java`: Tiện ích ghi file CSV streaming hiệu năng cao, tự động escape dấu phẩy, hỗ trợ flush theo dòng và phân cách cột an toàn.
3. `sim/src/backfill/run/ScenarioGrid.java`: Lưới kịch bản thí nghiệm chuẩn gồm 10 kịch bản bao phủ 4 chiều: số lượng Edge Server ($N$), mức tải hệ thống ($\rho$), sự mất cân bằng tồn đọng (Imbalance), và chế độ gián đoạn (Outage Regime).
4. `sim/src/backfill/run/RunMain.java`: Điểm vào (CLI Entrypoint) cho một lần chạy đơn lẻ, nạp tham số từ `ScenarioGrid`, cô lập seed và cấu hình thư mục kết quả.
5. `sim/src/backfill/run/ExperimentRunner.java`: Bộ điều phối chạy hàng loạt 200 run (10 kịch bản $\times$ 4 chính sách $\times$ 5 seed), chạy mỗi lần trên một JVM độc lập với `-Xmx2g` để tránh rò rỉ trạng thái static của CloudSim và chống tràn bộ nhớ.
6. `analysis/analyze.py`: Kịch bản Python quét toàn bộ thư mục `results/runs/`, tính trung bình qua các seed, tạo bảng markdown `analysis/tables.md` và vẽ 10 biểu đồ phân tích trong `analysis/figures/`.
7. `results/`: Cấu trúc thư mục lưu trữ kết quả phân tầng theo kịch bản (`results/runs/<scenario>/`).

---

## 2. GIẢI THÍCH CHI TIẾT TOÀN BỘ CODE CỦA N6

### 2.1. `sim/src/backfill/metrics/UnitRegistry.java` (Trung tâm đo lường)

`UnitRegistry` là lớp `final` chứa các trường và phương thức tĩnh đóng vai trò là **Nguồn sự thật duy nhất (Single Source of Truth)** về toàn bộ dữ liệu ra/vào hệ thống.

#### a. Vòng đời dữ liệu và nguyên tắc đo tại đích (Destination-Only Accounting)
- **Khi sinh ra (`onGenerated(EgressUnit u)`)**: Chỉ được gọi bởi `EdgeServerDevice.sendUp`. Tại đây, unit được đăng ký vào bảng tra cứu tạm thời `Map<Integer, EgressUnit> live` theo `cloudletId`.
  - Nếu `u.isNew == true` (sinh khi mạng đang `LINK_UP`): tăng bộ đếm `generatedNew` toàn cục và `genNewEdgeA[edgeIndex]`.
  - Nếu `u.isNew == false` (sinh khi mạng đang `LINK_DOWN` - đây là định nghĩa chuẩn của dữ liệu tồn đọng/backlog): tăng `generatedBack` toàn cục và `genBackEdgeA[edgeIndex]`.
  - Cập nhật mốc thời gian bắt đầu sinh dữ liệu `simDataStart`.
- **Khi đến trung tâm (`onArrived(int cloudletId)`)**: Chỉ được gọi duy nhất bởi `CentralReceiverDevice` khi đơn vị dữ liệu đã thực sự truyền qua đường truyền mạng vật lý lên máy chủ trung tâm.
  - Rút unit ra khỏi `live` map.
  - Tính độ trễ end-to-end chính xác: $\text{latency} = \max(0, \text{now} - u.\text{genTime})$.
  - Cập nhật số gói đến đích: `deliveredNew++` nếu là dữ liệu mới; `deliveredBack++` và `deliveredBackA[u.edgeIndex]++` nếu là dữ liệu tồn đọng.
  - **Cạm bẫy chí mạng đã được xử lý**: Việc tăng `deliveredBackA[u.edgeIndex]++` cho từng edge là bắt buộc. Nếu quên dòng này, mảng `deliveredBackA` bằng 0 dẫn tới chỉ số công bằng `jainBackfill` luôn bằng 1.0 sai lệch trên toàn bộ 200 lượt chạy.

#### b. Đo độ trễ với Bounded-Memory Reservoir Sampling
Một lượt chạy mô phỏng 600 giây với $\rho = 1.1$ có thể sinh ra trên 600.000 đối tượng. Nếu lưu toàn bộ độ trễ vào danh sách để sắp xếp, JVM sẽ bị tràn bộ nhớ (OutOfMemoryError). 
- `UnitRegistry` sử dụng thuật toán **Reservoir Sampling** với kích thước hồ chứa cố định `RESERVOIR = 12_000` phần tử cho mỗi loại (`delaysNew` và `delaysBack`).
- Đối với $k$ phần tử đầu tiên ($k < 12.000$), nạp trực tiếp vào hồ chứa.
- Với phần tử thứ $i$ ($i \ge 12.000$), xác suất được đưa vào hồ chứa là $\frac{12.000}{i}$. Khi được chọn, nó thay thế ngẫu nhiên một vị trí cũ trong hồ chứa bằng bộ sinh ngẫu nhiên `reservoirRng` (được cách ly seed bằng công thức `seed * 7919L + 13`).
- Cuối lượt chạy (`endRun`), hàm `percentiles()` sắp xếp mảng 12.000 phần tử để trích xuất các phân vị $p_{50}$ (trung vị), $p_{95}$, và $p_{99}$ với độ chính xác thống kê cao mà chỉ tốn lượng RAM tối thiểu không đổi.

#### c. Quản lý trạng thái kết nối và thời gian xả tồn đọng (Drain Time Tracking)
- `onLinkChange(...)`: Được gọi khi một Edge Server chuyển đổi trạng thái kết nối (`LINK_UP` hoặc `LINK_DOWN`).
  - Khi một edge hồi phục kết nối (`up == true`) mà tại thời điểm đó hàng đợi backlog của nó vẫn còn dữ liệu (`backlogAtChange > 0`): tăng `restoresWithBacklog++`, đánh dấu `drainPendingA[edgeIndex] = true` và ghi lại mốc thời gian bắt đầu xả `drainStartA[edgeIndex] = now`.
  - Đếm tổng số lần đứt kết nối `outageCount++`.
- `onTick(...)`: Mỗi tick 0.1s sau khi `SchedulerEntity` hoàn tất cấp phát, hàm quét qua các edge có cờ `drainPendingA[i] == true`. Nếu cả hai hàng đợi `backlogNew[i] + backlogBack[i] == 0` (đã xả cạn hoàn toàn), hệ thống ghi nhận thời gian xả: $\Delta t_{\text{drain}} = \text{now} - \text{drainStartA}[i]$ vào danh sách `drainDurations`.
- Cuối mô phỏng, `drainMean` và `drainMax` được tính toán để đánh giá tốc độ hồi phục sau sự cố của từng chính sách.

#### d. Diện tích tồn đọng (`backlogArea`) và Đỉnh tồn đọng (`backlogPeak`)
- Mỗi tick, tổng tồn đọng toàn hệ thống được tính: $\text{tot} = \sum (\text{backlogNew}[i] + \text{backlogBack}[i])$.
- Diện tích tồn đọng (đơn vị: $\text{unit} \cdot \text{s}$) tích phân theo phương pháp hình chữ nhật:
  $$\text{backlogArea} += \text{tot} \times \Delta t_{\text{decision}}$$
- Đỉnh tồn đọng: $\text{backlogPeak} = \max(\text{backlogPeak}, \text{tot})$. Chỉ số này phản ánh dung lượng bộ đệm lớn nhất mà các edge cần có để không bị tràn bộ nhớ cục bộ.

#### e. Chỉ số công bằng Jain cho dữ liệu gửi bù (`jainDeliveredBack`)
Chỉ số công bằng Jain đánh giá mức độ đồng đều trong việc xả hàng tồn đọng giữa các edge server:
$$J = \frac{\left(\sum_{i \in S} x_i\right)^2}{|S| \cdot \sum_{i \in S} x_i^2}$$
Trong đó:
- Tỷ lệ phục vụ của edge $i$: $x_i = \frac{\text{deliveredBackA}[i]}{\text{genBackEdgeA}[i]}$.
- Tập hợp $S$: Chỉ bao gồm các edge thực sự có phát sinh dữ liệu tồn đọng trong phiên chạy ($\text{genBackEdgeA}[i] > 0$). Những edge không bị đứt kết nối (không có tồn đọng) được loại ra khỏi mẫu để không làm lệch chỉ số.
- Nếu không có edge nào bị tồn đọng hoặc chưa có dữ liệu nào được giao, hàm trả về 1.0.

#### f. Xuất hai tệp kết quả CSV chuẩn
- `_tick.csv`: Ghi lại chuỗi thời gian theo từng tick (0.1 giây):
  - Cột chung: `t, grantedNew, grantedBack, arrivals, arrivalsRate, backlogNewTot, backlogBackTot, backlogTot`.
  - Cột chi tiết per-edge mở rộng theo số edge $N$: `e<i>_new, e<i>_back, e<i>_up` (với $i = 0 \dots N-1$).
- `_run.csv`: Tệp tóm tắt gồm đúng 1 dòng dữ liệu với **32 cột chuẩn** quy định trong hợp đồng kiến trúc (định danh kịch bản, khối lượng sinh/giao/còn lại, độ trễ phân vị, thông số backlog, thông số gián đoạn và chỉ số Jain).

---

### 2.2. `sim/src/backfill/util/Csv.java` (Tiện ích ghi CSV)

Lớp `Csv` cung cấp cơ chế xuất dữ liệu tuần tự với các đặc điểm:
- **Tự động tạo thư mục**: Sử dụng `f.getParentFile().mkdirs()` bảo đảm thư mục đích tồn tại trước khi mở luồng ghi.
- **Escape an toàn**: Phương thức `row(Object... cells)` kiểm tra từng ô dữ liệu. Nếu trong chuỗi có dấu phẩy `,` (có thể làm lệch cột CSV khi đọc bằng Excel hay pandas), nó tự động thay thế bằng dấu chấm phẩy `;`.
- **Ghi chuỗi thô tối ưu (`rawLine`)**: Dùng cho `_tick.csv` để ghi trực tiếp chuỗi đã format sẵn mà không tốn chi phí boxing/unboxing từng phần tử trong vòng lặp tick.
- **Quản lý tài nguyên an toàn**: Cài đặt interface `Closeable`, tự động flush bộ đệm xuống đĩa khi kết thúc để tránh mất dữ liệu nếu mô phỏng gặp sự cố dừng đột ngột.

---

### 2.3. `sim/src/backfill/run/ScenarioGrid.java` (Lưới thí nghiệm)

Cung cấp cấu hình cho toàn bộ các thí nghiệm theo phương pháp quét đơn yếu tố (One-Factor-At-A-Time - OFAT):
- `base()`: Kịch bản chuẩn đóng vai trò điểm neo (anchor) với cấu hình mặc định:
  - Số trạm biên: $N = 6$.
  - Mức tải: $\rho = 0.7$ (tổng tải sinh mới bằng 70% băng thông tiếp nhận của trung tâm $C = 1200$ đơn vị/s).
  - Phân bố tải: `EVEN` (chia đều giữa các edge).
  - Chế độ gián đoạn: `SCATTERED` (các edge ngắt kết nối ngẫu nhiên độc lập theo phân phối thời gian down lognormal).
- `grid()`: Tập hợp 10 kịch bản thí nghiệm bao phủ đầy đủ 4 chiều nghiên cứu:
  1. `base`: Điểm neo so sánh.
  2. `N4`: Giảm xuống 4 edge server (tải mỗi edge tăng).
  3. `N10`: Tăng lên 10 edge server.
  4. `N16`: Tăng lên 16 edge server (kiểm tra tính mở rộng khi số nút lớn).
  5. `rho0.4`: Tải thấp ($\rho = 0.4$), hệ thống dư thừa tài nguyên.
  6. `rho0.9`: Tải tiệm cận bão hòa ($\rho = 0.9$), áp lực hàng đợi cao.
  7. `rho1.1`: Quá tải ($\rho = 1.1$), tốc độ sinh dữ liệu mới vượt quá công suất xử lý của trung tâm, kiểm tra khả năng sống sót của backfill.
  8. `imbalance_zipf`: Phân bố tải theo luật lũy thừa Zipf ($\alpha = 1.0$), một số edge có lưu lượng rất cao, các edge khác rất thấp.
  9. `imbalance_dominant`: Edge 0 chiếm 60% tổng lưu lượng toàn hệ thống, 5 edge còn lại chia nhau 40%.
  10. `storm`: Chế độ gián đoạn `COHORT` (bão ngắt kết nối - cứ 120s có 50% số edge đang online bị sập đồng loạt, sau đó hồi phục gần như cùng lúc), tạo ra xung đột tài nguyên cực đại khi tái kết nối.
- `byName(String name)`: Hàm tra cứu kịch bản từ tên chuỗi phục vụ CLI `RunMain`.

---

### 2.4. `sim/src/backfill/run/RunMain.java` (CLI chạy đơn lẻ)

Là điểm khởi đầu để thực thi một phiên mô phỏng đơn lẻ:
- **Cú pháp CLI**: `java backfill.run.RunMain <policyKey> <scenarioName> <seed> [outDir] [runNameOverride]`
- **Quy trình hoạt động**:
  1. Đọc và kiểm tra đối số dòng lệnh tối thiểu (3 tham số bắt buộc).
  2. Tìm kiếm `SimParams` trong `ScenarioGrid.byName(scenarioName)` và tạo một bản sao độc lập (`.copy()`) để không làm ảnh hưởng cấu hình gốc.
  3. Gán `seed` được truyền vào tham số cấu hình.
  4. Tự động sinh tên phiên chạy chuẩn: `run_<scenario>_s<seed>_<policy>`.
  5. Tạo thư mục kết quả `outDir` nếu chưa có.
  6. Khởi động mô phỏng thông qua `BackfillSim.run(p, policyKey, outDir, runName)`.
  7. In thông báo hoàn tất: `DONE run_<scenario>_s<seed>_<policy> end_sim_time=...`.

---

### 2.5. `sim/src/backfill/run/ExperimentRunner.java` (Trình điều phối chạy lưới hàng loạt)

Đây là công cụ then chốt phục vụ việc chạy tự động toàn bộ 200 lượt chạy thực nghiệm:
- **Không gian thí nghiệm**: 10 kịch bản $\times$ 4 chính sách (`fifo`, `rr`, `ratelimit`, `newfirst`) $\times$ 5 seed ($0 \dots 4$) = **200 runs**.
- **Cơ chế cô lập tiến trình (Separate JVM Process)**:
  - iFogSim2 và CloudSim sử dụng rất nhiều biến tĩnh (`static`) nội bộ (như `CloudSim.clock()`, bảng định danh thực thể, v.v.). Nếu chạy nhiều lần trong cùng một JVM, các trạng thái dư thừa từ run trước sẽ làm sai lệch tính ngẫu nhiên và vi phạm nguyên tắc **Equal-Input**.
  - `ExperimentRunner` sử dụng `ProcessBuilder` để khởi chạy mỗi run như một tiến trình con Java hoàn toàn mới:
    `java -Xmx2g -cp <classpath> backfill.run.RunMain <policy> <scenario> <seed> <dir> <runName>`
- **Cấu hình bộ nhớ `-Xmx2g`**: Bắt buộc cấp tối thiểu 2GB heap space cho mỗi process con để ngăn ngừa lỗi `OutOfMemoryError` khi chạy kịch bản $\rho = 1.1$ (số lượng object `EgressUnit` tích lũy lên tới hàng trăm nghìn).
- **Ghi nhận nhật ký và hiển thị tiến độ**:
  - Gộp luồng lỗi vào luồng xuất (`redirectErrorStream(true)`).
  - Xuất toàn bộ log ra tệp `results/runs/<scenario>/<runName>.log`.
  - In ra màn hình console dòng trạng thái trực quan:
    `[<done>/<total>] OK    <runName>   <thời gian thực hiện>s`

---

### 2.6. `analysis/analyze.py` (Phân tích dữ liệu & Sinh đồ thị)

Được viết bằng Python với `matplotlib` (chế độ không cần màn hình đồ họa `matplotlib.use("Agg")`):
- **Thu thập dữ liệu (`load_summaries`)**:
  - Tìm kiếm tất cả tệp `*_run.csv` trong các thư mục con của `results/runs/`.
  - Xử lý an toàn các tệp rỗng hoặc chưa hoàn tất (bỏ qua tệp hỏng thay vì dừng chương trình).
  - Gom nhóm dữ liệu theo cặp `(scenario, policy)` và tính giá trị trung bình qua các hạt giống (seed averaging).
- **Xuất bảng Markdown (`tables_md`)**:
  - Tự động sinh `analysis/tables.md` gồm 8 bảng đối chiếu 4 chính sách trên 10 kịch bản:
    1. `backDelayMean`: Độ trễ trung bình của dữ liệu gửi bù (s).
    2. `backDelayP95`: Phân vị 95% độ trễ gửi bù (s).
    3. `newDelayMean`: Độ trễ dữ liệu mới (s).
    4. `delivBack`: Tổng số đơn vị dữ liệu tồn đọng đã gửi đến trung tâm.
    5. `backlogPeak`: Đỉnh tồn đọng cao nhất (units).
    6. `backlogArea`: Tích phân diện tích tồn đọng (unit $\cdot$ s).
    7. `drainMean`: Thời gian xả tồn đọng trung bình sau khi nối lại kết nối (s).
    8. `jainBackfill`: Chỉ số công bằng Jain của dữ liệu gửi bù.
- **Trực quan hóa đồ thị (`grouped_bars` & `tick_figure`)**:
  - Biểu đồ thanh nhóm (Grouped Bar Charts): 5 đồ thị so sánh toàn diện 10 kịch bản cho các chỉ số quan trọng (`back_delay_mean.png`, `back_delay_p95.png`, `new_delay_mean.png` ở thang log, `backlog_peak.png`, `drain_mean.png`).
  - Biểu đồ chuỗi thời gian động lực học (Per-tick Time Series): 5 đồ thị biểu diễn biến thiên của tồn đọng tích lũy và lượng đơn vị cấp phát qua từng tick 0.1s cho các kịch bản tiêu biểu (`tick_base.png`, `tick_storm.png`, `tick_rho1.1.png`, `tick_imbalance_dominant.png`, `tick_imbalance_zipf.png`).

---

## 3. KẾT QUẢ KIỂM TRA ĐỐI CHIẾU VỚI YÊU CẦU ĐỒ ÁN

Code hiện tại của N6 đã được kiểm tra nghiêm ngặt thông qua bộ kiểm thử tự động `SelfTest` và kiểm chứng các bất biến hệ thống:

| STT | Yêu cầu / Bất biến kiểm tra | Tình trạng | Kết quả kiểm chứng thực tế |
|---|---|---|---|
| 1 | **Biên dịch sạch với JDK 21+** | **ĐẠT** | `pwsh sim/build.ps1` biên dịch thành công `[build] OK` sử dụng Temurin JDK 21.0.12. |
| 2 | **5 Nhóm bất biến SelfTest** | **ĐẠT** | Chạy `pwsh sim/run.ps1 test` in ra `[SelfTest] ALL CHECKS PASSED` (đạt 100% các bài test bảo toàn và ràng buộc năng lực). |
| 3 | **Bảo toàn dữ liệu (Conservation Law)** | **ĐẠT** | Kiểm tra $\text{gen} = \text{deliv} + \text{left}$ đúng từng tick và toàn phiên cho cả 4 chính sách. |
| 4 | **Bảo đảm Equal-Input** | **ĐẠT** | Cùng seed, hai chính sách khác nhau sinh ra chuỗi `arrivals` và số lần đứt kết nối `outages` giống nhau 100%. |
| 5 | **Bẫy Jain Metric (`deliveredBackA`)** | **ĐẠT** | `deliveredBackA[u.edgeIndex]++` hoạt động chính xác trong `onArrived()`. Kết quả Jain phản ánh đúng sự phân hóa giữa các chính sách thay vì bằng 1.0 cố định. |
| 6 | **Quản lý RAM và JVM isolation** | **ĐẠT** | `ExperimentRunner` cấp `-Xmx2g` và chạy riêng process, hoàn thành mượt mà, không gặp lỗi OutOfMemory. |
| 7 | **Đường dẫn và định dạng tệp CSV** | **ĐẠT** | Đủ 32 cột ở `_run.csv` và ma trận mở rộng theo $N$ ở `_tick.csv`. |
| 8 | **Pipeline phân tích dữ liệu** | **ĐẠT** | `python analysis/analyze.py` chạy trơn tru, sinh đầy đủ bảng `tables.md` và các tệp hình ảnh đồ thị trong `analysis/figures/`. |

---

## 4. HƯỚNG DẪN CHI TIẾT CÁCH TỰ CHẠY

Người dùng có thể thực hiện toàn bộ các thao tác biên dịch, kiểm thử, chạy mô phỏng và phân tích theo các bước dưới đây trên PowerShell (Windows) hoặc Bash (Linux/macOS).

### Bước 1: Kiểm tra môi trường
Yêu cầu máy tính đã cài đặt JDK 21 trở lên và Python 3 kèm các thư viện đồ họa:
```powershell
# Kiểm tra Java
java -version    # Phải từ 21.x trở lên

# Kiểm tra Python và cài đặt thư viện phân tích
pip install -r requirements.txt
# (Hoặc: pip install matplotlib pandas)
```

### Bước 2: Biên dịch toàn bộ dự án
Chạy script build (chỉ cần chạy một lần; lần đầu sẽ biên dịch vendor và cache lại):
```powershell
# Trên Windows PowerShell:
pwsh sim/build.ps1

# Trên Linux / macOS:
bash sim/build.sh
```
*Kết quả hiển thị: `[build] OK -> .../sim/build/classes`*

### Bước 3: Chạy kiểm thử tự động (SelfTest)
Trước khi chạy thí nghiệm thực tế, luôn chạy SelfTest để kiểm tra 5 nhóm bất biến:
```powershell
# Trên Windows PowerShell:
pwsh sim/run.ps1 test

# Trên Linux / macOS:
bash sim/run.sh test
```
*Kết quả mong đợi: `[SelfTest] ALL CHECKS PASSED`*

### Bước 4: Chạy một lượt mô phỏng đơn lẻ (Single Run)
Để quan sát nhanh một chính sách cụ thể trên một kịch bản:
```powershell
# Cú pháp: sim/run.ps1 run -Policy <fifo|rr|ratelimit|newfirst> -Scenario <tên_kịch_bản> -Seed <số_seed>

# Ví dụ 1: Chạy FIFO trên kịch bản base, seed 0
pwsh sim/run.ps1 run -Policy fifo -Scenario base -Seed 0

# Ví dụ 2: Chạy Round-Robin trên kịch bản bão tái kết nối (storm), seed 1
pwsh sim/run.ps1 run -Policy rr -Scenario storm -Seed 1

# Ví dụ 3: Chạy New-Data-First trên kịch bản tải tiệm cận bão hòa (rho0.9)
pwsh sim/run.ps1 run -Policy newfirst -Scenario rho0.9 -Seed 0
```
*Kết quả xuất hiện tại: `results/runs/<scenario>/run_<scenario>_s<seed>_<policy>_run.csv` và `_tick.csv`.*

### Bước 5: Chạy lưới thí nghiệm tự động (Batch Experiment Grid)
Để chạy toàn bộ không gian thí nghiệm qua `ExperimentRunner`:
```powershell
# Chạy toàn bộ lưới với 3 seed (mặc định để kiểm tra nhanh):
pwsh sim/run.ps1 grid -Seeds 3

# Chạy đủ bộ chuẩn 5 seeds (200 runs cho báo cáo chính thức):
pwsh sim/run.ps1 grid -Seeds 5

# Lọc chỉ chạy 1 chính sách cụ thể (ví dụ chỉ chạy New-First cho toàn bộ kịch bản):
pwsh sim/run.ps1 grid -Policy newfirst -Seeds 3

# Lọc chỉ chạy 1 kịch bản cụ thể (ví dụ kịch bản mất cân bằng zipf qua 4 chính sách):
pwsh sim/run.ps1 grid -Scenario imbalance_zipf -Seeds 5
```
*Mỗi run sẽ ghi nhật ký độc lập vào tệp `.log` tương ứng trong thư mục kết quả.*

### Bước 6: Phân tích số liệu và xuất đồ thị
Sau khi chạy xong các lượt mô phỏng, thực thi kịch bản phân tích Python:
```powershell
python analysis/analyze.py
```
*Kết quả sinh ra gồm có:*
- Tệp bảng tổng hợp: `analysis/tables.md`
- 10 tệp đồ thị PNG phân giải cao tại thư mục: `analysis/figures/`
  - `analysis/figures/back_delay_mean.png`
  - `analysis/figures/back_delay_p95.png`
  - `analysis/figures/new_delay_mean.png`
  - `analysis/figures/backlog_peak.png`
  - `analysis/figures/drain_mean.png`
  - `analysis/figures/tick_base.png`
  - `analysis/figures/tick_storm.png`
  - `analysis/figures/tick_rho1.1.png`
  - `analysis/figures/tick_imbalance_dominant.png`
  - `analysis/figures/tick_imbalance_zipf.png`

