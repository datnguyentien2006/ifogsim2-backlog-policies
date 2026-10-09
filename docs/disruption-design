# Thiết kế và cách hoạt động của module disruption

## 1. Bài toán cần giải quyết

Trong mô phỏng, các biên có thể mất kết nối trong một khoảng thời gian rồi kết nối lại. Khi đường truyền bị gián đoạn, dữ liệu tiếp tục sinh ra và có thể tích lại thành backlog. Thời điểm phục hồi vì thế ảnh hưởng trực tiếp đến lượng dữ liệu cần gửi và cách các chính sách gửi hoạt động.

Phần disruption mô tả lịch mất kết nối, cập nhật trạng thái của từng biên và ghi lại những lần thay đổi đó. Bộ chạy dùng trạng thái này để quyết định khi nào giữ dữ liệu và khi nào cho phép gửi.

## 2. Vì sao sinh lịch trước khi chạy mô phỏng?

Thiết kế này tạo sẵn lịch mất kết nối rồi dùng CloudSim để phát lại. Cách làm đó xuất phát từ yêu cầu so sánh các chính sách gửi dữ liệu trên cùng một tình huống. Nếu mỗi lần chạy lại sinh một lịch khác, chênh lệch kết quả có thể đến từ thời gian mất kết nối, khiến việc đánh giá policy khó rõ ràng.

Vì vậy, code được chia thành ba phần chính. `DisconnectionModel` tạo lịch từ cấu hình và seed. `DisruptionPlan` giữ lịch đã tạo, đồng thời kiểm tra các khoảng gián đoạn. `ReconnectionScheduler` áp dụng lịch trong lúc mô phỏng chạy. `DisconnectEventLog` ghi những chuyển đổi đã được áp dụng để kiểm tra kết quả sau đó.

Plan được giữ bất biến. Khi một policy đang chạy, nó không thể sửa thời điểm mất kết nối hoặc phục hồi trong plan. Cùng một plan có thể dùng cho nhiều lượt so sánh, còn mỗi lượt cần scheduler và log riêng.

## 3. Lịch mất kết nối được tạo như thế nào?

Mỗi biên được biểu diễn bằng hai trạng thái `ONLINE` và `OFFLINE`. Một lần gián đoạn được lưu trong `Outage`, gồm mã biên, số thứ tự chu kỳ, thời điểm mất kết nối và thời điểm phục hồi.

Code dùng `DurationSpec` để cấu hình thời lượng. Có thể chọn một giá trị cố định, lấy ngẫu nhiên trong một khoảng hoặc dùng phân phối mũ có thời lượng tối thiểu. Giá trị cố định thuận tiện để kiểm tra bằng tay. Hai phân phối còn lại tạo ra các chu kỳ có độ dài khác nhau. Tất cả thời lượng đều tính bằng giây mô phỏng.

Ở chế độ **scattered**, mỗi biên có lịch riêng. Ban đầu biên ONLINE. Chương trình lấy một mẫu thời gian ONLINE, cộng vào thời điểm phục hồi trước đó để tìm lần mất kết nối tiếp theo. Sau đó, nó lấy thời gian OFFLINE để xác định lúc phục hồi. Quá trình này lặp lại đến khi hết thời gian quan sát.

Ví dụ, nếu ONLINE luôn kéo dài 10 giây và OFFLINE kéo dài 5 giây, biên sẽ mất kết nối từ giây 10 đến 15, từ 25 đến 30, rồi từ 40 đến 45. Thời gian ONLINE của chu kỳ sau bắt đầu tính từ lúc phục hồi của chu kỳ trước.

Ở chế độ **clustered**, một nhóm biên mất kết nối cùng lúc. Chương trình lấy thời gian gián đoạn nền chung, sau đó cộng thêm một độ trễ ngẫu nhiên riêng cho từng biên, gọi là jitter. Nhờ vậy, các biên có thể phục hồi gần nhau nhưng không nhất thiết đúng cùng một thời điểm. Nếu cửa sổ jitter bằng 0, cả nhóm phục hồi cùng lúc.

Sự cố tiếp theo được tính từ lúc biên cuối cùng trong nhóm phục hồi. Cách tính này giữ các đợt sự cố tách nhau và thể hiện rõ từng đợt dữ liệu có thể dồn về sau phục hồi. Những biên ngoài nhóm vẫn ONLINE.

Ngoài hai cách sinh tự động, chế độ **fixed** cho phép nhập trực tiếp các outage. Chế độ này hữu ích khi cần kiểm tra một tình huống cụ thể, chẳng hạn hai biên cùng kết nối lại ở giây 50.

Các lần lấy mẫu đều dùng seed. Trong scattered, từng biên có luồng số ngẫu nhiên riêng cho thời gian ONLINE và OFFLINE. Đổi thứ tự danh sách biên hoặc thêm một biên khác không làm đổi lịch của biên đã có. Với clustered, thời điểm sự cố dùng luồng chung, còn jitter dùng luồng riêng của từng biên. Giữ nguyên seed và cấu hình giúp chạy lại đúng kịch bản đã tạo.

