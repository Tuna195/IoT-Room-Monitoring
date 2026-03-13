# 🎓 Hệ thống Giám sát Môi trường Phòng học IoT (Custom Backend)

Một hệ thống IoT tự chủ (Self-hosted) được xây dựng từ A-Z (Phần cứng - Backend - Mobile App) nhằm giám sát nhiệt độ, độ ẩm và cảnh báo an toàn thời gian thực. Dự án được thực hiện trong khuôn khổ học phần **Project I**.

## 🌟 Tính năng nổi bật

* **Phần cứng (Cơ chế Fail-safe):** Thu thập dữ liệu qua cảm biến DHT22, hiển thị tại chỗ bằng OLED 0.96". Tích hợp cơ chế tự động ghi log vào Thẻ nhớ MicroSD khi mất kết nối mạng.
* **Backend (Dockerized & Data Pipeline):** Trung chuyển tin nhắn qua Mosquitto MQTT, xử lý logic đồng bộ thời gian (Merge Data) bằng Node-RED và lưu trữ chuỗi thời gian hiệu năng cao với InfluxDB. Cung cấp API xuất báo cáo Excel chuẩn hóa.
* **Mobile App (Foreground Service):** Ứng dụng Android Native (Kotlin) có khả năng chạy ngầm 24/7. Tự động cảnh báo rung và thông báo đẩy khi vượt ngưỡng an toàn. Tích hợp trực tiếp biểu đồ Grafana qua WebView.

## 🛠 Công nghệ sử dụng

* **Thiết bị biên (Edge Device):** ESP32 WROOM, DHT22, OLED SSD1306, MicroSD Module, Buzzer.
* **Hạ tầng Server (Backend):** Node-RED, InfluxDB, Mosquitto MQTT, Grafana (Triển khai 100% qua Docker).
* **Ứng dụng di động:** Android SDK, Kotlin, Paho MQTT Client, Foreground Service.

## 📸 Hình ảnh dự án
*(Bạn hãy thay link ảnh thực tế của bạn vào đây sau khi push code nhé)*
* [Ảnh chụp thiết bị phần cứng]
* [Ảnh chụp màn hình App (Xanh/Đỏ)]
* [Ảnh chụp Dashboard Grafana trên máy tính]

---

## 🚀 Hướng dẫn cài đặt & Chạy dự án (Quick Start)

Dự án được thiết kế theo nguyên tắc "Clean Code", giúp bạn dễ dàng triển khai lại trên hệ thống mạng của mình chỉ với vài thao tác cấu hình.

### Bước 1: Khởi chạy Server Backend
1. Cài đặt Docker và Docker Compose trên máy tính/VPS của bạn.
2. Mở Terminal tại thư mục `Server_Backend` và chạy lệnh:
   ```bash
   docker-compose up -d
3. Truy cập Node-RED (http://localhost:1880) và Grafana (http://localhost:3000) để import các flow/dashboard có sẵn.

Bước 2: Nạp Firmware cho ESP32
Mở file main.cpp trong thư mục ESP32_Firmware và thay đổi các thông số mạng của bạn:

C++
const char* ssid = "YOUR_WIFI_SSID";         // Điền tên WiFi
const char* password = "YOUR_WIFI_PASSWORD"; // Điền mật khẩu WiFi
const char *mqtt_server = "YOUR_SERVER_IP";  // Điền IP máy chạy Docker (VD: 192.168.1.10)
Sau đó nạp code xuống ESP32.

Bước 3: Cấu hình Ứng dụng Android (Chỉ cần sửa 1 file duy nhất)
Toàn bộ thông số kết nối của App đã được tập trung tại file AppConfig.kt. Bạn chỉ cần mở file này ra và thay đổi địa chỉ IP cũng như UID của Grafana (nếu bạn tạo Dashboard mới):

Kotlin
// Đường dẫn: app/src/main/java/.../AppConfig.kt

object AppConfig {
    // 1. Thay đổi IP máy chủ của bạn tại đây
    const val SERVER_IP = "YOUR_SERVER_IP" // VD: "192.168.1.10"

    // 2. Cấu hình Server (Tự động cập nhật theo IP)
    val MQTT_BROKER_URI = "tcp://$SERVER_IP:1883"
    val GRAFANA_BASE_URL = "http://$SERVER_IP:3000"

    // 3. Thay đổi UID của Grafana Dashboard (Lấy trên thanh URL của Grafana)
    const val UID_CHART_TEMP = "adpxbxd"    // UID cho biểu đồ Nhiệt độ
    const val UID_CHART_HUMID = "adph8mx"   // UID cho biểu đồ Độ ẩm
    const val UID_HISTORY = "adxqscq"       // UID cho bảng Lịch sử
}
Sau khi sửa file AppConfig.kt, bạn chỉ cần Build App và cài đặt file .apk vào điện thoại là hệ thống sẽ đồng bộ hoàn toàn!
