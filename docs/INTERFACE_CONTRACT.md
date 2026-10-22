# INTERFACE_CONTRACT — Hợp đồng giao diện `SendingPolicy`
---

## 1. Chữ ký hàm chung của `SendingPolicy`

```java
public interface SendingPolicy {
    String name();
    List<BacklogItem> select(double now, long capacityBytes,
                             Map<Integer, BacklogQueue> queues, long newDataBytes);
    void reset();
}
```

| `now` | Thời gian mô phỏng hiện tại (giây) |
| `capacityBytes` | Dung lượng policy được dùng cho backlog trong tick (xem bảng dưới) |
| `queues` | Hàng đợi tồn đọng theo `edgeId` |
| `newDataBytes` | Dữ liệu mới phát sinh trong tick; **chỉ Priority dùng**, 3 policy còn lại bỏ qua |
| Trả về | Item được chọn **theo thứ tự gửi**, tổng ≤ `capacityBytes`, không bao giờ `null` |

**Quy tắc đi kèm:**
- Runner gửi toàn bộ danh sách trả về.
- `select()` chỉ chọn, **không** lấy item ra khỏi hàng đợi; runner gửi toàn bộ danh sách rồi mới `poll()`.
- `capacityBytes` khác nhau theo nhóm: **FIFO / Rate-Limit / Round-Robin** nhận phần dung lượng **đã trừ dữ liệu mới**; **Priority** nhận **toàn bộ** dung lượng tick và tự chia.
- **Dữ liệu mới vượt dung lượng → DROP:** phần vượt bị bỏ ngay, tính là mất gói, không retry, không đưa vào `BacklogQueue`. Công thức: `newDataBytesSent = min(newDataBytes, capacityBytes)`; phần dư bị drop. N6 thêm metric `droppedNewDataBytes` cho cả 4 policy.
- **Không âm:** mọi phép trừ dung lượng đều chặn ở 0. Policy nhận `capacityBytes <= 0` trả về danh sách rỗng.
- **Thứ tự gọi (4 policy không dùng chung một vòng lặp):**
  - **FIFO / Rate-Limit / Round-Robin:** gửi dữ liệu mới (`min`) → `backlogCapacityBytes = max(0, capacity − newDataBytesSent)` → `select()` → gửi và `poll()`.
  - **Priority:** `select(capacityBytesFull, …)` → `remainingForNew = max(0, capacityFull − byte item cũ đã chọn)` → gửi dữ liệu mới tối đa `remainingForNew` (phần vượt bị drop) → gửi và `poll()` item cũ.

## 2. Đơn vị "băng thông khả dụng"

- Thời gian: **giây mô phỏng**. Kích thước: **byte**.
- Interface nhận `capacityBytes` của **tick hiện tại**. Runner đổi từ băng thông: `capacityBytes = bandwidth (bytes/giây) × tickSeconds`.
- Đề xuất **1 tick = 1 giây**, khai báo một lần trong `TopologyConfig` (N1 quản lý) để cả 4 policy dùng chung.
- Dữ liệu mới của 1 tick **chỉ** gồm dữ liệu sinh ra **trong tick đó**, không dồn sang tick khác. Phần không gửi kịp bị drop


## 3. Phạm vi áp dụng rate-limit 

- Giới hạn **tổng cho toàn hệ thống** (một token bucket chung), không chia riêng từng biên. Lý do: đề yêu cầu tốc độ không đổi bất kể tồn đọng.
- Trong hạn mức, chọn giữ liệu cũ nhất trước (*FIFO TÒA CỤC*)
- Chỉ áp cho **backlog**, **không** áp cho dữ liệu mới. **Không gộp** Rate-Limit vào Priority (N1 chốt).
- Trong hạn mức, chọn dữ liệu cũ nhất trước.

## 4. Tổng hợp trọng tâm (đã chốt)
*4.1 Đơn vị*
Thời gian: giây. Kích thước: byte. 1 tick = 1 giây.
Lưu ý: đổi bandwidth thành capacityBytes.

*4.2 Nút thắt mạng*
Chỉ xét khả năng tiếp nhận của trung tâm. Đường truyền của từng biên coi như không giới hạn.

*4.3 Chia dung lượng cũ/mới*
- newDataBytes chỉ gồm dữ liệu mới sinh ra trong tick, không cộng phần chưa gửi từ tick trước.
- Nếu dữ liệu mới vượt phần dung lượng dành cho nó, phần vượt bị drop ngay, tính là mất gói, không đưa vào BacklogQueue, không gửi lại.
- FIFO, Rate-Limit, Round-Robin: runner gửi dữ liệu mới trước, với newDataBytesSent = min(newDataBytes, capacityBytes). Phần dư newDataBytes − newDataBytesSent bị drop. Sau đó backlogCapacityBytes = max(0, capacityBytes − newDataBytesSent) rồi mới gọi select().
- Priority: xem 4.12.
- Lý do drop thay vì đẩy vào BacklogQueue: giữ đúng định nghĩa "cũ" ở 4.9; không bắt runner ghi vào BacklogQueue (chỉ DisconnectionModel được ghi); đúng thực tế mạng quá tải thì mất gói.

