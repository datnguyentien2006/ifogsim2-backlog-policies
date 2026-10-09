# SETUP — Cài đặt và chạy dự án `ifogsim2-backlog-policies`

Tài liệu này giúp một người **chưa từng thấy dự án** clone về và chạy được smoke test trong khoảng 10–15 phút.

## 0. Phiên bản chốt cho cả nhóm

| Thành phần | Phiên bản | Ghi chú |
| --- | --- | --- |
| **JDK** | **25 (duy nhất)** — khuyến nghị Eclipse Temurin 25 (LTS) | Không cần cài JDK 11; không dùng JDK khác để build dự án này |
| Eclipse | Eclipse IDE for Java Developers (bản mới nhất, có hỗ trợ Java 25) | Hoặc build bằng dòng lệnh, xem mục 4 |
| Git | bản bất kỳ gần đây | |

**Vì sao không dùng JDK 11 (hoặc 8):** project dùng `JavaSE-11` báo lỗi
`The type java.lang.Record cannot be resolved` và `The project was not built since its build path is incomplete`.
`java.lang.Record` chỉ có từ Java 16 trở lên, nên nguồn/thư viện trong project cần JDK mới hơn 11.
Cấu hình này đã được nhóm chạy thực tế với **JDK 25**, nên chốt đúng một phiên bản đó để mọi máy cho cùng kết quả.

> `.classpath` dùng JRE container theo **tên môi trường `JavaSE-25`**, không chứa đường dẫn/tên JDK của một máy cá nhân
> (bản cũ trỏ cứng `jdk-25.0.3.9-hotspot`, máy nào đặt tên JRE khác sẽ lỗi).

## 1. Cài đặt công cụ

