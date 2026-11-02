# Chương: Độ tin cậy của kiểm thử và kết quả mô phỏng

Chương này trình bày cách kiểm thử mô phỏng, kết quả chạy hiện tại, mức độ bắt lỗi của bộ kiểm thử,
và những gì chưa kiểm thử được. Mọi con số trong chương được đo trực tiếp từ repo ở commit hiện tại.

## 1. Phương pháp

Mô phỏng là một hệ thống nhiều thành phần, nên không thể tin vào kết quả chỉ vì chương trình chạy xong.
Bộ kiểm thử dùng hai tầng:

- **Tầng đơn vị (stub):** kiểm tra một hàm đo lường trên một trường hợp có đáp số tính trước bằng tay.
  Tầng này nhanh (không dựng CloudSim) và cho biết chính xác hàm có tính đúng hay không.
- **Tầng bất biến trên toàn bộ run:** chạy mô phỏng thật (bốn chính sách, 90 s, bốn edge) rồi kiểm tra các
  bất biến mà mọi run phải thoả mãn. Kiểm tra đọc kết quả từ `run.csv` và `tick.csv` như một hộp đen, nên không
  phụ thuộc vào cách cài đặt bên trong.

Bộ kiểm thử phía web (`web/test/`) kiểm tra mô hình JavaScript theo cách tương tự, nhưng chỉ kiểm tra nội bộ web.

## 2. Bộ kiểm thử

### 2.1. SelfTest của Java (`sim/src/backfill/run/SelfTest.java`)

| Nhóm | Kiểm tra | Cách kiểm | Bắt được lỗi gì |
|---|---|---|---|
| Stub | Jain = 2/3 khi một edge bị bỏ đói trong ba edge có backlog | Tính tay: r = (1, 1, 0), J = 4/6 | Hàm Jain tính sai, bộ đếm delivered không tăng |
| Stub | Jain = 1 khi mọi edge được phục vụ đủ | r = (1, 1, 1) | Công thức sai hệ số |
| Stub | Jain nằm trong [0, 1] | Kiểm tra giới hạn | Tràn số, công thức sai |
| Equal input | genNew, genBack, outages, restores giống nhau giữa bốn chính sách | So sánh `run.csv` | Chính sách làm đổi luồng dữ liệu hoặc lịch mất kết nối |
| Bảo toàn | delivered + left = generated, riêng cho dữ liệu mới và backlog | Kiểm tra trên `run.csv` | Mất hoặc tạo dữ liệu ngoài ý muốn |
| Sức chứa | Dữ liệu giao không vượt C × (dataEnd − dataStart) | Kiểm tra trên `run.csv` | Cấp phát vượt năng lực trung tâm |
| Ngân sách | Mỗi tick không cấp quá 120 đơn vị | Kiểm tra trên `tick.csv` | Chính sách vượt ngân sách mỗi tick |
| Link down | Hàng đợi của edge đang down không bao giờ giảm | So sánh `e<i>_new + e<i>_back` giữa các tick khi `e<i>_up = 0` | Chính sách lấy dữ liệu từ edge đang mất kết nối |
| Không vacuous | Kịch bản có tick mất kết nối; chính sách rate-limit có lấy backfill | Đếm trên `tick.csv` | Kiểm tra đúng nhưng không có gì để kiểm tra |
| Rate-limit | Backfill mỗi tick của rate-limit ≤ R = 60 | `grantedBack` trên `tick.csv` | Rate-limit cấp vượt R |
| Tái lập | Hai run cùng seed trong cùng JVM cho file byte-giống-nhau | So sánh byte của `run.csv` và `tick.csv` | Trạng thái tĩnh rò rỉ giữa các run, RNG không được seed đúng |
| Khác biệt | Bốn chính sách cấp phát khác nhau | So sánh delivBack hoặc backDelayMean | Chính sách bị cài đặt giống hệt nhau |

Tổng cộng 51 dòng `ok:` khi mọi kiểm tra đạt.

### 2.2. Kiểm thử phía web (`web/test/`)

- `engine_test.js`: bốn chính sách trên cùng cấu hình (N = 6, ρ = 0.7, 300 s, seed 7). Kiểm tra số đơn vị sinh ra,
  số lần mất kết nối giống nhau, và bảo toàn khối lượng.
- `variants_test.js`: bốn cấu hình khác (COHORT, DOMINANT, ρ = 1.1, COHORT + ZIPF + N = 16), mỗi cấu hình 300 s.
  Mỗi cấu hình phải có ít nhất một lần mất kết nối và một lượng backfill dương.

## 3. Kết quả chạy hiện tại

| Kiểm thử | Lệnh | Kết quả |
|---|---|---|
| Build | `bash sim/build.sh` | OK |
| SelfTest | `bash sim/run.sh test` | ALL CHECKS PASSED, 51 kiểm tra, khoảng 20 s kể cả build |
| Web smoke và parity nội bộ | `node web/test/engine_test.js` | PARITY OK |
| Web variants | `node web/test/variants_test.js` | ALL VARIANTS PASS |

## 4. Độ nhạy của bộ kiểm thử: lỗi cố ý

Để kiểm tra bộ kiểm thử có thật sự bắt lỗi hay không, nhóm đưa lỗi cố ý vào một bản sao của repo (không sửa repo
chính) rồi chạy SelfTest.

