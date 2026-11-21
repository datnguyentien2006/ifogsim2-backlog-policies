# Script demo — Backfill scheduling trên fog/edge (Đề tài 2)

Tài liệu này là **kịch bản demo từng bước** cho thư mục dự án. Mỗi bước đều có:
**lệnh chính xác** → **kết quả mong đợi** → **cần nói gì với người xem**. Các khái niệm quan
trọng được giải thích ngay tại chỗ (trong khối `> **Khái niệm:**`).

Thứ tự demo gợi ý (≈ 12–15 phút, có thể cắt bớt):

1. Preflight môi trường → 2. Build → 3. Một lần chạy (CSV) → 4. SelfTest →
5. **Web demo (phần chính)** → 6. (tuỳ chọn) Full grid → 7. Phân tích bảng + đồ thị →
8. Kết luận & fairness →

> **Quan trọng — đọc trước:** thư mục đã có **sẵn dữ liệu** `results/runs/` (200 lần chạy). Vì
> vậy bước 6 (chạy lại full grid ~20–40 phút) **không bắt buộc** — có thể bỏ qua và đi thẳng từ
> bước 5 sang bước 7 (bảng/đồ thị đã sinh sẵn). Chỉ chạy grid nếu muốn chứng minh "chạy lại ra
> đúng số cũ".

Tất cả lệnh Windows chạy từ **gốc repo** `C:\MMT_261`. Bản Linux/macOS tương ứng được ghi kèm
ở các bước có mirror (`./sim/run.sh`).

---

## 0. Từ điển khái niệm (nắm trước khi nói)

Đọc/kể phần này nếu người nghe chưa biết mô hình. Mỗi khái niệm cũng được nhắc lại tại bước dùng nó.

| Khái niệm | Ý nghĩa trong đề tài này |
|---|---|
| **Fog/edge computing** | Nhiều máy chủ ở rìa mạng (edge server) sinh dữ liệu và gửi về **một trung tâm thu nhận** (receiving center). Trung tâm có **năng lực hữu hạn**. |
| **Fresh data (dữ liệu tươi)** | Dữ liệu *đang* sinh ra liên tục (luồng Poisson). Luôn phải được phục vụ nếu không muốn trễ tăng. |
| **Backfill (dữ liệu tồn đọng)** | Dữ liệu sinh ra **trong lúc đường truyền edge→center bị đứt**. Khi link nối lại, nó tạo thành một *cơn lũ backlog* phải được gửi bù. |
| **Xung đột cốt lõi** | Fresh và backfill cùng tranh **một** băng thông trung tâm. Lịch phục vụ quyết định: ưu tiên backlog → fresh bị trễ; ưu tiên fresh → backlog trễ/nghẽn. |
| **Token bucket** | Trung tâm cấp cho bộ lập lịch `tokens = round(C·ΔT)` đơn vị mỗi tick. Với `C = 1200 u/s`, `ΔT = 0.1 s` → **120 token/tick**. Không ai được phát nhiều hơn số token này. |
| **ΔT (decision tick)** | Nhịp quyết định = 0.1 s. Mỗi tick: cập nhật link → sinh dữ liệu → nhận đơn vị đã gửi → policy cấp phát → ghi snapshot. |
| **ρ (load factor)** | Tổng tốc độ dữ liệu tươi / năng lực trung tâm: `ρ = Σλi / C`. `ρ<1` = nhẹ, `ρ=0.7` = danh nghĩa, `ρ≥1.1` = **quá tải có hệ thống** (không thể drain hết). |
| **Imbalance** | Cách chia tốc độ dữ liệu giữa các edge: `EVEN` (đều), `ZIPF` (lệch theo luật zipf), `DOMINANT` (edge-0 phát 60% toàn bộ). |
| **Outage (đứt link)** | Link edge→center xuống rồi lên. Xác suất mất **và** thời gian đứt đều **cấu hình được theo phân bố**. |
| **SCATTERED** | Mỗi edge đứt/khôi phục **độc lập**, rải rác theo thời gian (mỗi edge một tham số riêng). |
| **COHORT (storm)** | Cứ **120 s** một lần, ~**50%** các edge đang kết nối **đứt cùng một thời điểm** → "bão tái kết nối": nhiều backlog lớn đổ về trung tâm cùng lúc. |
| **storms-only (`cohortStormsOnly=true`)** | Trong chế độ COHORT, edge đã nối lại **chỉ đợi cơn bão kế tiếp** để đứt lại (không tự đứt theo đồng hồ SCATTERED). ⟹ đúng nghĩa "nhiều biên cùng tái kết nối", **một chuỗi outage mỗi edge**. Đây là **mặc định**. |
| **storms + post-storm scattered (`cohortStormsOnly=false`)** | Biến thể COHORT sát thực tế: sau khi cả nhóm cùng đứt và tái kết nối, mỗi edge chờ **`cohortScatterDelay`** giây (mặc định **90 s**) rồi **quay lại nhịp SCATTERED riêng** — tức sau cơn bão vẫn xuất hiện các đợt đứt rải rác. Bật ở UI: `COHORT mode = storms + post-storm scattered`. |
| **Chính sách (policy)** | Hàm *thuần quyết định*: nhìn `tokens`, danh sách edge (link + số đơn vị tươi/tồn) và đưa ra cấp phát. **Không** đụng vào đường truyền. |
| **Fair fight (cùng đầu vào)** | 4 policy nhận **y hệt** mọi thứ mỗi lần chạy: cùng luồng sinh (seed theo cảm biến), cùng timeline outage, cùng token, cùng seed. Chỉ *quyết định* khác nhau. |
| **Bảo toàn (conservation)** | `đã gửi + còn lại = đã sinh` cho cả fresh lẫn backfill. |
| **Seed / determinism** | Cùng `(scenario, seed)` → **byte-identical** dữ liệu khi chạy lại (trong cùng engine). Web mirror seed cùng cấu trúc nhưng RNG khác → giống *về cấu trúc*, **không** byte-identical với Java. |
| **Jain index (backfill)** | Chỉ số công bằng theo tỉ lệ *đã gửi/đã sinh của backfill, tính theo từng edge*. Edge bị bỏ đói tính tỉ lệ 0. Càng gần 1 càng công bằng. |

