# Phương pháp thống kê cho bộ kết quả đo

Tài liệu này mô tả cách `analysis/stats.py` biến 1200 lần chạy mô phỏng thành các kết luận
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
`D_r = Y_r(A) − Y_r(B)`, rồi suy luận trên dãy `D_1..D_n`. Vì
`Var(D) = Var(A) + Var(B) − 2·Cov(A, B)` và CRN làm `Cov(A, B) > 0`, phương sai của hiệu nhỏ
hơn nhiều so với hai mẫu độc lập. `STATS.md` §6 báo cáo hệ số lợi
`(Var A + Var B) / Var(D)`: đó là số lần lặp mà thiết kế độc lập cần thêm để đạt cùng độ chính xác.

Các bước quét (ρ ∈ {0.4, 0.7, 0.9, 1.1}, N ∈ {4, 6, 10, 16}) cũng ghép cặp theo seed giữa
các mức. Ghép cặp luôn hợp lệ khi cặp được định nghĩa trước khi xem dữ liệu; mức tương quan chỉ
quyết định lợi ích nhiều hay ít.

## 4. Số lần lặp

Grid gốc có 5 seed (`results/runs`, kèm chuỗi thời gian từng tick). Bộ bổ sung
`results/replications` chạy thêm seed 5–29 (chỉ giữ file run CSV), tổng **30 lần lặp cho mỗi ô
kịch bản × chính sách**, 1200 lần chạy.

Lý do không dừng ở 5 seed:

* với n = 5, `t(0.975; 4) = 2.776`, khoảng tin cậy rộng gấp ~1.4 lần so với n = 30
  (`t(0.975; 29) = 2.045`) chưa kể hệ số `1/√n`;
* kiểm định Wilcoxon hai phía với n = 5 có p nhỏ nhất là `2/2⁵ = 0.0625 > 0.05`, tức là
  **không bao giờ** bác bỏ được giả thuyết bằng nhau, dù khác biệt lớn đến đâu.

Đủ hay chưa được kiểm bằng tiêu chí độ chính xác tương đối của Law & Kelton: với sai số tương đối
mục tiêu γ, cần nửa độ rộng khoảng tin cậy `h ≤ γ/(1+γ)·|Ȳ|`, nên số lần lặp cần là
`n*(γ) = min{ n : t(0.975; n−1)·s/√n ≤ γ/(1+γ)·|Ȳ| }` với s ước lượng từ chính các lần lặp đã có.
`STATS.md` §5 báo cáo độ rộng tương đối đạt được và n* cho γ = 5% và 10%. Ô nào n* > 30 thì kết
luận về giá trị tuyệt đối của ô đó kém chính xác hơn mục tiêu, nhưng so sánh ghép cặp vẫn dùng được
vì phương sai của hiệu nhỏ hơn nhiều.

## 5. Ước lượng khoảng

* **Trung bình**: `Ȳ ± t(0.975; n−1)·s/√n`. Chỉ số của một lần chạy là trung bình hoặc phân vị
  của rất nhiều gói, nên phân phối giữa các lần chạy gần chuẩn (định lý giới hạn trung tâm).
* **Kiểm tra chéo**: khoảng bootstrap BCa (9999 mẫu lặp, seed cố định nên kết quả tái lập được),
  cột `boot_lo`, `boot_hi` của `analysis/stats/cells.csv`. Với chỉ số lệch (p95, New-first) nên
  trích khoảng này.
* **Tỷ số giữa hai chính sách**: "A chậm hơn B x%" được ước lượng bằng trung bình theo cặp của
  `log(Y_A / Y_B)` với khoảng t, rồi lấy mũ: đó là tỷ số trung bình nhân và khoảng tin cậy của nó.
  Cách này tránh chia hai trung bình độc lập và cho khoảng đối xứng trên thang log.

## 6. Kiểm định

| Bước | Kiểm định | Vì sao |
|---|---|---|
| Có khác biệt giữa 4 chính sách không | Friedman (khối = seed), kèm Kendall's W | phi tham số, đúng cho thiết kế khối ngẫu nhiên đầy đủ |
| Từng cặp (6 cặp) | paired t-test | khớp với việc báo cáo trung bình; vững với n = 30 |
| Kiểm tra độ vững | Wilcoxon signed-rank | không cần giả định chuẩn |
| Giả định chuẩn của hiệu | Shapiro–Wilk trên D | nếu bác bỏ, ưu tiên Wilcoxon và khoảng BCa |
| Đa so sánh | Holm–Bonferroni trong mỗi họ 6 cặp | kiểm soát xác suất sai loại I của cả họ ở 5% |
| Góc nhìn thứ hai | Benjamini–Hochberg trên toàn bộ 480 so sánh | kiểm soát tỷ lệ phát hiện sai (FDR) |

Một **họ** là 6 so sánh cặp của một chỉ số trong một kịch bản: nó trả lời đúng một câu hỏi,
"chính sách nào tốt nhất cho chỉ số m ở kịch bản s".

Độ lớn hiệu ứng: tỷ số (mục 5), `d_z = mean(D) / sd(D)` (Cohen cho dữ liệu ghép cặp) và tỷ lệ thắng
(tỷ lệ seed mà A tốt hơn B). Có ý nghĩa thống kê không có nghĩa là quan trọng trong thực tế: một
khác biệt 1% có thể có p rất nhỏ nhờ CRN, nên luôn đọc kèm tỷ số.

**Quy tắc chọn chính sách tốt nhất**: chính sách có trung bình tốt nhất theo chiều của chỉ số;
chính sách nào có hiệu với nó **không** có ý nghĩa (p Holm của paired t ≥ 0.05) thì được coi là
hòa. Dấu ✓ trong bảng nghĩa là chính sách tốt nhất hơn hẳn cả ba chính sách còn lại.

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
.\sim\run.ps1 grid -FirstSeed 5 -Seeds 25 -Jobs 4 -NoTicks -OutDir results/replications
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
