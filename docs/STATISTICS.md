# Phương pháp thống kê cho bộ kết quả đo

Tài liệu này mô tả cách `analysis/stats.py` biến 800 lần chạy mô phỏng thành các kết luận
có khoảng tin cậy và kiểm định. Kết quả được sinh tự động ở [`analysis/STATS.md`](../analysis/STATS.md);
diễn giải nằm trong [`REPORT_VI.md`](REPORT_VI.md).

## 1. Câu hỏi cần trả lời

1. Ở mỗi kịch bản (miền tải), chính sách nào tốt nhất theo từng tiêu chí, khác biệt có ý nghĩa
   thống kê hay không, và lớn bao nhiêu.
2. Khi một yếu tố thay đổi (số biên N, mức tải ρ), mỗi chỉ số tăng hay giảm bao nhiêu phần trăm.

Tiêu chí (cột của file run CSV, xem [`MODEL.md`](MODEL.md) §7): độ trễ dữ liệu mới
(`newDelayMean`, `newDelayP95`), độ trễ dữ liệu gửi bù (`backDelayMean`, `backDelayP95`),
đỉnh tồn đọng (`backlogPeak`), thời gian xả tồn đọng sau khi tái kết nối (`drainMean`),
tỷ lệ dữ liệu bù được giao trước khi hết giờ (`backDelivered = delivBack / genBack`) và chỉ số
công bằng Jain (`jainBackfill`).

## 2. Đơn vị quan sát là một lần chạy

Mỗi lần chạy mô phỏng 655 s: 50 s khởi động (iFogSim2 dựng topology, chưa sinh dữ liệu) bị
loại, 600 s cửa sổ đo, 5 s dư. Mỗi seed cho một lần chạy độc lập với các seed khác, nên các lần
chạy của cùng một (kịch bản, chính sách) là các quan sát độc lập, cùng phân phối. Đây là phương
pháp lặp độc lập (replication/deletion, Law & Kelton ch. 9).

Đại lượng cần ước lượng là **kỳ vọng của chỉ số trên một cửa sổ vận hành 600 s** (mô phỏng có
điểm kết thúc xác định), nên không cần giả định hệ đạt trạng thái dừng.

Không dùng độ trễ của từng gói làm mẫu. Các gói trong cùng một lần chạy phụ thuộc mạnh vào nhau
(cùng một đợt mất kết nối, cùng hàng đợi), nên khoảng tin cậy tính từ hàng trăm nghìn gói sẽ hẹp
giả tạo (pseudo-replication). Mỗi lần chạy chỉ đóng góp **một** giá trị cho mỗi chỉ số.

## 3. Số ngẫu nhiên chung và so sánh ghép cặp

Với cùng seed, bốn chính sách nhận **cùng luồng sinh dữ liệu** (seed sensor
`seed·1000003 + i·131 + 17`) và **cùng dòng thời gian mất kết nối** (seed `seed·8191 + 101`).
SelfTest B1 kiểm chứng điều này ở mỗi lần build. Đây là kỹ thuật số ngẫu nhiên chung
(common random numbers, CRN).

Vì vậy so sánh hai chính sách A, B là so sánh **ghép cặp theo seed**: với mỗi seed r,
`D_r = log Y_r(A) − log Y_r(B) = log(Y_r(A) / Y_r(B))`, rồi suy luận trên dãy `D_1..D_n`.
Vì `Var(D) = Var(log A) + Var(log B) − 2·Cov(log A, log B)` và CRN làm hiệp phương sai dương,
phương sai của D nhỏ hơn nhiều so với khi hai mẫu độc lập. `STATS.md` §6 báo cáo hệ số lợi
`(Var log A + Var log B) / Var(D)`, tức là số lần thiết kế độc lập phải chạy nhiều hơn để đạt
cùng độ chính xác.

**Vì sao dùng thang log.** Tác động của chính sách có dạng nhân. Ví dụ New-first làm độ trễ bù
dài gấp khoảng 7 lần FIFO, dù lần lặp đó có ít hay nhiều sự cố mất kết nối. Mức tuyệt đối của
chỉ số thì dao động rất mạnh giữa các lần lặp (§4), nên hiệu thô `Y(A) − Y(B)` vừa lệch vừa có
phương sai không đều, còn log-tỷ số đồng đều và gần chuẩn hơn nhiều. Trên dữ liệu thử, so với
hiệu thô, thang log giảm số so sánh bị Shapiro–Wilk bác bỏ tính chuẩn và số lần t-test với
Wilcoxon cho kết luận khác nhau. Thang log cũng khớp với cách báo cáo hiệu ứng dưới dạng tỷ số
(§5): khoảng tin cậy và kiểm định nằm trên **cùng một thang**, nên khoảng tin cậy không chứa 1
khi và chỉ khi t-test có ý nghĩa. Mọi chỉ số ở đây đều dương. Nếu có giá trị bằng 0, script tự
chuyển sang hiệu thô và ghi `scale = raw` trong `pairwise.csv`. Hiệu thô kèm p của nó vẫn được
giữ trong file (`diff`, `diff_lo`, `diff_hi`, `p_t_raw`) để đối chiếu.