**Bốn policy (bảng xương sống của buổi demo):**

| key | Tên | Một câu |
|---|---|---|
| `fifo` | FIFO | Thứ tự sinh toàn cục — dữ liệu tươi xếp hàng sau backlog. |
| `rr` | Round-robin | Chia token **đều, theo lượt** cho các edge đang kết nối & có dữ liệu; phần dư **được chia lại** chứ không bỏ phí. |
| `ratelimit` | Rate-limit | Backfill gửi ở **tốc độ cố định** `R=0.5·C = 600 u/s` (**60 token/tick**), bất kể backlog lớn cỡ nào; phần còn lại → fresh. |
| `newfirst` | New-data-first | Fresh ưu tiên trước; backlog chỉ được một **quota nhỏ cố định** `q=0.05·C` (**6 token/tick**), *được giữ riêng* nên không bị luồng fresh nhấn chìm. |

---

## 1. Preflight — kiểm tra môi trường (≈ 30 giây)

```powershell
# Java 21 (repo-local, vì iFogSim2 upstream cần API SequencedCollection)
.\tools\jdk-21.0.12.1+1\bin\java.exe -version

# Python + matplotlib (chỉ cần cho bước 7)
python -c "import sys, matplotlib; print('py', sys.version.split()[0], 'mpl', matplotlib.__version__)"

# Node (chỉ cần cho 2 test web headless)
node --version
```

**Kết quả mong đợi (đúng máy này):** `openjdk 21.0.12.1`, `py 3.14.5 mpl 3.11.2`, `v24.21.0`.

> **Nói gì:** "Toàn bộ dự án là Java thuần (không Maven) build bằng `javac`, đóng gói kèm JDK 21
> trong `tools/` vì iFogSim2 upstream cần API Java 21. Web demo là JS thuần, không dependency."

Nếu Java/Python/Node khác máy: chỉ cần **JDK 21** là bắt buộc; Python/Node chỉ để vẽ hình / test.

---

## 2. Build (≈ 30–60 giây lần đầu)

```powershell
.\sim\build.ps1
```

**Kết quả mong đợi:**
```
[build] compiling iFogSim2-vendor ...        # chỉ lần đầu
[build] OK -> C:\MMT_261\sim\build\classes
```

