# WEB.md — Demo trực quan và kiểm thử phía web

Tài liệu này mô tả bản demo web (`web/`) và các bộ kiểm thử của nó. Bản web là một **mô hình JavaScript
(`web/engine.js`) mô phỏng cùng bài toán với Java**. Số liệu chính thức của đề tài là kết quả của Java
(`sim/`, lưu ở `results/runs/`). Web dùng để minh họa hành vi và để đối chiếu, không phải nguồn số liệu chính.

## 1. Mở demo

Mở trực tiếp `web/index.html` bằng trình duyệt (double-click). Không cần server, không cần thư viện ngoài.

Lưu ý: vì mở bằng `file://`, trình duyệt sẽ chặn `fetch()` tới tệp cục bộ (CORS). Không thêm `fetch()` vào `web/`.

## 2. Cấu trúc thư mục

| File | Vai trò |
|---|---|
| `index.html`, `style.css` | Giao diện: bảng điều khiển, sơ đồ topology, biểu đồ, bảng so sánh |
| `engine.js` | Mô hình: device, scheduler, outage, bốn chính sách. Bản port của `sim/src/backfill/` |
| `app.js` | Vẽ canvas, biểu đồ, nút điều khiển, xuất CSV |
| `test/engine_test.js` | Smoke test và kiểm tra nội bộ: bốn chính sách cùng đầu vào, bảo toàn khối lượng |
| `test/variants_test.js` | Các chế độ khác: COHORT, ZIPF, DOMINANT, ρ = 1.1, N = 10 và 16 |

## 3. Tham số

Các control trên giao diện và tham số tương ứng trong `readConfig()` (`app.js`):

| Control | Tham số | Mặc định trong web | Ý nghĩa |
|---|---|---|---|
| `cfgN` | `numEdges` | 6 | Số edge server |
| `cfgRho` | `loadFactor` (ρ) | 0.7 | Tổng tải dữ liệu mới = ρ × C |
| `cfgImbalance` | `imbalance` | EVEN | Phân bố tải: EVEN, ZIPF, DOMINANT |
| `cfgZipf` | `zipfAlpha` | 1.0 | Chỉ dùng khi ZIPF |
| `cfgOutage` | `outageMode` | SCATTERED | SCATTERED (rơi ngẫu nhiên) hoặc COHORT (đợt dồn dập) |
| `cfgDuration` | `simDuration` | 300 s | Thời lượng dữ liệu mô phỏng |
| `cfgSeed` | `seed` | 7 | Hạt giống ngẫu nhiên |
| `cfgPolicy` | — | FIFO | Chính sách đang hiển thị |

Các tham số không có control trên giao diện chỉ đổi được trong mã nguồn (`defaultConfig()` trong `engine.js`):
`dropRatePerEdge` = 0.01/s, `downMean` = 20 s, `downCV` = 0.8, `cohortPeriod` = 120 s, `cohortFraction` = 0.5,
`cohortCV` = 0.15, `rateLimitFactor` = 0.5, `backfillQuotaFactor` = 0.05, `dominantShare` = 0.6.

## 4. Bốn chính sách

| Khóa | Tên hiển thị | Quy tắc một dòng |
|---|---|---|
| `fifo` | FIFO (generation order) | Cấp theo thứ tự sinh toàn cục, dữ liệu tồn đọng cũ đi trước |
| `rr` | Round-robin | Chia ngân sách vòng qua các edge còn dữ liệu |
| `ratelimit` | Rate-limit (R fixed) | Backfill cố định R = 0.5 × C mỗi tick, phần còn lại cho dữ liệu mới |
| `newfirst` | New-data-first (quota q) | Dữ liệu mới trước, backfill chỉ được tối đa q = 0.05 × C |

Cả bốn chính sách nhận cùng đầu vào trong một cấu hình: cùng hàng đợi, cùng luồng arrival, cùng lịch gián đoạn,
cùng ngân sách token (120 đơn vị mỗi tick, tức C × Δt với C = 1200 và Δt = 0.1 s).

## 5. Chế độ so sánh và xuất CSV

- **Compare** (`btnCompare`): chạy cả bốn chính sách với cùng cấu hình, hiển thị bảng so sánh (`compareTable`).
- **Xuất CSV so sánh** (`btnCsv`): một dòng cho mỗi chính sách. Các cột bắt đầu bằng `policy, n, rho, imbalance,
  outageMode, seed`. Tên tệp kết thúc bằng `_<outageMode>_s<seed>.csv`. Các cột này không trùng với `run.csv` của Java.
- **Xuất tick CSV** (`btnTickCsv`): chuỗi theo từng tick của chính sách đang chọn, tên tệp dạng
  `ticks_<policy>_s<seed>.csv`. Cột là `t, grantedNew, grantedBack, down, backlogNewTot, backlogBackTot`.
  Cột này **khác** `*_tick.csv` của Java (Java có thêm `arrivals`, `arrivalsRate`, `backlogTot` và các cột theo từng edge),
  nên hiện chưa dùng trực tiếp để so sánh từng dòng. Xem mục 7.

## 6. Kiểm thử

Chạy từ thư mục gốc của repo (đã thử với Node.js v22):

```bash
node web/test/engine_test.js      # kết thúc bằng: PARITY OK ... conservation holds.
node web/test/variants_test.js    # kết thúc bằng: ALL VARIANTS PASS
```

Ý nghĩa từng kiểm tra:

- `engine_test.js`: chạy bốn chính sách trên cùng cấu hình (N = 6, ρ = 0.7, seed = 7, 300 s). Kiểm tra số đơn vị
  sinh ra và số lần mất kết nối giống nhau giữa các chính sách, và bảo toàn khối lượng (delivered + left = generated).