*4.4 Biên nào có trong queues*
- Chỉ các biên đang kết nối, duyệt theo edgeId tăng dần.
- Biên mất kết nối không bị chọn. Nếu một biên không còn gửi dữ liệu nữa thì bỏ qua biên đó.

*4.5 Cơ chế lấy dữ liệu*
- Các policy tự gọi poll để lấy item được chọn ra khỏi hàng đợi.


*4.6 Gói tin (item)*
- Không chia nhỏ gói tin.
- Cấu hình phải đảm bảo kích thước tối đa một gói tin maxItemBytes <= capacityBytes.
- Với Priority, cần thêm maxItemBytes <= effectiveOldQuota.

*4.7 Rate-limit*
- Dùng thuật toán Token Bucket chung cho toàn hệ thống.
- Token nạp với tốc độ R byte/giây, tối đa bằng độ sâu bucket (R × burstSeconds), tràn thì mất. Sau reset(), bucket đầy.
- Ngân sách của tick = min(backlogCapacityBytes, floor(tokens)). Chọn dữ liệu cũ nhất trước (FIFO toàn cục), trừ token theo số byte đã chọn.
- Không work-conserving: không dùng quá trần giới hạn dù trung tâm đang rảnh rỗi.

*4.8 Round-robin (khi một biên đã gửi hết)*
- Bỏ qua biên rỗng.
- Lưu con trỏ bằng edgeId của biên được phục vụ gần nhất (không lưu bằng chỉ số index), đảm bảo chính xác khi biên vào/ra liên tục.
- Dữ liệu mới không tham gia vòng này.

*4.9 Priority*
- Data mới: Dữ liệu sinh ra khi biên đang kết nối là Mới; dữ liệu nằm trong BacklogQueue là cũ.
- Work-conserving: Dữ liệu Mới dùng không hết băng thông thì phần dư được chuyển sang cho dữ liệu Cũ xài. 
- Quy tắc chung: mọi phép trừ dung lượng trong toàn hệ thống đều phải chặn ở 0, không để giá trị âm đi vào policy.


*4.10 Thời điểm gọi reset()*
- Gọi khi bắt đầu mỗi lần chạy mới (đổi kịch bản, đổi seed, vòng lặp mới) do Runner thực hiện.
- Không gọi khi có một biên tái cũ kết nối.
- ## Runner nên tạo policy mới mỗi lần chạy (dạng factory) và vẫn gọi reset().


*4.11 Tính xác định*
- ## Cấm dùng hàm Random không có seed.
- Nếu cùng mức độ ưu tiên, dùng thứ tự chuẩn để phân định.


*4.12 Thứ tự gọi theo nhóm (N6)*
-	Nhóm FIFO, RateLimit, RoundRobin: runner gửi dữ liệu mới trước, trừ khỏi capacityBytes rồi mới gọi select().
-	Priority làm ngược lại: 
	  Bước 1: runner gọi select(now, capacityBytes toàn phần, queues, newDataBytes). Policy tự áp quota cho dữ liệu cũ và trả về item cũ được chọn.
	  Bước 2: remainingCapacityForNew = max(0, capacityBytes − tổng byte item cũ đã chọn).
    Bước 3: runner gửi dữ liệu mới tối đa bằng remainingCapacityForNew. Phần vượt bị drop theo 4.3.





## 5. Định nghĩa "dữ liệu mới" cho Priority 

-FifoPolicy: lặp, lấy item đầu hàng nhỏ nhất theo thứ tự chuẩn trong tất cả hàng đợi. Nếu item không vừa phần dung lượng còn lại thì dừng để giữ đúng thứ tự, ngược lại poll và thêm vào kết quả. Ngân sách = backlogCapacityBytes.

-RateLimitPolicy: giống FIFO toàn cục nhưng ngân sách = min(backlogCapacityBytes, floor(tokens)), sau đó trừ token.

-RoundRobinPolicy: bắt đầu từ biên có edgeId nhỏ nhất lớn hơn con trỏ (hết thì vòng lại). Mỗi lượt biên gửi một phần dữ liệu (quantumBytes, ít nhất 1 item nếu vừa), rồi sang biên kế. Biên rỗng hoặc item đầu không vừa thì bỏ qua. Dừng khi không biên nào gửi thêm được. Con trỏ = biên cuối cùng được phục vụ.
- PriorityNewDataPolicy: tham số oldDataQuotaBytes (cố định, bất kể tải dữ liệu mới).
•	  effectiveOldQuota = min(oldDataQuotaBytes, capacityBytes)
•	  ngân sách cũ = min(capacityBytes, max(effectiveOldQuota, max(0, capacityBytes − newDataBytes))) (IDK, chưa    chắc lắm sau code làm kĩ tí :v)
•	  Chọn dữ liệu cũ theo FIFO toàn cục trong ngân sách này.

## 6. Thời điểm gọi `reset()`

- Gọi **mỗi khi bắt đầu một lần chạy mới** (mỗi kịch bản, seed, lần lặp), do `ExperimentRunner` (N6) thực hiện.
- **Không** gọi khi một biên tái kết nối, vì policy phải giữ trạng thái giữa các tick (con trỏ Round-Robin, token của Rate-Limit).
- Phương án thay thế: tạo đối tượng policy mới cho mỗi lần chạy thay vì gọi `reset()`.