> **Khái niệm:** `build.ps1` biên dịch **iFogSim2 vendor** một lần rồi **cache** (`sim/build/vendor`),
> sau đó biên dịch framework của mình vào `sim/build/classes`. Classpath = `vendor` + `classes` +
> 11 jar trong `iFogSim2-vendor/jars`. Chạy lại lần sau gần như tức thì (bỏ qua vendor).

---

## 3. Demo một lần chạy đơn lẻ (≈ 10 giây) — cho thấy "một ô dữ liệu"

```powershell
.\sim\run.ps1 run -Policy fifo -Scenario base -Seed 0
```

**Kết quả mong đợi:** dòng `DONE run_base_s0_fifo end_sim_time=655.000` và sinh ra 2 file *trong
đúng thư mục scenario*:

```
results\runs\base\run_base_s0_fifo_run.csv     <- 1 dòng tổng kết
results\runs\base\run_base_s0_fifo_tick.csv    <- chuỗi thời gian 0.1 s
```

Xem nhanh file tổng kết (đổi tên cột cho dễ đọc):

```powershell
Import-Csv results\runs\base\run_base_s0_fifo_run.csv |
  Format-List policy,scenario,N,rho,genBack,delivBack,backDelayMean,backlogPeak,outages,jainBackfill
```

> **Khái niệm — cột trong `*_run.csv` (32 cột):**
> - `N, rho, C, imbalance, outageMode` — cấu hình scenario.
> - `genNew, genBack` — tổng đơn vị *sinh ra* (tươi / tồn đọng).
> - `delivNew, delivBack` — tổng đơn vị *đã gửi tới* trung tâm.
> - `leftNew, leftBack` — *còn lại* cuối cửa sổ. (Bảo toàn: `deliv + left = gen`.)
> - `newDelay*` / `backDelay*` — độ trễ (`Mean/P50/P95/P99`) của đơn vị tươi / tồn đọng.
> - `backlogArea, backlogPeak` — diện tích & đỉnh tồn đọng (mức độ nghẽn).
> - `outages, restoresWithBacklog` — số lần đứt link & số lần nối lại *có kèm backlog*.
> - `drains, drainMean, drainMax` — số "đợt xả" và thời gian xả trung bình/đại diện.
> - `jainBackfill` — chỉ số công bằng.
>
> `*_tick.csv` là chuỗi theo 0.1 s: `t, grantedNew, grantedBack, down, backlogNewTot, backlogBackTot`.

> **Quan trọng cần nhớ:** bản sửa gần đây làm `run.ps1 run` ghi **vào thư mục scenario**
> (`results/runs/<scenario>/`), cùng layout với `grid` — trước đây nó ghi phẳng.

---

## 4. Demo SelfTest — "đặc tả chạy được" (≈ 1 phút)

```powershell
.\sim\run.ps1 test                    # mặc định: Part A + B(base) + C, ~1 phút
```

`.\sim\run.ps1 test` chỉ chạy chế độ mặc định. Muốn chọn `fast`/`full` (hoặc mode mặc định) thì gọi
`java` trực tiếp với **đúng classpath** (chạy từ gốc repo):

```powershell
$repo = (Get-Location).Path
$cp = "$repo\sim\build\classes;$repo\sim\build\vendor;" + (
      (Get-ChildItem -Recurse "$repo\iFogSim2-vendor\jars" -Filter *.jar |
       ForEach-Object { $_.FullName }) -join ";")
$java = ".\tools\jdk-21.0.12.1+1\bin\java.exe"

& $java -Xmx2g -cp $cp backfill.run.SelfTest fast    # chỉ Part A, < 1 giây
& $java -Xmx2g -cp $cp backfill.run.SelfTest         # mặc định
& $java -Xmx2g -cp $cp backfill.run.SelfTest full    # thêm B trên storm / rho=1.1 / DOMINANT
```

**Kết quả mong đợi (máy này):**
```
[SelfTest] ALL CHECKS PASSED (114867 hard checks, 0 warning(s), output ...)   # mặc định
[SelfTest] ALL CHECKS PASSED (353095 hard checks, 4 warning(s), output ...)   # full
```
(4 warning ở `full` là cảnh báo *chất lượng bao phủ* của scenario `st_dominant` ngắn — không phải lỗi.)