| Lỗi cố ý | Nằm ở đâu | SelfTest trước khi bổ sung | SelfTest hiện tại |
|---|---|---|---|
| Bỏ bộ đếm `deliveredBackA` (bug số 1 của đề tài) | `UnitRegistry.onArrived` | **Vẫn PASS**: jain = 1.0 trên mọi run | **FAIL** ở "jain stub: one starved edge out of three gives 2/3 (got 1.0)" |
| Rate-limit cấp 2R mỗi tick | `RateLimitPolicy` | Không có kiểm tra này | **FAIL** ở "rate-limit cap: backfill granted per tick <= R = 60" |
| Coi mọi edge là đang kết nối khi chọn grant | `SchedulerEntity`, hàm `connected()` | Không có kiểm tra này | **FAIL** ở "no grant to a link-down edge" (bắt được ở chính sách `rr`) |

Ghi chú về lỗi thứ ba: trong kịch bản này chính sách FIFO không lấy dữ liệu từ edge đang down, vì ngân sách 120 đơn vị
mỗi tick đã được dùng hết cho dữ liệu cũ hơn của các edge khác. Vì vậy kiểm tra không bắt được lỗi ở FIFO nhưng bắt được
ở RR. Kiểm tra chạy trên cả bốn chính sách, nên lỗi vẫn bị phát hiện. Kết quả này cho thấy độ bắt lỗi phụ thuộc kịch bản:
một kịch bản khác có thể cần thiết để bắt lỗi ở một chính sách cụ thể.

Lỗi thứ nhất cho thấy vấn đề quan trọng nhất của bản SelfTest ban đầu: nó chạy đúng mọi kiểm tra mà nó có, nhưng không
có kiểm tra nào về hàm Jain. Trong kịch bản đó mọi backlog đều được giao hết, nên jain = 1 là đúng cả khi code đúng
lẫn khi bộ đếm đã hỏng. Hai trường hợp không phân biệt được nếu không có trường hợp đáp số đã biết trước.

## 5. Độ tin cậy của kết quả mô phỏng

- **Tái lập trong cùng JVM:** hai run cùng seed cho file giống hệt nhau (kiểm tra "reproducible" ở trên).
  Chưa kiểm tra tái lập giữa các JVM khác nhau. Plan yêu cầu mỗi run chạy trong một JVM riêng, nên kết quả của
  ExperimentRunner cần được kiểm tra lại khi nó có trên `main`.
- **Nhiễu do số lần mất kết nối:** trong cấu hình SelfTest (90 s, 4 edge) mỗi run chỉ có 3 lần mất kết nối. Tổng thời
  gian mất kết nối vì vậy rất nhiễu: trong cùng một cấu hình, tổng số backlog sinh ra có thể khác nhau nhiều giữa các seed. Vì thế kết quả phải
  lấy trung bình trên 5 seed (theo plan) và báo cáo độ lệch.
- **Bảo toàn khối lượng** đúng cho cả 4 chính sách trên mọi run được kiểm tra.

## 6. Phần chưa kiểm thử được và hạn chế

1. **Parity web ↔ Java chưa đạt.** Hai bên có cùng công thức mô hình nhưng dùng bộ sinh số ngẫu nhiên khác nhau và
   warmup khác nhau (50 s so với 5 s). Chi tiết và số liệu đo nằm trong `docs/WEB.md`, mục 7.
2. **Lỗi bị nuốt im lặng ở device.** Khi `EdgeServerDevice.applyGrant` nhận grant lúc link đang down, nó khôi phục các
   unit vào hàng đợi mà không báo lỗi (xem comment "Should not happen"). Grant đó vẫn làm hàng đợi giảm trong
   `tick.csv` tại thời điểm quyết định, nên kiểm tra "queues never shrink while down" là chỗ duy nhất bắt được lỗi này.
   Đề xuất: đếm hoặc ném lỗi khi nhánh này chạy, để lỗi có tín hiệu ngay trong log.
3. **RunMain và ExperimentRunner** chưa có trên `main` (thuộc N6), nên lệnh `run` và `grid` chưa được kiểm thử.
4. **Bộ kiểm thử chỉ kiểm tra đơn vị và bất biến.** Chưa có kiểm tra đúng-giá-trị cho từng chính sách với một kịch bản
   nhỏ đã tính tay. Đây là việc nên làm tiếp theo cho RR và NewFirst.

## 7. Các lỗi đã phát hiện và sửa trong quá trình kiểm thử

| Phát hiện | Cách sửa |
|---|---|
| SelfTest không có kiểm tra jain nên không bắt được bộ đếm hỏng | Thêm kiểm tra stub với đáp số 2/3 và 1 |
| Javadoc của SelfTest ghi có kiểm tra rate-limit nhưng code không có | Thêm kiểm tra rate-limit cap và sửa Javadoc khớp với code |
| Kiểm tra capacity dùng hằng số 1200 thay vì tham số `centerCapacity` | Đổi sang `p.centerCapacity` |
| Kiểm tra cột dựa trên chỉ số cứng, dễ lệch khi header đổi | Tra cột theo tên |
| `variants_test.js` chạy COHORT 120 s nên storm đầu tiên nằm sát cuối cửa sổ, backDelay = 0 ở mọi chính sách | Tăng lên 300 s và thêm kiểm tra không rỗng (phải có outage và backfill) |
| Chưa có kiểm tra link down và tái lập | Thêm, đã kiểm chứng bằng lỗi cố ý (mục 4) |

## 8. Ghi chú về cơ chế cấp phát

Plan gọi cơ chế cấp phát là "token bucket". Đọc mã cho thấy `SchedulerEntity` lấy `tokens = centerTokensPerTick()`
ở mỗi tick và không tích lũy phần dư sang tick sau. Đây là ngân sách cố định mỗi tick. Tên "token bucket" trong plan
nên được sửa thành "ngân sách mỗi tick" để khớp với mã.
