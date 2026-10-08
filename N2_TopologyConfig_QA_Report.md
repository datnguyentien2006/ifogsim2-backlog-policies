# N2 QA — TopologyConfig tuần 41

## Kết quả

- TopologyConfigTest.java: 11 test cases.
- Compile production source: PASS với toàn bộ jars, sau khi dùng đúng commons-math3 jar ở thư mục con.
- Compile TopologyConfigTest.java: PASS về mặt source/API với JUnit 5 API tương ứng.
- Chạy 11 test bằng compatibility harness: 11 PASS, 0 FAIL.
- Repo hiện chỉ đóng gói junit-jupiter-engine-5.11.3.jar, không có junit-jupiter-api và junit-platform-launcher, nên chưa thể chạy test bằng JUnit 5 runner thực tế từ ZIP hiện tại.

## Các nhóm test


| Test                     | Mục đích                               |
| ------------------------ | -------------------------------------- |
| Default constructor      | Kiểm tra toàn bộ default               |
| Full constructor         | Kiểm tra truyền đủ 15 tham số          |
| Getter/setter            | Đảm bảo setter thực sự cập nhật config |
| Boundary hợp lệ          | `1 edge`, `0 sensor`, latency `0`      |
| Invalid values           | Kiểm tra từng điều kiện `validate()`   |
| Invalid full constructor | Constructor phải reject config sai     |
| Builder                  | Builder tạo config đúng                |
| Invalid Builder          | `build()` phải validate                |
| Properties round-trip    | Ghi rồi đọc lại không mất dữ liệu      |
| Partial properties       | Field thiếu giữ default                |
| `toString()`             | Có thông tin cấu hình cần thiết        |




## Các vấn đề QA phát hiện

Điểm tốt là `validate()` đã kiểm tra khá đầy đủ các giới hạn `> 0`, `>= 0`.

Tuy nhiên tôi phát hiện một lỗi QA đáng chú ý:

```
if (edgeMips <= 0)
```

không bắt được:

```
Double.NaN
Double.POSITIVE_INFINITY
Double.NEGATIVE_INFINITY
```

Ví dụ:

```
config.setEdgeMips(Double.NaN);
config.validate(); // hiện tại KHÔNG throw
```