> **Khái niệm — 3 tầng của SelfTest:**
> - **A. Hợp đồng policy** (hàng đợi tổng hợp, không mô phỏng): không cấp cho edge **mất kết nối**;
>   không vượt **token**; không cấp **quá** số hàng đang có; đúng thứ tự **cũ nhất trước**; và đúng
>   luật chia của từng policy. Có "ca mẫu" cố định: sau khi edge-0 nối lại với 500 backlog thì
>   `fifo (120,0)`, `rr (30,90)`, `ratelimit (60,60)`, `newfirst (6,114)`.
> - **B. Bất biến đầu-cuối** trên chạy thật: **B1 cùng đầu vào** (4 policy thấy cùng generation/outage),
>   **B2 bảo toàn**, **B3 trần năng lực** (`deliv ≤ C·(dataEnd−dataStart)+1 tick`), **B4 policy tách biệt**
>   (không phải chạy trùng policy), **B5 tốc độ đúng** (ratelimit ≤ R, newfirst ≤ q, và *thực sự chạm*
>   ngưỡng), **B6 khép kín grant↔arrival** + chuỗi tick đều nhịp.
> - **C. Determinism**: chạy lại cùng `(scenario, policy, seed)` → `_run.csv`/`_tick.csv` **byte-identical**.
>
> Exit code: `0` = đạt, `1` = fail (dừng ở check fail đầu tiên), `2` = sai cách gọi. WARN **không** làm fail.

> **Nói gì:** "Đây là điểm mạnh học thuật — thay vì tin vào vài con số, khung tự kiểm chứng *công bằng*
> (cùng đầu vào) và *đúng đắn* (bảo toàn, trần năng lực) bằng 114k+ phép kiểm."

---

## 5. WEB DEMO — phần chính (≈ 5–7 phút)

```text
Mở web\index.html bằng trình duyệt (nháy đúp, chạy file:// — KHÔNG cần server, KHÔNG npm).
```

> **Khái niệm:** `web/` là **bản mô phỏng gương** của engine Java: cùng mô hình, cùng 4 policy,
> cùng pipeline, nhưng có hoạt ảnh + đồ thị + so sánh 4 policy ngay trong trình duyệt. Engine
> deterministic theo `(scenario, seed)`.

### 5.1 Giao diện tổng quan
- **Cột trái:** *Scenario* (knob mô hình — gồm *Outage regime* SCATTERED/COHORT; khi chọn
  **COHORT** hiện thêm ô **COHORT mode** (storms-only / storms + post-storm scattered) và, nếu
  chọn scattered, ô **Post-storm scatter delay (s)**, mặc định 90) · *Policy* (chọn + mô tả)
  · *Playback* (nút + số liệu) · *Batch compare + export*.
- **Cột phải:** canvas **Topology**, 3 đồ thị, và log **Link events**.

### 5.2 Topology — nói về 2 loại bộ đệm và LED
Bấm **▶ Play**. Chỉ vào từng thẻ edge:
- **LED xanh = đang kết nối**, **LED đỏ = LINK DOWN** (đường đứt nét đỏ).
- **Thanh xanh dương = bộ đệm tươi (`Qn`)**, **thanh cam = bộ đệm tồn đọng (`Qb`)**.
- Các **điểm** chạy về hộp trung tâm (xanh = tươi, cam = tồn đọng); link đứt thì điểm dừng.

> **Nói gì:** "Chú ý: backlog (cam) chỉ *phình ra trong lúc link đỏ*, rồi đổ về trung tâm khi nối lại."

### 5.3 Đồ thị — kể câu chuyện 4 policy
1. **Queued units** (xanh lá) với vùng nền **đỏ** = lúc *có edge đứt*; backlog (cam) khớp nhịp outage.
2. **Granted transmissions** — cột xếp theo loại. Chọn `ratelimit` → thấy **dải cam phẳng 60/tick**
   khi còn backlog (chữ ký của tốc độ cố định). Chọn `newfirst` → **dải cam mỏng 6/tick**.
3. **Delivery latency** — trung bình trượt 2 s: xanh = tươi, đỏ = tồn đọng.

