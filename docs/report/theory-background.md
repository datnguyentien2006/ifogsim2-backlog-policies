# Chương: Cơ sở lý thuyết

Chương này giới thiệu các khái niệm mà mô hình mô phỏng dùng: điện toán biên và sương mù, bài toán dữ liệu tồn đọng
(backfill), cơ chế cấp phát theo ngân sách mỗi tick, bốn chính sách so sánh, chỉ số công bằng Jain, và mô hình
gián đoạn kết nối. Mọi công thức trong chương khớp với mã trong `sim/src/backfill/`.

**Ghi chú về tài liệu tham khảo:** danh sách tài liệu ở cuối chương được ghi từ trí nhớ và chưa được đối chiếu
với nguồn gốc. Trước khi nộp, cần kiểm tra từng mục (tên tác giả, năm, số tập, trang).

## 1. Điện toán biên và sương mù

Trong mô hình điện toán đám mây truyền thống, thiết bị cảm biến gửi toàn bộ dữ liệu lên trung tâm dữ liệu. Điện toán
biên (edge computing) đặt một phần khả năng tính toán và lưu trữ gần nguồn dữ liệu hơn, để giảm độ trễ và băng thông
đi lên. Điện toán sương mù (fog computing) mở rộng ý tưởng này thành nhiều tầng giữa thiết bị và đám mây.

Đề tài mô phỏng một cấu hình đơn giản: N edge server nhận dữ liệu từ cảm biến và gửi lên một trung tâm tiếp nhận
duy nhất. Dung lượng của trung tâm là C = 1200 đơn vị mỗi giây. Một đơn vị dữ liệu là một tuple 1 000 byte.

Mô phỏng dựa trên iFogSim2, một bộ công cụ mô phỏng sương mù xây dựng trên CloudSim. CloudSim cung cấp bộ máy
sự kiện rời rạc, còn iFogSim bổ sung các thực thể và mô hình riêng cho IoT và sương mù. iFogSim2 là phiên bản
mở rộng của iFogSim.

## 2. Dữ liệu tồn đọng (backfill)

Khi một edge mất kết nối với trung tâm, dữ liệu vẫn được sinh ra tại edge. Dữ liệu đó không thể gửi ngay, nên được lưu
lại trong hàng đợi backlog. Khi kết nối được khôi phục, trung tâm phải nhận cả dữ liệu mới và dữ liệu tồn đọng.

Mô hình tách dữ liệu thành hai loại theo thời điểm sinh:

- **Dữ liệu mới (new):** sinh khi edge đang kết nối.
- **Dữ liệu tồn đọng (back):** sinh khi edge đang mất kết nối. Phân loại này được quyết định ngay lúc sinh, không phải
lúc gửi.

Mỗi edge có hai hàng đợi FIFO tương ứng. Mỗi đơn vị mang thời điểm sinh `genTime` và cờ `isNew`. Thứ tự FIFO được
xác định theo `genTime`.

## 3. Cấp phát theo ngân sách mỗi tick

Trung tâm chỉ có thể tiếp nhận một lượng dữ liệu nhất định mỗi giây. Mô hình chia thời gian thành các tick có độ dài
Δt = 0.1 s. Ở mỗi tick, bộ lập lịch có ngân sách

B = round(C × Δt) = 120 đơn vị.

Ngân sách này được cấp lại đầy đủ ở mỗi tick. Phần chưa dùng không được tích lũy sang tick sau. Đây là điểm cần lưu
ý: plan của đề tài gọi cơ chế này là "token bucket", nhưng về bản chất đây là ngân sách cố định mỗi tick, không
phải token bucket có tích lũy.

Chính sách cấp phát quyết định cách chia ngân sách này cho các edge và các loại dữ liệu. Bộ lập lịch không được
sửa quyết định của chính sách; nó chỉ thực hiện và kiểm tra rằng tổng cấp phát không vượt ngân sách.

Tải dữ liệu mới tổng cộng là λ = ρ × C, với ρ = 0.7 trong cấu hình cơ sở, tức 840 đơn vị mỗi giây. Phần dư so với
ngân sách là 1200 − 840 = 360 đơn vị mỗi giây, dùng để xả backlog.

## 4. Bốn chính sách

Bốn chính sách nhận cùng đầu vào và chỉ khác nhau ở cách chia ngân sách mỗi tick.

- **FIFO theo thứ tự sinh.** Ở mỗi đơn vị ngân sách, chọn đơn vị có `genTime` nhỏ nhất trong tất cả hàng đợi của
các edge đang kết nối. Dữ liệu tồn đọng thường đi trước dữ liệu mới vì được sinh sớm hơn.
- **Round-robin.** Chia ngân sách theo vòng cho các edge còn dữ liệu. Nếu một edge hết dữ liệu giữa tick, phần ngân sách
còn lại được trả về quỹ chung và cấp cho edge khác. Chính sách này không ưu tiên loại dữ liệu nào.
- **Rate-limit.** Dành một hạn mức cố định R = f × C mỗi giây cho backlog (f = 0.5, tức 600 đơn vị mỗi giây, 60 đơn vị
mỗi tick). Phần còn lại cho dữ liệu mới. Tổng cấp phát không phụ thuộc vào độ lớn của backlog.
- **New-data-first.** Ưu tiên dữ liệu mới. Backlog chỉ được tối đa một hạn mức cố định q = 0.05 × C (60 đơn vị mỗi giây,
6 đơn vị mỗi tick). Nếu dữ liệu mới không đủ dùng hết ngân sách, phần dư bị bỏ trống chứ không cấp cho backlog.