- `variants_test.js`: bốn cấu hình khác (COHORT, DOMINANT, ρ = 1.1, COHORT + ZIPF + N = 16), mỗi cấu hình 300 s.
  Ngoài kiểm tra parity và bảo toàn, mỗi cấu hình còn phải tạo ra ít nhất một lần mất kết nối và một lượng backfill
  dương. Nếu không, parity và bảo toàn đều đúng một cách tầm thường và kiểm tra không có ý nghĩa.

Lưu ý: hai bộ kiểm thử này **chỉ kiểm tra nội bộ web**. Không bộ nào so sánh với Java.

## 7. Parity web ↔ Java: hiện trạng

Yêu cầu của đề tài là web và Java phải cho cùng hành vi trên cùng kịch bản và cùng seed. Phần dưới đây ghi
những gì đã đối chiếu trong mã và những gì đã đo, kèm kết luận hiện tại.

### 7.1. Đối chiếu mã

| Thành phần | Java | Web | Khớp? |
|---|---|---|---|
| Tốc độ arrival mỗi edge | `rateOfEdge()`: EVEN, ZIPF, DOMINANT | `rateOfEdge()`: cùng công thức | Có |
| Seed luồng arrival | `seed × 1 000 003 + edge × 131 + 17` | `seed × 1000003 + i × 131 + 17` | Có |
| Seed luồng outage | `seed × 8191 + 101` | `seed × 8191 + 101` | Có |
| Lognormal thời gian mất kết nối | σ² = ln(1 + CV²), μ = ln(mean) − σ²/2 | Cùng công thức | Có |
| Bộ sinh số ngẫu nhiên | Dựa trên `java.util.Random` | `mulberry32` | **Không** — chuỗi số khác nhau |
| Warmup trước khi có dữ liệu | 50 s (`Config.TRANSMISSION_START_DELAY`) | `WARMUP = 5` s | **Không** |
| Cửa sổ kết thúc | `stopAt = 50 + simDuration + 5` | `run(simDuration + WARMUP + 1)` | **Không** (do warmup) |
| Thời lượng mặc định | 600 s | 300 s | **Không** (chỉ là mặc định) |
| Seed mặc định | 42 | 7 | **Không** (chỉ là mặc định) |

Kết luận đối chiếu mã: công thức mô hình giống nhau, nhưng **bộ sinh số ngẫu nhiên và warmup khác nhau**.
Với bộ sinh khác nhau, tick.csv của hai bên không thể giống nhau từng dòng dù cùng seed.

### 7.2. Số liệu đo

Cấu hình đo: N = 4, seed = 5, simDuration = 90 s, chính sách FIFO, outage SCATTERED. Đây là cấu hình của SelfTest.
Java: `dataStart` = 50.0 s, `dataEnd` = 145.0 s. Web: để so sánh cùng khoảng dữ liệu, đo trên một bản sao tạm
với `WARMUP = 50` (không sửa `engine.js` trong repo).

| Đại lượng | Java | Web (WARMUP = 50, bản tạm) |
|---|---|---|
| genNew | 77 307 | 56 868 |
| genBack | 2 588 | 18 898 |
| Tổng sinh ra (genNew + genBack) | 79 895 | 75 766 |
| Số lần mất kết nối (outages) | 3 | 3 |
| delivBack | 2 588 | 0 |

Ghi chú về đo lường:
- Tổng số đơn vị sinh ra gần bằng nhau (lệch khoảng 5%). Lệch này chưa rõ nguyên nhân.
- Sự khác biệt giữa genNew và genBack là lớn. Mỗi run chỉ có khoảng 3 lần mất kết nối, nên tổng thời gian mất
  kết nối rất nhiễu. Chỉ một seed không đủ để so sánh các đại lượng này.
- Web với `WARMUP = 5` (cấu hình hiện tại) cho genNew = 60 265, genBack = 15 501, outages = 2 trên cùng cấu hình
  (thời gian dữ liệu khác, nên không so trực tiếp được).

### 7.3. Kết luận và đề xuất

Parity theo nghĩa "khớp tick.csv từng dòng" **chưa đạt** và không thể đạt nếu không đổi bộ sinh số ngẫu nhiên của web.
Có hai hướng để nhóm chọn (quyết định thuộc N1, vì `engine.js` là phần nền đã được nhóm chốt):

- **(A) Parity tuyệt đối.** Port `java.util.Random` (LCG 48-bit) và thuật toán của `ExponentialDistribution`
  sang JavaScript, đặt `WARMUP = 50` và cùng `simDuration`, `seed`. Khi đó tick.csv có thể so từng dòng.
  Chi phí: sửa `engine.js` và phải kiểm tra lại bộ sinh số bằng test riêng.
- **(B) Parity thống kê.** Giữ nguyên bộ sinh số. Đặt `WARMUP = 50` và cùng thời lượng. Chạy 5 seed cho mỗi
  cấu hình, so trung bình của các đại lượng chính (delivNew, delivBack, delay trung bình, backlog peak) với sai số
  định trước (ví dụ ±10%). Đây là cách so sánh đúng với bản chất ngẫu nhiên của mô hình.

Khuyến nghị: làm (B) trước vì ít thay đổi mã, sau đó làm (A) nếu thời gian cho phép.

## 8. Việc còn lại trước khi merge

1. N1 chọn hướng (A) hoặc (B) cho parity. Sau đó cập nhật mục 7 theo kết quả.
2. Nếu chọn (B): viết script so sánh 5 seed, lưu kết quả vào `results/` hoặc `analysis/`.
3. Nếu chọn (A): đổi `WARMUP` về 50 và thêm test cho bộ sinh số mới.
4. Chạy lại `engine_test.js` và `variants_test.js` sau mọi thay đổi trong `web/`.