## 4. Từ lịch gián đoạn đến sự kiện CloudSim

Sau khi nhận danh sách outage, `DisruptionPlan` kiểm tra mã biên, thời điểm và thứ tự chu kỳ. Hai outage trên cùng một biên không được chồng lấn hoặc chạm nhau. Các dữ liệu không hợp lệ bị từ chối ngay để tránh đưa một lịch sai vào mô phỏng.

Mỗi outage được chuyển thành các `Transition`: một lần chuyển sang OFFLINE tại thời điểm mất kết nối và một lần chuyển sang ONLINE tại thời điểm phục hồi. Các transition được sắp theo thời gian. Nếu nhiều biên chuyển trạng thái cùng lúc, code xử lý theo mã biên tăng dần để thứ tự luôn ổn định.

Giả sử một biên mất kết nối trong khoảng `[20, 50)`. Trạng thái của nó được hiểu như sau:

| Thời điểm | Trạng thái |
| --- | --- |
| Trước giây 20 | ONLINE |
| Đúng giây 20 | OFFLINE |
| Từ giây 20 đến trước giây 50 | OFFLINE |
| Đúng giây 50 | ONLINE |

Quy ước này làm rõ trạng thái tại thời điểm chuyển tiếp. Bộ chạy có thể xử lý dữ liệu ngay sau khi biên phục hồi ở giây 50.

`ReconnectionScheduler` là một entity của CloudSim. Khi bắt đầu chạy, nó đặt các sự kiện đánh thức tại những thời điểm cần chuyển trạng thái. Hàm `applyDue(now)` xử lý các transition đã đến hạn, cập nhật trạng thái, ghi log rồi thông báo cho listener nếu có.

Scheduler giữ một chỉ số trỏ đến transition chưa xử lý tiếp theo. Nhờ đó, gọi `applyDue` nhiều lần ở cùng một thời điểm vẫn chỉ áp dụng mỗi transition một lần. Các transition đã xử lý không bị ghi log lại.

Nếu bộ chạy làm việc theo từng tick, đầu tick cần gọi `applyDue` trước khi đọc trạng thái kết nối. Điều này xử lý trường hợp tick trùng với thời điểm mất kết nối hoặc phục hồi. Bộ chạy sẽ dùng trạng thái mới cho công việc của tick đó.

## 5. Xử lý cuối mô phỏng và ghi log

`horizon` là mốc kết thúc quan sát. Chương trình chỉ phát lại các transition trước mốc này. Ví dụ, outage bắt đầu ở giây 70 và dự kiến phục hồi ở giây 95, nhưng mô phỏng kết thúc ở giây 80. Lịch vẫn giữ thời điểm phục hồi là 95. Trong lượt chạy đó, scheduler chỉ áp dụng lần OFFLINE và không tự thêm một lần ONLINE tại giây 80.

Giữ nguyên thời điểm dự kiến giúp phân biệt thời lượng đầy đủ với phần quan sát được. Outage trên kéo dài 25 giây theo kế hoạch, nhưng chỉ có 10 giây nằm trong cửa sổ mô phỏng.

Code xuất hai loại CSV. Event log ghi những lần chuyển trạng thái đã áp dụng, gồm cả thời điểm kế hoạch và thời điểm áp dụng. Nếu bộ chạy gọi `applyDue` muộn, hai thời điểm này sẽ khác nhau. Outage summary tổng hợp các khoảng gián đoạn từ plan, cho biết thời lượng dự kiến, phần nằm trong cửa sổ quan sát và khả năng phục hồi trước khi kết thúc.

Hai loại dữ liệu phục vụ hai việc khác nhau. Event log dùng để kiểm tra quá trình thực thi. Outage summary dùng để xem kịch bản đã xây dựng. Khi mô phỏng chạy muộn hoặc dừng sớm, cần đọc event log để biết những gì thực sự đã được áp dụng.

## 6. Ghép với phần gửi dữ liệu

Module disruption cung cấp trạng thái kết nối cho bộ chạy. Khi biên OFFLINE, bộ chạy cần giữ dữ liệu trong backlog. Khi biên ONLINE, policy chọn dữ liệu để gửi theo quy tắc của nó. Listener có thể được dùng để thông báo phục hồi và kích hoạt việc gửi tiếp.

Việc giữ backlog, giới hạn tốc độ và chọn bản ghi thuộc các phần xử lý dữ liệu. Tách trách nhiệm như vậy cho phép thay đổi policy trong khi vẫn giữ nguyên lịch sự cố để so sánh.

Trong lần rà soát ngày 02/10/2026, phần disruption đã đạt 54/54 ca kiểm thử. Các ca kiểm tra bao gồm sinh lịch, trạng thái tại thời điểm chuyển tiếp, tính tái lập theo seed, phát lại trong CloudSim và ghi CSV. Khi ghép với bộ chạy thật, cần kiểm tra thêm dữ liệu có được giữ đúng khi mất kết nối và gửi lại theo policy sau phục hồi hay không.
