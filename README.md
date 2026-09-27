# iFogSim2 Backlog Policies — Mô phỏng & So sánh các chính sách điều phối gửi bù dữ liệu

> **Đồ án môn Mạng máy tính — HK261**
> Đề tài 2: Mô phỏng và so sánh các chính sách điều phối gửi bù dữ liệu cơ bản trên iFogSim2

---

## 📖 Giới thiệu

Khi một máy chủ biên mất kết nối rồi tái kết nối, dữ liệu tồn đọng thường được gửi dồn về trung tâm, gây áp lực lên hạ tầng mạng và hệ thống tiếp nhận. Đề tài này xây dựng một khung mô phỏng trên nền **iFogSim2** (chạy trên CloudSim, viết bằng Java) để:

- Mô hình hóa topology gồm **nhiều máy chủ biên** gửi dữ liệu về **một trung tâm tiếp nhận**.
- Mô phỏng các sự kiện **mất kết nối / tái kết nối** của biên, bao gồm cả kịch bản nhiều biên cùng khôi phục trong thời gian ngắn.
- Cài đặt **4 chính sách điều phối gửi bù** khác nhau và so sánh chúng trên cùng một bộ kịch bản tải.

---

## 🎯 Mục tiêu

1. Xây dựng khung mô phỏng iFogSim2 hoàn chỉnh, có thể **dùng lại và mở rộng**.
2. Cài đặt 4 chính sách gửi bù dữ liệu tồn đọng và so sánh công bằng trên cùng bộ kịch bản.
3. Đo lường định lượng các chỉ số: độ trễ, thông lượng, mức độ công bằng, dữ liệu bị trì hoãn quá lâu, mức sử dụng mạng.
4. Viết báo cáo phân tích ưu/nhược điểm từng chính sách theo từng miền tải.

---

## 📌 Bốn chính sách gửi bù

| # | Chính sách | Mô tả ngắn |
|---|-----------|------------|
| 1 | **FIFO** | Gửi tuần tự theo đúng thứ tự phát sinh — dữ liệu cũ nhất đi trước. |
| 2 | **Round-Robin** | Chia đều theo vòng — lần lượt gửi một phần của mỗi biên rồi quay vòng. |
| 3 | **Rate-Limit** | Giới hạn tốc độ gửi ở mức cố định, không đổi bất kể tồn đọng. |
| 4 | **Priority New Data** | Ưu tiên dữ liệu mới, chỉ dành hạn mức nhỏ cố định cho gửi bù dữ liệu cũ. |

---

## 🛠️ Công nghệ sử dụng

| Thành phần | Công cụ |
|-----------|---------|
| Mô phỏng chính | iFogSim2 (trên nền CloudSim) |
| Ngôn ngữ | Java (JRE 8 hoặc 11 — chốt theo phiên bản tương thích) |
| Kiểm thử | JUnit 5 (JUnit Jupiter) |
| IDE | Eclipse / IntelliJ IDEA |
| Hậu xử lý & vẽ biểu đồ | Python (pandas, numpy, matplotlib) |
| Quản lý phiên bản | Git |
| Tài liệu | Markdown |

---

## 📂 Cấu trúc dự án

ifogsim2-backlog-policies/
│
├── analysis/                                   # Script Python phân tích
│   ├── data_cleaning.py
│   ├── data_validation.py
│   └── plotting.py
│
├── docs/                                       # Tài liệu kỹ thuật
│   ├── ADDING_NEW_POLICY.md
│   ├── ARCHITECTURE.md
│   ├── INTERFACE.md
│   └── SETUP.md
│
├── report/                                     # Báo cáo
│   └── report.md
│
├── results/                                    # Kết quả thí nghiệm (CSV)
│   └── .gitkeep
│
├── src/
│   └── org/
│       └── fog/
│           └── hk261backlog/                   # ⭐ PACKAGE CỦA NHÓM
│               │
│               ├── topology/
│               │   └── TopologyConfig.java            (N1)
│               │
│               ├── backlog/
│               │   └── BacklogQueue.java              (N5)
│               │
│               ├── policy/
│               │   ├── SendingPolicy.java             (N4, N5 — chốt cùng nhau, N1 làm trọng tài)
│               │   ├── FifoPolicy.java                (N4)
│               │   ├── RateLimitPolicy.java           (N4)
│               │   ├── RoundRobinPolicy.java          (N5)
│               │   ├── PriorityNewDataPolicy.java     (N5)
│               │   └── MockBacklogGenerator.java      (N5)
│               │
│               ├── disruption/
│               │   ├── DisconnectionModel.java        (N3)
│               │   ├── ReconnectionScheduler.java     (N3)
│               │   └── DisconnectEventLog.java        (N3)
│               │
│               ├── metrics/
│               │   ├── MetricsLogger.java             (N6)
│               │   ├── MetricsSchema.java             (N6)
│               │   └── DelayedDataMetric.java         (N6)
│               │
│               └── experiment/
│                   ├── ExperimentMatrix.java          (N6)
│                   └── ExperimentRunner.java          (N6)
│
├── test/                                       # ⭐ JUNIT TEST CỦA NHÓM
│   └── org/
│       └── fog/
│           └── hk261backlog/
│               ├── TopologyConfigTest.java            (N2)
│               ├── SendingPolicyInterfaceTest.java    (N2)
│               ├── FifoPolicyTest.java                (N2)
│               ├── RateLimitPolicyTest.java            (N2)
│               ├── RoundRobinPolicyTest.java          (N2)
│               ├── PriorityNewDataPolicyTest.java     (N2)
│               ├── DisconnectionModelTest.java        (N2)
│               ├── ReconnectionSchedulerTest.java     (N2)
│               ├── BacklogQueueTest.java              (N2)
│               └── IntegrationTest.java               (N2)
│
├── jars/                                       # Thư viện phụ thuộc
│   ├── commons-math3-3.5/
│   │   └── commons-math3-3.5.jar
│   ├── commons-io-2.17.0.jar
│   ├── commons-lang3-3.17.0.jar
│   ├── commons-net-3.11.1.jar
│   ├── easymock-5.3.0.jar
│   ├── geoip2-4.2.0.jar
│   ├── guava-18.0.jar
│   ├── json-simple-1.1.1.jar
│   ├── junit-jupiter-engine-5.11.3.jar
│   ├── opencsv-5.9.jar
│   └── uncommons-maths-1.2.3.jar
│
├── slides/                                     # Slide demo (chưa xác nhận — hỏi giảng viên có yêu cầu bảo vệ/thuyết trình không)
│   └── slides.pptx
│
├── .gitignore
├── LICENSE.txt
└── README.md