Sự khác nhau giữa bốn chính sách thể hiện ở độ trễ của dữ liệu mới, độ trễ của backlog, mức độ công bằng giữa các
edge, và thời gian xả backlog sau khi kết nối được khôi phục.

## 5. Các đại lượng đo và chỉ số công bằng

Mô hình ghi lại các đại lượng sau cho mỗi run:

- Độ trễ của dữ liệu mới và backlog: trung bình, p50, p95, p99 (tính trên mẫu lấy theo phương pháp reservoir).
- Diện tích backlog (∫ backlog dt) và đỉnh backlog.
- Số lần mất kết nối, số lần khôi phục khi còn backlog, và thời gian xả backlog sau mỗi lần khôi phục.
- Chỉ số công bằng Jain trên backfill.

Chỉ số Jain (Jain, Chiu và Hawe, 1984) cho n giá trị không âm x_i là

J = (Σ x_i)² / (n Σ x_i²),

với J ∈ [1/n, 1]. J = 1 khi mọi x_i bằng nhau, và J giảm khi các giá trị lệch nhau. Trong mô hình, x_i là tỷ lệ
dữ liệu tồn đọng của edge i đã được giao trên tổng dữ liệu tồn đọng của edge đó:

x_i = delivBack_i / genBack_i, chỉ tính các edge có genBack_i > 0.

Hai quy ước cần ghi nhớ. Thứ nhất, edge không có backlog không đưa vào mẫu. Thứ hai, nếu không có dữ liệu tồn đọng
nào được giao (tổng x_i = 0), mô hình trả về J = 1 vì không có gì để so sánh. Quy ước thứ hai không phải là
hiển nhiên và phải được nêu rõ khi báo cáo kết quả.

## 6. Mô hình gián đoạn kết nối

Mô hình có hai chế độ gián đoạn.

**SCATTERED.** Mỗi edge độc lập. Thời gian kết nối giữa hai lần mất là phân phối mũ với tỷ lệ 0.01 lần mỗi giây
(trung bình 100 s). Thời gian mất kết nối có phân phối lognormal với trung bình 20 s và hệ số biến thiên CV = 0.8.
Với trung bình m và CV c, tham số của phân phối lognormal là

σ² = ln(1 + c²), μ = ln(m) − σ²/2,

để kỳ vọng đúng bằng m và hệ số biến thiên đúng bằng c.

**COHORT.** Mô phỏng các đợt mất kết nối đồng loạt, ví dụ khi một mạng con gặp sự cố. Cứ 120 s, một nửa số edge đang
kết nối bị mất kết nối cùng lúc. Thời gian mất kết nối có phân phối lognormal với CV = 0.15, nên các edge được khôi
phục gần như cùng lúc. Chế độ này tạo ra đợt backlog lớn và hiện tượng xả backlog đồng loạt.

Hai chế độ dùng cùng một luồng số ngẫu nhiên cho lịch gián đoạn, tách khỏi luồng arrival. Nhờ vậy đổi chính sách không
làm đổi lịch gián đoạn.

## 7. So sánh công bằng: equal input

Để so sánh bốn chính sách một cách công bằng, mỗi chính sách phải nhận cùng một đầu vào:

- Luồng arrival của mỗi edge được sinh từ seed riêng, không phụ thuộc chính sách.
- Lịch mất kết nối được sinh từ một luồng số ngẫu nhiên khác, cũng không phụ thuộc chính sách.
- Ngân sách mỗi tick giống nhau.
- Chính sách chỉ được nhìn thấy trạng thái đọc-được (EdgeQueueView) và chỉ tác động qua sink.

Các điều kiện này được kiểm tra tự động trong SelfTest (chương về độ tin cậy kiểm thử). Nếu một điều kiện bị vi phạm,
sự khác biệt giữa các chính sách không còn đo được chỉ do chính sách.

## Tài liệu tham khảo

1. R. N. Calheiros, R. Ranjan, A. Beloglazov, C. A. F. De Rose, R. Buyya. CloudSim: a toolkit for modeling and simulation

   of cloud computing environments and evaluation of resource provisioning algorithms. *Software: Practice and
    Experience*, 41(1), 2011.
2. H. Gupta, A. Vahid Dastjerdi, S. K. Ghosh, R. Buyya. iFogSim: A toolkit for modeling and simulation of resource

   management techniques in the Internet of Things, Edge and Fog computing environments. *Software: Practice and
    Experience*, 47(9), 2017.
3. R. Jain, D.-M. Chiu, W. R. Hawe. A Quantitative Measure of Fairness and Discrimination for Resource Allocation in

   Shared Computer Systems. DEC Technical Report 301, 1984.
4. Mã nguồn iFogSim2: https://github.com/Cloudslab/iFogSim2 (phiên bản được vendor trong `iFogSim2-vendor/`).