### 5.4 Kịch bản demo bắt buộc (bảng "để chứng minh")
| Muốn cho thấy | Thiết lập trên UI |
|---|---|
| FIFO vs rate-limit khi nối lại | N=6, ρ=0.7, EVEN, SCATTERED; động vào lúc có backlog lớn (20k+): FIFO "khóa" fresh sau lưng drain, rate-limit giữ dải fresh đầy |
| Tốc độ phẳng của rate-limit | cùng cấu hình, speed **20×**, xem đồ thị 2 |
| **newfirst đói backlog** | policy `newfirst`: backlog leo gần như tuyến tính cả run (đồ thị 1), fresh phẳng ~0 |
| **Bão tái kết nối (COHORT)** | Outage regime = **COHORT**, `COHORT mode = storms-only`: tại t≈120, 240, ... ~nửa số LED **đỏ đồng loạt**; nhiều backlog đỉnh cùng lúc |
| **Bão rồi mới rải rác (COHORT + scattered)** | Outage regime = **COHORT**, `COHORT mode = storms + post-storm scattered`, delay **90 s**: ngay sau mỗi cơn bão thấy các LED **đỏ lẻ tẻ** xuất hiện lại (edge quay về nhịp riêng) |
| **Quá tải** | ρ = **1.1**: mọi thứ bão hoà; batch-compare cho thấy **rate-limit thắng** (backfill delay thấp nhất *và* gửi nhiều nhất) |

> **Khái niệm — hai chế độ con của COHORT (`cohortStormsOnly`):**
> - `true` (**storms-only**, mặc định): edge nối lại **không** tự đứt theo đồng hồ SCATTERED, chỉ
>   nằm chờ cơn bão kế → đúng "nhiều biên cùng tái kết nối", **một chuỗi outage mỗi edge**.
> - `false` (**storms + post-storm scattered**): mỗi edge nối lại rồi chờ **`cohortScatterDelay`**
>   giây (mặc định **90 s**) thì **quay về nhịp SCATTERED riêng** — mô phỏng đúng thực tế là sau
>   cơn bão chung vẫn còn các đợt đứt rải rác của từng edge.
>
> Cả hai chế độ có mặt ở **cả Java (`SimParams.cohortStormsOnly` / `cohortScatterDelay`) lẫn web
> mirror**. Chọn `SCATTERED` thì hai ô này tự ẩn (không liên quan).

### 5.5 Batch compare — so 4 policy cùng seed
Trong card **Batch compare + export**, bấm **Run 4 policies (same seed)**. Bảng hiện (mỗi policy):
`delivNew, delivBack, newDelay mean, backDelay mean/p95, backlog peak, drains (mean,max), Jain`.
Dưới bảng có biểu đồ cột nhóm. Nút **CSV** xuất đúng định dạng cột `*_run.csv` của Java.

> **Nói gì:** "Cùng seed, cùng cấu hình — chỉ khác quyết định — nên bảng này là so sánh công bằng."

> **Tuỳ chọn (chứng minh tính gương):** chạy 2 test headless:
> ```powershell
> node web\test\engine_test.js      # -> PARITY OK: generations + outage timelines identical ...
> node web\test\variants_test.js    # -> ALL VARIANTS PASS
> ```

---

## 6. (Tuỳ chọn) Full grid — tái tạo toàn bộ 200 lần chạy (≈ 20–40 phút)

> **Có thể BỎ QUA** nếu chỉ demo: `results/runs/` đã có sẵn. Chỉ chạy để chứng minh "tái lập".

```powershell
.\sim\run.ps1 grid -Seeds 5
```

**Kết quả mong đợi:** tiến trình `[k/200] OK run_<scenario>_s<seed>_<policy> ...`, kết thúc
`finished: 200 ok, 0 failed`. Mỗi lần chạy ~6–11 s, **mỗi run một JVM riêng** (để iFogSim/CloudSim
không dính trạng thái tĩnh giữa các run). Mỗi run còn ghi 1 file `.log` trong thư mục scenario
(`*.log` bị gitignore).

