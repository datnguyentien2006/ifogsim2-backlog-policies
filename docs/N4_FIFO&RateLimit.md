# Giải thích hợp đồng policy, `FifoPolicy` và `RateLimitPolicy`

> Phần của N4: `SchedulingPolicy`, `AllocationSink`, `EdgeQueueView`, `FifoPolicy`, `RateLimitPolicy` (thư mục `sim/src/backfill/policy/`).

**Quy ước đơn vị.** Một *unit* là một tuple 1.000 B. Mỗi tick (`decisionInterval = 0,1 s`) trung tâm nhận tối đa `tokens = round(1200 × 0,1) = 120` unit; ngân sách này **giống nhau cho mọi policy**. Policy chỉ trả lời câu hỏi: *120 token đó cấp cho edge nào, hàng đợi nào, bao nhiêu*.

---

## 1. `SchedulingPolicy.java` — hợp đồng chung

### 1.1 Vai trò
Interface mà cả 4 policy cùng cài đặt. Khung mô phỏng chỉ gọi interface này, nên thêm một policy mới không cần sửa framework. Interface còn chứa các **hàm helper dùng chung** để các policy không tự viết lại logic quét hàng đợi.

### 1.2 Các hàm của interface
| Hàm | Ý nghĩa |
|---|---|
| `key()` | Khóa ngắn cố định (`fifo`, `rr`, `ratelimit`, `newfirst`), dùng trong CLI, tên file kết quả và web |
| `displayName()` | Tên hiển thị trong bảng và biểu đồ |
| `description()` | Một dòng mô tả cách cấp phát |
| `allocate(now, tokens, edges, sink, params)` | Quyết định cấp phát ngân sách của một tick |

**Equal-input:** `allocate` chỉ nhận thời gian, số token, danh sách `EdgeQueueView` (chỉ đọc), `sink` để cấp phát và `SimParams` cấu hình. Policy không có đường nào khác để nhìn hoặc tác động vào dữ liệu, nên so sánh giữa các policy là công bằng.

### 1.3 Hàm tĩnh
- **`standardPolicies()`** trả về bốn policy theo thứ tự FIFO, Round-Robin, Rate-limit, New-first.
- **`byKey(key)`** tra policy theo `key()`, không phân biệt hoa thường; khóa lạ thì ném `IllegalArgumentException` và liệt kê các khóa hợp lệ. Thêm policy mới phải đăng ký qua đây.

### 1.4 Hai helper quan trọng

**`anyData(edges)`**: `true` nếu có ít nhất một edge **đang kết nối** và còn dữ liệu (`hasAny()`).

**`drainOldestGlobal(edges, sink, limit, which)`** — cấp tối đa `limit` unit theo **thứ tự sinh toàn cục** trên mọi edge đang kết nối. `which` chọn hàng đợi: `NEW`, `BACK` hoặc `BOTH` (FIFO chặt trên cả hai loại).

- Mỗi vòng lặp: duyệt các edge đang kết nối, lấy `genTime` của đầu hàng đợi (new và/hoặc back tùy `which`), chọn đầu hàng có `genTime` **nhỏ nhất**, rồi `sink.grant(edge, isNew, 1)` đúng **một** unit.
- Điều kiện dừng: đủ `limit`, hoặc hết dữ liệu (`anyData` sai), hoặc `best == null`. Nhánh `best == null` cần thiết vì `anyData` xét cả hai hàng đợi: với `which = BACK` mà chỉ còn dữ liệu mới thì `anyData` vẫn đúng nhưng không có đầu hàng nào hợp lệ, không có nhánh này sẽ lặp vô hạn.
- Hòa `genTime`: so sánh dùng `<` chặt, nên edge đứng trước trong danh sách (`edgeIndex` nhỏ hơn) thắng; trong cùng một edge, hàng *new* được xét trước hàng *back*. Kết quả luôn xác định.
- **Trả về `int` = số unit thực sự cấp.**