---

## 👥 Phân công nhóm

| Thành viên | Vai trò | Phụ trách chính |
|-----------|--------|-----------------|
| **Người 1** | Topology & Integration Lead + Report Synthesizer | `TopologyConfig`, `BacklogTopologyTest`, `ExperimentConfig`, tích hợp luồng dữ liệu thật, merge báo cáo, Chương 1 + 4.4 + 6 |
| **Người 2** | QA & Cơ sở lý thuyết | Toàn bộ test suite, coverage report, `testing-reliability.md`, `theory-background.md` |
| **Người 3** | Module gián đoạn / tái kết nối | `DisconnectionModel`, `ReconnectionScheduler`, `DisconnectEventLog`, `disruption-model.md` |
| **Người 4** | Chính sách FIFO & Rate-limit | `FifoPolicy`, `RateLimitPolicy`, `policy-fifo-ratelimit.md` |
| **Người 5** | Chính sách Round-robin & Priority + Hàng đợi | `BacklogQueue`, `RoundRobinPolicy`, `PriorityNewDataPolicy`, `MockBacklogGenerator`, `policy-roundrobin-priority.md` |
| **Người 6** | Đo lường & Thí nghiệm | `MetricsLogger`, `MetricsSchema`, `DelayedDataMetric`, `ExperimentMatrix`, `ExperimentRunner`, `plotting.py`, CSV + biểu đồ |

---

## ⏱️ Lộ trình 6 giai đoạn (Tuần 39 → 50)

| Giai đoạn | Tuần | Nội dung chính |
|-----------|------|----------------|
| **GĐ1 — SETUP + INT** | 39 | Dựng môi trường, công bố skeleton `TopologyConfig`, `BacklogTopologyTest`, `SETUP.md` |
| **GĐ2 — CODE CHÍNH** | 40–42 | Chốt interface `SendingPolicy`, code 4 policies + module gián đoạn + topology; N1 nối luồng dữ liệu thật (T42) |
| **GĐ3 — TEST + INTEGRATION** | 43–44 | Test suite đầy đủ + `IntegrationTest` + coverage report |
| **GĐ4 — CHẠY THÍ NGHIỆM** | 45–46 | N6 code Metrics + Experiment, chạy ma trận, xuất CSV + biểu đồ |
| **GĐ5 — VIẾT BÁO CÁO** | 46–48 | 5 người viết draft báo cáo phần mình; N1 merge lần 1 |
| **GĐ6 — DEMO + NỘP** | 49–50 | Rà soát chung, export `final_report.docx`, demo |

---

## 🚀 Cài đặt & Chạy

### Yêu cầu môi trường

- **Java:** JRE/JDK 8 hoặc 11 (phiên bản chính xác xem `docs/SETUP.md`)
- **Python:** 3.8+ với `pandas`, `numpy`, `matplotlib`
- **IDE:** Eclipse hoặc IntelliJ IDEA
- **Git**

### Các bước cài đặt

```bash
# 1. Clone repository
git clone <repo-url>
cd ifogsim2-backlog-policies

# 2. Mở project trong Eclipse/IntelliJ
#    - Đảm bảo .classpath dùng JRE container theo tên (không trỏ cứng JDK cá nhân)
#    - Thêm các thư viện trong jars/ vào Build Path

# 3. Cài đặt thư viện Python
pip install pandas numpy matplotlib