> **Khái niệm — 10 scenario ứng với 3 trục khảo sát (+ base/storm):**
> - Trục A — **số biên**: `N4, N10, N16`.
> - Trục B — **tải so với năng lực**: `rho0.4, rho0.9, rho1.1`.
> - Trục C — **chênh lệch tồn đọng**: `imbalance_zipf, imbalance_dominant`.
> - Neo chung: `base` (N=6, ρ=0.7, EVEN, SCATTERED). Chế độ outage: `storm` (COHORT).
>
> ⟹ 10 scenario × 4 policy × 5 seed = **200 `_run.csv` (+200 `_tick.csv`) = 400 file**.

Lọc nhanh một lát cắt:

```powershell
.\sim\run.ps1 grid -Policy newfirst -Scenario imbalance_dominant -Seeds 5
```

**Kiểm chứng tái lập (byte-identical):** chạy lại 1 ô rồi so sánh với bản đã commit:
```powershell
.\sim\run.ps1 run -Policy rr -Scenario storm -Seed 4 -OutDir results\repro
fc.exe results\runs\storm\run_storm_s4_rr_run.csv results\repro\storm\run_storm_s4_rr_run.csv
# -> FC: no differences encountered
```

---

## 7. Phân tích → bảng + đồ thị (≈ 10–20 giây)

```powershell
python analysis\analyze.py
```

**Kết quả mong đợi:**
```
figures -> C:\MMT_261\analysis\figures
tables  -> C:\MMT_261\analysis\tables.md
```

Mở `analysis\tables.md`: **8 bảng metric × 10 scenario**, mỗi bảng 4 cột policy (trung bình 5 seed):
`backDelayMean, backDelayP95, newDelayMean, delivBack, backlogPeak, backlogArea, drainMean, jainBackfill`.