**`drainEdgeMerged(v, limit, sink)`** — trong **một edge**, cấp tối đa `limit` unit theo thứ tự sinh của edge đó, gộp cả hai hàng đợi (hòa thì *new* trước). Cũng **trả về số unit thực cấp**, có thể nhỏ hơn `limit` nếu edge hết dữ liệu giữa chừng.

> **Vì sao trả về `int`?** Số unit thực cấp có thể nhỏ hơn số định xin. Policy phải trừ ngân sách theo giá trị trả về; nếu trừ theo số định xin thì phần token dư biến mất (lãng phí ngân sách, một cạm bẫy đã gặp ở bản repo 1 với Round-Robin).

---

## 2. `AllocationSink.java` — cổng cấp phát duy nhất

### 2.1 Vai trò
Cầu nối giữa policy và hàng đợi. Policy chỉ nói *"edge nào, hàng đợi nào, bao nhiêu unit"*; `SchedulerEntity` mới là bên lấy unit ra khỏi hàng đợi và đưa xuống thiết bị. Policy **không chạm trực tiếp** vào device.

### 2.2 Chi tiết
Một hàm duy nhất: `grant(int edgeIndex, boolean isNew, int count)` — `isNew = true` lấy từ hàng dữ liệu mới, `false` lấy từ hàng tồn đọng. Bản triển khai trong `SchedulerEntity` kiểm tra:

- `count <= 0`: bỏ qua.
- `count` lớn hơn số unit đang có trong hàng đó: ném `IllegalStateException` (lỗi lập tức, không âm thầm cắt).
- Sau khi `allocate` kết thúc, nếu **tổng** đã cấp vượt `tokens` cũng ném lỗi.

Nhờ vậy, policy cấp sai sẽ lộ ngay khi chạy thay vì cho ra số liệu sai.

---

## 3. `EdgeQueueView.java` — những gì policy được nhìn

### 3.1 Vai trò
Giao diện chỉ-đọc mô tả một edge tại tick hiện tại. Đây là **toàn bộ thông tin** mà policy dùng để quyết định.

### 3.2 Chi tiết
| Hàm | Ý nghĩa |
|---|---|
| `edgeIndex()` | Chỉ số edge |
| `connected()` | Link tới trung tâm đang UP; edge mất kết nối **không bao giờ** được cấp |
| `newCount()` / `backCount()` | Số unit ở hàng dữ liệu mới / hàng tồn đọng |
| `newHeadGenTime()` / `backHeadGenTime()` | `genTime` của unit đầu hàng, `null` nếu hàng rỗng (kiểu `Double`) |
| `hasAny()` | `newCount() + backCount() > 0` |

Không có hàm ghi nào. Trong bản triển khai của `SchedulerEntity`, view đọc trực tiếp từ device nên sau mỗi `grant` thì số lượng và đầu hàng cập nhật ngay; đó là lý do `drainOldestGlobal` có thể lặp "chọn đầu hàng cũ nhất rồi cấp 1 unit" mà vẫn đúng.

---

## 4. `FifoPolicy`

**Nhiệm vụ:** gửi **đúng thứ tự phát sinh** trên toàn hệ thống. Dữ liệu tồn đọng sinh ra trước nên tự nhiên đi trước; dữ liệu mới phải **xếp sau** khối tồn đọng. Dùng toàn bộ dung lượng trung tâm, không giới hạn tốc độ.

**Cách làm (một bước):** gọi `drainOldestGlobal(edges, sink, (int) tokens, Queue.BOTH)`.

- `Queue.BOTH` trộn đầu hàng của **cả hai** hàng đợi ở mọi edge đang kết nối, chọn `genTime` nhỏ nhất → thứ tự sinh toàn cục.
- Không cần cộng dồn hay vòng lặp ngoài, vì chỉ có một lần cấp tối đa `tokens`.