1. Cài **JDK 25** (Adoptium/Temurin: https://adoptium.net). Kiểm tra: `java -version` phải in ra `25.x`.
2. Cài **Eclipse IDE for Java Developers**: https://www.eclipse.org/downloads/
3. Cài **Git**.

## 2. Clone repo

Chọn thư mục **không có dấu cách hoặc ký tự có dấu** trong đường dẫn (ví dụ `C:\MMT_261` hoặc `~/work`):

```bash
git clone https://github.com/datnguyentien2006/ifogsim2-backlog-policies.git
cd ifogsim2-backlog-policies
```

Repo là private: bạn cần được thêm làm collaborator và đăng nhập GitHub khi Git yêu cầu.

## 3. Mở trong Eclipse

1. **Khai báo JDK 25 cho Eclipse:** `Window → Preferences → Java → Installed JREs → Add… → Standard VM`, trỏ tới thư mục JDK 25 → Finish.
2. **Gắn JDK 25 với môi trường `JavaSE-25`:** `Window → Preferences → Java → Installed JREs → Execution Environments → JavaSE-25` → tick JDK 25 vừa thêm. (Thường Eclipse tự khớp; nếu thấy lỗi *"Unbound classpath container: JRE Container [JavaSE-25]"* thì bước này chưa xong.)
3. **Import project:** `File → Import → General → Existing Projects into Workspace` → chọn thư mục repo vừa clone → Finish. Repo đã có sẵn `.project` và `.classpath` — **không cần chỉnh Build Path hay sửa `.classpath` thủ công.**
4. Chuột phải project → **Refresh (F5)**, rồi `Project → Clean…`.
5. Kỳ vọng: không còn dấu đỏ trong Package Explorer (cảnh báo vàng thì bỏ qua).

JUnit 5 đã được khai báo sẵn trong `.classpath` (thư viện JUnit 5 đi kèm Eclipse), **không cần tải thêm jar** và không cần thêm gì thủ công.

## 4. Chạy thử

**Trong Eclipse:** mở `src/org/fog/hk261backlog/topology/BacklogTopologyTest.java` → chuột phải → `Run As → Java Application`.
Kết quả đúng sẽ in cấu hình mặc định, danh sách 4 thiết bị (`cloud`, `edge-0..2`) và dòng cuối **`Smoke test xong — CloudSim khởi động/dừng sạch.`**

**Bằng dòng lệnh (không cần Eclipse)**, chạy ở thư mục gốc repo với JDK 25:

Linux / macOS:

```bash
find src -name "*.java" > sources.txt
javac -encoding UTF-8 -cp "jars/*:jars/commons-math3-3.5/commons-math3-3.5.jar" -d out/production/iFogSim @sources.txt
java -cp "out/production/iFogSim:jars/*:jars/commons-math3-3.5/commons-math3-3.5.jar" org.fog.hk261backlog.topology.BacklogTopologyTest
```

Windows (cmd):

```bat
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -cp "jars/*;jars/commons-math3-3.5/commons-math3-3.5.jar" -d out/production/iFogSim @sources.txt
java -cp "out/production/iFogSim;jars/*;jars/commons-math3-3.5/commons-math3-3.5.jar" org.fog.hk261backlog.topology.BacklogTopologyTest
```

> `jars/*` chỉ lấy các file `.jar` trực tiếp trong `jars/`; `commons-math3-3.5.jar` nằm trong thư mục con nên **phải khai báo riêng** như trên.

**Chạy unit test:** trong Eclipse, chuột phải thư mục `test/` → `Run As → JUnit Test`. Hiện tại unit test chỉ chạy qua Eclipse; phần dòng lệnh ở trên chỉ build và chạy smoke test (trong `jars/` không có sẵn bộ chạy JUnit 5 đầy đủ).

## 5. Cấu trúc thư mục cần biết

```
src/org/fog/hk261backlog/   code của nhóm (topology, backlog, policy, disruption, metrics, experiment)
test/org/fog/hk261backlog/  JUnit test của nhóm
docs/                       ARCHITECTURE.md, INTERFACE.md, INTERFACE_CONTRACT.md, ADDING_NEW_POLICY.md, SETUP.md
jars/                       thư viện phụ thuộc (đã kèm sẵn)
results/                    kết quả thí nghiệm (CSV)
analysis/                   script Python phân tích
```

Mã nguồn gốc của iFogSim2 nằm trong cùng `src/` (các package `org.fog.*`, `org.cloudbus.*`).

## 6. Sự cố thường gặp

| Triệu chứng | Nguyên nhân | Cách xử lý |
| --- | --- | --- |
| `Unbound classpath container: JRE Container [JavaSE-25]` | Eclipse chưa có JDK 25 gắn với môi trường `JavaSE-25` | Làm lại mục 3, bước 1–2 (nếu Eclipse quá cũ không có `JavaSE-25`: cập nhật Eclipse) |
| `The type java.lang.Record cannot be resolved` / `The project was not built since its build path is incomplete` | Project đang dùng JDK cũ hơn 16 (ví dụ 11) | Dùng đúng `.classpath` trong repo (`JavaSE-25`), gắn JDK 25 như mục 3, rồi `Project → Clean` |
| Lỗi `invalid source release` / `class file has wrong version` | Đang dùng JDK khác 25 | Chọn JDK 25, `Project → Clean` |
| Hàng loạt lỗi về `module` / `Modulepath` | Eclipse bật chế độ module cho project | Dùng đúng `.classpath` trong repo (có `module=false`); `Properties → Java Build Path → Libraries` không được có mục nào dưới *Modulepath* |
| `ClassNotFoundException: org.apache.commons.math3...` khi chạy dòng lệnh | Thiếu jar trong thư mục con | Thêm `jars/commons-math3-3.5/commons-math3-3.5.jar` vào classpath |
| Test báo thiếu `org.junit.jupiter.api` | Eclipse quá cũ (chưa có JUnit 5) hoặc `.classpath` bị ghi đè | Cập nhật Eclipse bản mới; hoặc `Build Path → Add Libraries… → JUnit → JUnit 5`; khôi phục `.classpath` từ `main` |
| Máy khác báo lỗi vì đường dẫn JDK của bạn | Ai đó commit `.classpath` có đường dẫn cá nhân | Khôi phục `.classpath` từ `main`; **không commit thay đổi JRE trong `.classpath`** |

## 7. Quy ước khi làm việc nhóm

- Không commit thư mục `out/`, `bin/`, `*.class`, `sources.txt` (nên có trong `.gitignore`); **có** commit `.classpath` và `.project`.
- Mỗi người làm trên nhánh riêng, mở Pull Request; N1 duyệt và merge vào `main` hằng tuần.
- Trước khi mở PR: build sạch (`Project → Clean`) và chạy test của phần mình sửa.