Các bước quét (ρ ∈ {0.4, 0.7, 0.9, 1.1}, N ∈ {4, 6, 10, 16}) cũng ghép cặp theo seed giữa
các mức. Ghép cặp luôn hợp lệ khi cặp được định nghĩa trước khi xem dữ liệu; mức tương quan chỉ
quyết định lợi ích nhiều hay ít.

## 4. Số lần lặp

Grid gốc có 5 seed (`results/runs`, kèm chuỗi thời gian từng tick). Bộ bổ sung
`results/replications` chạy thêm seed 5–19 (chỉ giữ file run CSV), tổng **20 lần lặp cho mỗi ô
kịch bản × chính sách**, 800 lần chạy.

Lý do không dừng ở 5 seed:

* với n = 5, `t(0.975; 4) = 2.776` so với `t(0.975; 19) = 2.093` ở n = 20, và hệ số `1/√n`
  gấp đôi, nên khoảng tin cậy rộng gấp khoảng 2.65 lần;
* kiểm định Wilcoxon hai phía với n = 5 có p nhỏ nhất là `2/2⁵ = 0.0625 > 0.05`, tức là
  **không bao giờ** bác bỏ được giả thuyết bằng nhau, dù khác biệt lớn đến đâu. Với n = 20, p nhỏ
  nhất là `2/2²⁰ ≈ 2·10⁻⁶`.

Con số 20 là mức cân bằng giữa độ chính xác và chi phí tính toán. Một lần chạy `rho1.1` cần heap
2 GB, nên một máy 8 GB chỉ chạy được 2–3 lần song song. Độ chính xác thực tế đạt được nằm ở
`STATS.md` §5. Muốn khoảng hẹp hơn thì chạy thêm seed (lệnh ở §8) rồi chạy lại `stats.py`: mọi
bảng và hình tự cập nhật.

Đủ hay chưa được kiểm bằng tiêu chí độ chính xác tương đối của Law & Kelton: với sai số tương đối
mục tiêu γ, cần nửa độ rộng khoảng tin cậy `h ≤ γ/(1+γ)·|Ȳ|`, nên số lần lặp cần là
`n*(γ) = min{ n : t(0.975; n−1)·s/√n ≤ γ/(1+γ)·|Ȳ| }` với s ước lượng từ chính các lần lặp đã có.
`STATS.md` §5.1 báo cáo độ rộng tương đối đạt được và n* cho γ = 5% và 10%.

Mức tuyệt đối của một ô dao động rất mạnh, vì số lần và độ dài các đợt mất kết nối thay đổi
theo seed, nên n* của §5.1 thường lớn hơn nhiều so với 20. Điều đó chỉ có nghĩa là giá trị tuyệt
đối của ô (ví dụ "độ trễ bù trung bình của FIFO ở base") có sai số tương đối lớn. Câu hỏi chính
của đề bài là so sánh các chính sách, và với câu hỏi đó thì độ chính xác cần xét là của tỷ số
A/B. `STATS.md` §5.2 báo cáo nửa độ rộng tương đối của khoảng tin cậy tỷ số (`e^h − 1`, với h là
nửa độ rộng trên thang log), cùng số lần lặp mà cặp kém chính xác nhất cần để đạt ±10%
(`n = min{ n : t(0.975; n−1)·s_D/√n ≤ log 1.1 }`).

## 5. Ước lượng khoảng

* **Trung bình**: `Ȳ ± t(0.975; n−1)·s/√n`. Các lần lặp độc lập và cùng phân phối, nên theo
  định lý giới hạn trung tâm, trung bình của n lần lặp có phân phối xấp xỉ chuẩn. Bản thân giá trị
  của từng lần lặp thì có thể lệch phải, vì một vài seed gặp đợt mất kết nối dài, nên cần thêm kiểm
  tra chéo ở dòng dưới.
* **Kiểm tra chéo**: khoảng bootstrap BCa (9999 mẫu lặp, seed cố định nên kết quả tái lập được),
  cột `boot_lo`, `boot_hi` của `analysis/stats/cells.csv`. Khi khoảng t và khoảng BCa lệch nhau
  rõ (chỉ số p95, New-first) thì nên trích khoảng BCa. Khoảng t có cận dưới âm cho một đại lượng
  luôn dương là dấu hiệu của độ lệch đó.
* **Tỷ số giữa hai chính sách**: "A chậm hơn B x%" được ước lượng bằng trung bình theo cặp của
  `D = log(Y_A / Y_B)` với khoảng t, rồi lấy mũ: đó là trung bình nhân của các tỷ số theo seed,
  kèm khoảng tin cậy. Cách này tránh chia hai trung bình độc lập và cho khoảng đối xứng trên thang
  log.