**Ví dụ:** tick có 120 token; edge A tồn đọng 500 unit (cũ), edge B có 30 unit mới. Tất cả 120 token đi vào tồn đọng của A; dữ liệu mới của B **phải chờ**. Đây chính là điểm yếu cần quan sát của FIFO: khi tồn đọng lớn, dữ liệu mới bị trễ rất lâu.

**Lưu ý thiết kế:** FIFO **toàn cục**, không phải FIFO riêng từng edge. Làm từng edge riêng sẽ thành một chính sách khác (gần giống Round-Robin).

---

## 5. `RateLimitPolicy`

**Nhiệm vụ:** gửi bù tồn đọng ở **tốc độ cố định** `R = rateLimitFactor × C` (mặc định `0,5 × 1200 = 600 unit/s`), không đổi dù tồn đọng ít hay nhiều. Mỗi tick, phần dành cho tồn đọng là `rateLimitTokensPerTick() = round(1200 × 0,1 × 0,5) = 60` token.

**Các bước:**

**B1.** `reservedBack = min(rateLimitTokensPerTick, (int) tokens)` — hạn mức dành cho tồn đọng, chặn không vượt tổng ngân sách.

**B2.** `grantedBack = drainOldestGlobal(edges, sink, reservedBack, Queue.BACK)` — cấp tồn đọng cũ nhất trước, tối đa `reservedBack`. Lấy **giá trị trả về** vì tồn đọng có thể ít hơn hạn mức.

**B3.** `remaining = (int) tokens − grantedBack`. Nếu `remaining > 0` thì `drainOldestGlobal(..., remaining, Queue.NEW)` — phần còn lại (kể cả phần hạn mức tồn đọng không dùng hết) chảy sang dữ liệu mới, cũ nhất trước.

**Ví dụ** (`tokens = 120`, hạn mức tồn đọng 60):

| Tình huống | Tồn đọng được gửi | Dữ liệu mới được gửi |
|---|---|---|
| Tồn đọng rất lớn, dữ liệu mới nhiều | 60 | 60 |
| Tồn đọng chỉ có 10 unit, dữ liệu mới nhiều | 10 | 110 |
| Không có tồn đọng | 0 | tối đa 120 |
| Tồn đọng rất lớn, **không có** dữ liệu mới | 60 | 0 (60 token bị bỏ phí) |

Dòng cuối cho thấy chính sách **không work-conserving**: tồn đọng không bao giờ nhận quá hạn mức, kể cả khi trung tâm đang rảnh.

**Tính chất:**
- Thời gian xả dự đoán được: tồn đọng B unit cần khoảng `B / R` giây (nếu không bị gián đoạn thêm).
- Đợt tái kết nối không thể làm ngập trung tâm, vì ít nhất `(1 − rateLimitFactor) × C` dung lượng luôn dành cho dữ liệu mới.
- Hạn mức tồn đọng **được dành trước**, nên tốc độ gửi bù giữ cố định kể cả khi dữ liệu mới tăng đột biến.

> **Phân biệt với FIFO:** Rate-limit không "cấp hết token khi tồn đọng lớn". Nếu viết như vậy thì nó trở thành FIFO.

---

## 6. So sánh nhanh FIFO và Rate-limit

| | FIFO | Rate-limit |
|---|---|---|
| Thứ tự | Cũ nhất trước, cả mới lẫn tồn đọng | Tồn đọng cũ nhất, rồi dữ liệu mới cũ nhất |
| Tốc độ gửi bù | Tối đa, dùng cả `tokens` | Cố định ≤ 60 unit/tick |
| Dữ liệu mới khi tồn đọng lớn | Bị chặn hoàn toàn | Luôn có ít nhất `tokens − 60` |
| Dùng hết ngân sách? | Có | Không (khi thiếu dữ liệu mới) |
| Trạng thái giữa các tick | Không | Không |
| Điểm yếu | Dữ liệu mới trễ lâu | Xả tồn đọng chậm, có thể bỏ phí dung lượng |

---