Mở thư mục `analysis\figures\` — 10 ảnh `.png` (delay, backlog, drain, fairness, tick storm…).

> **Khái niệm:** `analyze.py` chỉ **đọc** `results/runs/*/*_run.csv`, gộp theo `(scenario, policy)`
> bằng trung bình qua seed, rồi vẽ. Nó **không** chạy lại mô phỏng — đổi số ở bảng nghĩa là đổi dữ liệu.

---

## 8. Kết luận để trình bày + công bằng (nói theo `docs/REPORT.md` §5–6)

- **Tải cân bằng (base):** 3 policy phục vụ gần nhau; **FIFO xả backlog nhanh nhất**; `rr`/`ratelimit`
  **giảm ~½ độ trễ fresh** (đổi lại backlog trễ hơn ~13–16%); `newfirst` giữ fresh ~phẳng nhưng
  **không bao giờ drain hết** (~40% backlog được gửi).
- **Quét N (4/10/16):** mọi policy cải thiện khi N tăng; tại N=16 `rr` gần như hành xử như `fifo`.
- **Tải nặng (ρ=1.1):** **`ratelimit` thắng rõ** — vừa backfill delay thấp nhất (25.7 s vs FIFO 35.0,
  RR 53.7), vừa **gửi nhiều backlog nhất** (119.9k). `rr` là lựa chọn sai khi quá tải (drain 108 s).
- **Chênh lệch tồn đọng (ZIPF/DOMINANT):** sau khi sửa lỗi kế toán budget, "RR sụp đổ" cũ **biến mất**;
  RR chỉ còn chậm hơn ~13% backfill delay — "lỗi cấu trúc" thực ra là **lỗi tham số** (tái dùng token).
- **Bão (COHORT, storms-only):** cả 3 policy phục vụ **xả hết** backlog (`leftBack=0`); **FIFO có backfill
  delay thấp nhất** (12.1 s); `rr`/`ratelimit` đổi ~2 s backlog lấy fresh tốt hơn ~1.8×; `newfirst` gửi 74%.
- **Công bằng (Jain, §5.3):** 3 policy phục vụ **phẳng 0.95–1.00** mọi regime; **`newfirst` là bộ phân biệt**
  (min **0.699** tại N16, tối đa 0.901 tại ρ=0.4). ⟹ với backfill, **công bằng về *độ trễ*** mới là trục
  phân biệt, không phải công bằng thông lượng.

> **Khái niệm — vì sao Jain "phẳng" ở 3 policy phục vụ?** Khi backlog không bị cắt bởi đuôi cửa sổ chạy,
> policy cuối cùng gửi **hết** dữ liệu của mỗi edge ⟹ tỉ lệ hoàn thành bằng nhau, dù *thời điểm* rất khác.

---

## 9. (Tuỳ chọn) "Khung tái dùng được" — thêm policy trong 3 chỗ

Mở `sim\src\backfill\policy\SchedulingPolicy.java` — policy = **hàm quyết định thuần**, chỉ nhận
`now, tokens, List<EdgeQueueView>, AllocationSink, SimParams`; **không** được đụng đường truyền.

Theo `docs/ADD_POLICY.md`: (1) viết class implement `SchedulingPolicy`, (2) đăng ký vào
`SchedulingPolicy.standardPolicies()`, (3) chạy:

```powershell
.\sim\run.ps1 run  -Policy largest -Scenario base -Seed 0
.\sim\run.ps1 grid -Policy largest -Seeds 5
```

`SelfTest` tự quét **mọi** policy trong `standardPolicies()` nên policy mới được kiểm công bằng/bảo toàn ngay.

> **Nói gì:** "Bộ khung không chỉ so 4 policy — nó là *nền so sánh* cho policy bất kỳ, luôn cùng đầu vào
> và cùng tải."

---

## 10. Xử lý sự cố nhanh (bảng tra)

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `javac`/`java` không tìm thấy | Dùng đúng JDK repo-local (`.\tools\jdk-21.0.12.1+1\bin\...`); hoặc cài JDK 21 và sửa `JAVA_HOME`. |
| `OutOfMemoryError` khi ρ=1.1 | Đã bật `-Xmx2g`; máy nhỏ hơn thì `$env:BACKFILL_JVM="-Xmx4g"` (hoặc `BACKFILL_JVM` cho `run.sh`). |
| `python analysis\analyze.py` báo thiếu `matplotlib` | `pip install matplotlib` (chỉ cần cho bước 7). |
| Web demo trắng/không chạy | Mở đúng `web\index.html` bằng trình duyệt (chạy `file://`, không cần server). |
| Số web không khớp số Java | **Đúng như thiết kế**: web và Java khác RNG nên giống *cấu trúc*, không byte-identical. Muốn số khớp thì so trong cùng engine (chạy lại Java, hoặc SelfTest Part C). |
| `run.ps1 grid` rất lâu | 200 JVM riêng, ~6–11 s/run. Bỏ qua được — dữ liệu đã có sẵn. |

---

## 11. Trình tự "copy-paste" chạy hết (ngoài grid nặng)

```powershell
# 1) môi trường
.\tools\jdk-21.0.12.1+1\bin\java.exe -version
python -c "import matplotlib; print('mpl', matplotlib.__version__)"
node --version

# 2) build
.\sim\build.ps1

# 3) một ô dữ liệu
.\sim\run.ps1 run -Policy fifo -Scenario base -Seed 0

# 4) self-test
.\sim\run.ps1 test

# 5) mở web\index.html bằng trình duyệt  (thủ công)
# 6) test web headless
node web\test\engine_test.js
node web\test\variants_test.js

# 7) phân tích
python analysis\analyze.py
```

> Kết thúc: mở `docs\REPORT.md` (kết luận) và `analysis\tables.md` (số liệu) để chốt. Nếu muốn tái tạo
> toàn bộ, chạy thêm `.\sim\run.ps1 grid -Seeds 5` trước bước 7.

---

## 12. Checklist 60 giây trước khi trình bày

- [ ] `.\sim\build.ps1` in `[build] OK`.
- [ ] `results\runs\` có **10 thư mục** (base, N4, N10, N16, rho0.4, rho0.9, rho1.1, imbalance_zipf,
      imbalance_dominant, storm), mỗi thư mục **20 `_run.csv` + 20 `_tick.csv`**.
- [ ] `web\index.html` mở được; bấm Play thấy LED/backlog chạy.
- [ ] `analysis\tables.md` + `analysis\figures\*.png` đã sinh.
- [ ] `docs\REPORT.md`, `docs\MODEL.md`, `docs\ADD_POLICY.md`, `docs\WEB.md`, `docs\SELFTEST.md` mở sẵn.
- [ ] Nhớ 3 con số "đắt": **rate-limit thắng khi ρ=1.1 (25.7 s)** · **FIFO thắng khi storm (12.1 s)** ·
      **newfirst đói backlog (Jain 0.70–0.90)**.