## 6. Kiểm định

| Bước | Kiểm định | Vì sao |
|---|---|---|
| Có khác biệt giữa 4 chính sách không | Friedman (khối = seed), kèm Kendall's W | phi tham số, đúng cho thiết kế khối ngẫu nhiên đầy đủ |
| Từng cặp (6 cặp) | paired t-test trên `D = log(A/B)` | cùng thang với tỷ số và khoảng tin cậy của nó (§3, §5); vững với n = 20 |
| Kiểm tra độ vững | Wilcoxon signed-rank trên cùng D | không cần giả định chuẩn |
| Giả định chuẩn của D | Shapiro–Wilk | nếu bác bỏ, đọc kèm Wilcoxon và khoảng BCa |
| Đa so sánh | Holm–Bonferroni trong mỗi họ 6 cặp | kiểm soát xác suất sai loại I của cả họ ở 5% |
| Góc nhìn thứ hai | Benjamini–Hochberg trên toàn bộ 480 so sánh | kiểm soát tỷ lệ phát hiện sai (FDR) |

Một **họ** là 6 so sánh cặp của một chỉ số trong một kịch bản: nó trả lời đúng một câu hỏi,
"chính sách nào tốt nhất cho chỉ số m ở kịch bản s".

Độ lớn hiệu ứng: tỷ số (mục 5), `d_z = mean(D) / sd(D)` (Cohen cho dữ liệu ghép cặp) và tỷ lệ thắng
(tỷ lệ seed mà A tốt hơn B). Có ý nghĩa thống kê không có nghĩa là quan trọng trong thực tế: một
khác biệt 1% có thể có p rất nhỏ nhờ CRN, nên luôn đọc kèm tỷ số.

**Quy tắc chọn chính sách tốt nhất**: lấy chính sách có trung bình tốt nhất theo chiều của chỉ
số. Một chính sách khác chỉ bị coi là **kém hơn** nó khi cả paired t-test lẫn Wilcoxon trên D đều
có ý nghĩa sau hiệu chỉnh Holm, và ước lượng điểm cùng chiều với thứ tự trung bình. Mọi trường
hợp còn lại được coi là **không phân biệt được** (in nghiêng trong bảng). Yêu cầu hai kiểm định
cùng đồng ý là cách thận trọng: một kết luận phụ thuộc vào giả định chuẩn hoặc vào vài seed
ngoại lai sẽ không được tính là thắng. Dấu ✓ nghĩa là chính sách tốt nhất hơn hẳn cả ba chính
sách còn lại theo quy tắc này.

## 7. Giới hạn

* Thiết kế quét từng yếu tố quanh kịch bản gốc (one-factor-at-a-time): kết luận chỉ áp dụng cho
  các mức đã thử, và không ước lượng được tương tác giữa các yếu tố.
* `backDelayMean` chỉ tính trên gói đã giao. New-first giao ít dữ liệu bù trước khi hết giờ, nên
  độ trễ bù của nó phải đọc kèm `backDelivered`.
* `drainMean` không xác định khi một lần chạy không có lần xả nào; lần chạy đó bị loại khỏi ô
  tương ứng và khỏi các so sánh ghép cặp liên quan.
* Kịch bản `storm` (COHORT) dùng hành vi đã publish: edge sau storm vẫn rơi lẻ (xem
  [`MODEL.md`](MODEL.md) §5).

## 8. Tái lập

```powershell
pip install -r analysis/requirements.txt
.\sim\run.ps1 grid -Seeds 5                                            # results/runs (seed 0-4, có tick CSV)
.\sim\run.ps1 grid -FirstSeed 5 -Seeds 15 -Jobs 2 -NoTicks -OutDir results/replications
python analysis/stats.py                                               # results/summary_runs.csv, analysis/stats/*, analysis/STATS.md, figures
.\sim\demo.ps1                                                         # in tóm tắt kết luận
```

Mọi lần chạy đều tất định theo seed, và bootstrap dùng seed cố định, nên chạy lại cho ra đúng các
con số trong `STATS.md`.

## 9. Tài liệu tham khảo

* A. M. Law, *Simulation Modeling and Analysis*, 5th ed., McGraw-Hill, 2015: ch. 9 (phân tích
  đầu ra, phương pháp lặp độc lập, tiêu chí độ chính xác tương đối), ch. 11 (số ngẫu nhiên chung).
* M. Friedman, "The use of ranks to avoid the assumption of normality implicit in the analysis of
  variance", *JASA*, 1937.
* S. Holm, "A simple sequentially rejective multiple test procedure", *Scand. J. Statistics*, 1979.
* Y. Benjamini, Y. Hochberg, "Controlling the false discovery rate", *JRSS B*, 1995.
* B. Efron, "Better bootstrap confidence intervals", *JASA*, 1987.
* F. Wilcoxon, "Individual comparisons by ranking methods", *Biometrics Bulletin*, 1945.
