#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include "DHT.h"
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "FS.h"
#include "SD.h"
#include "SPI.h"
#include "time.h" 

// --- CẤU HÌNH CHÂN SPI CỨNG ---
#define SD_CS_PIN 5
#define SD_SCK_PIN 18
#define SD_MISO_PIN 19
#define SD_MOSI_PIN 23

SPIClass spiSD(VSPI); // Tạo đối tượng SPI riêng
// Cấu hình NTP (Giờ Việt Nam là UTC+7)
const char* ntpServer = "pool.ntp.org";
const long  gmtOffset_sec = 7 * 3600; // GMT+7
const int   daylightOffset_sec = 0;

// Cấu hình chân Thẻ nhớ (SPI)
#define SD_CS_PIN 5

// --- 1. CẤU HÌNH PHẦN CỨNG MỚI (OLED, LED, BUZZER) ---
#define SCREEN_WIDTH 128 
#define SCREEN_HEIGHT 64 
#define OLED_RESET    -1 
// QUAN TRỌNG: Thêm địa chỉ 0x3C vào đây để OLED nhận diện chính xác
Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);


#define BUTTON_MUTE_PIN 13 // Chân D13 nối nút bấm
#define BUZZER_PIN 15     // Chân còi
#define LED_RED 27       // Đèn đỏ
#define LED_GREEN 26     // Đèn xanh
float TEMP_LIMIT = 35; // Ngưỡng nhiệt độ báo động
float HUM_LIMIT = 95;

// --- 2. CẤU HÌNH WIFI ---


const char* ssid = "YOUR_WIFI_SSID";        // << SỬA TÊN WIFI
const char* password = "YOUR_WIFI_PASSWORD";       // << SỬA PASS WIFI

// --- 3. CẤU HÌNH MQTT ---
// IP của máy tính chạy Node-RED (Phải cùng mạng Wifi với ESP32)
const char* mqtt_server = "YOUR_MQTT_BROKER_IP";     // << SỬA THÀNH IP MÁY TÍNH BẠN
const int mqtt_port = 1883;
const char* mqtt_topic_temp = "phong_hoc/nhiet_do"; // Topic y hệt trong Node-RED/Android
const char* mqtt_topic_hum  = "phong_hoc/do_am";
const char* status_topic = "phong_hoc/trang_thai";
const char* topic_coi = "phong_hoc/coi";
const char* topic_limit = "phong_hoc/nguong_canh_bao";
const char* topic_history = "phong_hoc/lich_su";
// Topic mới để nhận lệnh tắt tiếng từ App
const char* topic_mute = "phong_hoc/tat_tieng"; 
// Topic để gửi trạng thái hiện tại (cho App đồng bộ)
const char* topic_current_limit = "phong_hoc/nguong_hien_tai";
// --- THÊM MỚI CHO ĐỘ ẨM ---
const char* topic_limit_hum = "phong_hoc/nguong_canh_bao_do_am"; // Topic nhận lệnh cài đặt
const char* topic_current_limit_hum = "phong_hoc/nguong_do_am_hien_tai"; // Topic gửi trạng thái về App

// --- 3. CẤU HÌNH CẢM BIẾN ---
#define DHTPIN 4        // Chân D4 nối với chân Data của cảm biến
// --- SỬA LỖI TẠI ĐÂY ---
#define DHTTYPE DHT22   // DHT 22 (AM2302)

DHT dht(DHTPIN, DHTTYPE);
WiFiClient espClient;
PubSubClient client(espClient);

// BIẾN QUAN TRỌNG: Trạng thái kích hoạt hệ thống báo động
// true = Đang bật chế độ tự động (Default)
// false = Đã tắt báo động (Im lặng)
bool alarm_system_enabled = true;
// Thêm biến quản lý tắt tiếng tạm thời
bool is_muted = false;


void setup_wifi();
void reconnect();
void callback(char* topic, byte* payload, unsigned int length);
void initSDCard();
void logToSD(float t, float h);
void syncOfflineData();

void setup() {
  Serial.begin(115200);
  // -- Setup thiết bị ra (Output) --
  pinMode(BUZZER_PIN, OUTPUT);
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  digitalWrite(LED_GREEN, HIGH); // Mặc định đèn xanh sáng
  // (Trạng thái bình thường là HIGH, khi bấm sẽ xuống LOW)
  pinMode(BUTTON_MUTE_PIN, INPUT_PULLUP);

  // -- Khởi động OLED --
  // Thêm dòng Wire.begin() để chắc chắn I2C chạy
  Wire.begin(); 
  
  // Sửa lại hàm khởi động cho đúng địa chỉ 0x3C
  if(!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) { 
    Serial.println(F("Khong tim thay OLED"));
    // Không dùng for(;;) để tránh treo nếu lỏng dây, vẫn cho chạy tiếp để debug Serial
  } else {
    Serial.println(F("OLED OK!")); // Báo lên Serial nếu màn hình nhận
  }
  
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1); // Đặt cỡ chữ mặc định
  display.setCursor(10,20);
  display.println("Dang ket noi...");
  display.display();

  // Khởi động dht
  dht.begin();

  // Khởi động thẻ nhớ
  initSDCard();

  // Kết nối Wifi
  setup_wifi();

  // Lấy giờ từ Internet ngay khi có mạng
  configTime(gmtOffset_sec, daylightOffset_sec, ntpServer);
  Serial.println("Dang dong bo thoi gian...");

  // Cấu hình MQTT Server
  client.setServer(mqtt_server, mqtt_port);
  client.setCallback(callback);

  client.setKeepAlive(5);      // Giảm thời gian kiểm tra sống còn xuống 5 giây (Mặc định là 15)
  client.setSocketTimeout(5);  // Giảm thời gian chờ phản hồi mạng xuống 5 giây
}

// --- HÀM KHỞI TẠO THẺ NHỚ (UPDATE) ---
void initSDCard() {
  Serial.print("Dang khoi tao SD Card... ");
  
  // Khởi động giao tiếp SPI với chân cụ thể
  spiSD.begin(SD_SCK_PIN, SD_MISO_PIN, SD_MOSI_PIN, SD_CS_PIN);
  
  // Thử kết nối với tần số thấp trước (4000000) cho ổn định nếu dây dài
  if (!SD.begin(SD_CS_PIN, spiSD, 4000000)) {
    Serial.println("THAT BAI! (Loi vat ly/Day long)");
    return;
  }
  Serial.println("THANH CONG!");
}

// Hàm lấy thời gian hiện tại (Epoch Time - Số giây tính từ năm 1970)
unsigned long getEpochTime() {
  time_t now;
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) {
    return 0; // Trả về 0 nếu lỗi (chưa lấy được giờ)
  }
  time(&now);
  return now;
}

// --- HÀM GHI DỮ LIỆU OFFLINE (UPDATE: TỰ CỨU HỘ MẠNH HƠN) ---
void logToSD(float t, float h) {
  if(isnan(t) || isnan(h)) return;

  // Lấy thời gian (Code cũ giữ nguyên logic lấy epochTime của bạn ở đây)
  unsigned long epochTime = getEpochTime();
  
  File file = SD.open("/offline_log.txt", FILE_APPEND);
  
  // NẾU LỖI: THỰC HIỆN "CẤP CỨU" PHẦN CỨNG
  if(!file){
    Serial.println("-> LOI: The nho bi mat ket noi! Dang khoi dong lai SPI...");
    
    SD.end(); // Ngắt kết nối cũ
    
    // Mẹo: Đẩy chân CS lên cao để Reset thẻ
    pinMode(SD_CS_PIN, OUTPUT);
    digitalWrite(SD_CS_PIN, HIGH);
    delay(100);
    
    // Khởi động lại SPI từ đầu
    spiSD.begin(SD_SCK_PIN, SD_MISO_PIN, SD_MOSI_PIN, SD_CS_PIN);
    
    if (SD.begin(SD_CS_PIN, spiSD)) {
      Serial.println("-> KHOI PHUC THANH CONG!");
      file = SD.open("/offline_log.txt", FILE_APPEND);
      if(file) {
        // Ghi dữ liệu (Nhớ thêm logic thời gian của bạn vào đây)
        String dataLine = String(epochTime) + "," + String(t, 1) + "," + String(h, 2);
        file.println(dataLine);
        file.close();
        Serial.print("Da ghi Offline (Sau khi cuu ho): "); Serial.println(dataLine);
      }
    } else {
      Serial.println("-> THAT BAI: Day nguon/GND qua long hoac sut ap!");
    }
    return;
  }
  
  // Ghi bình thường
  String dataLine = String(epochTime) + "," + String(t, 1) + "," + String(h, 2);
  file.println(dataLine);
  file.close();
  Serial.print("Da ghi Offline: "); Serial.println(dataLine);
}

// --- HÀM ĐỒNG BỘ DỮ LIỆU KHI CÓ MẠNG ---
void syncOfflineData() {
  if(!SD.exists("/offline_log.txt")) return; // Không có file thì thôi

  Serial.println(">>> PHAT HIEN DU LIEU OFFLINE! DANG DONG BO...");
  File file = SD.open("/offline_log.txt");
  
  if(file){
    while(file.available()){
      String line = file.readStringUntil('\n');
      line.trim(); // Xóa khoảng trắng thừa
      if(line.length() > 5){ // >5 để tránh dòng trống
        // Gửi nguyên chuỗi "Timestamp,Temp" lên topic lịch sử
        client.publish(topic_history, line.c_str());
        Serial.print("Da gui bu: "); Serial.println(line);
        delay(100); // Nghỉ xíu để Broker kịp thở
      }
    }
    file.close();
    // Gửi xong thì xóa file đi để không gửi lại lần sau
    SD.remove("/offline_log.txt");
    Serial.println(">>> DONG BO HOAN TAT! DA XOA FILE LOG.");
  }
}

// --- HÀM XỬ LÝ LỆNH TỪ ĐIỆN THOẠI ---
void callback(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (int i = 0; i < length; i++) message += (char)payload[i];
  
  Serial.print("Nhan lenh: "); Serial.println(message);

  if (String(topic) == topic_coi) {
    if (message == "ON") {
      alarm_system_enabled = true;
      Serial.println("-> DA KICH HOAT CHE DO CANH BAO");
    } else if (message == "OFF") {
      alarm_system_enabled = false;
      Serial.println("-> DA TAT (VO HIEU HOA) CANH BAO");
    }
    // Gửi lại trạng thái có RETAIN = true để App đồng bộ
    client.publish("phong_hoc/trang_thai_coi", alarm_system_enabled ? "ON" : "OFF", true);
  }
  // --- THAY ĐỔI 3: XỬ LÝ CẬP NHẬT NGƯỠNG ---
  else if (String(topic) == topic_limit) {
    // Chuyển chuỗi nhận được thành số thực (float)
    float new_limit = message.toFloat();
    // Kiểm tra tính hợp lệ (VD: không thể đặt ngưỡng 0 độ hoặc 1000 độ)
    if (new_limit > 0 && new_limit < 100) {
      TEMP_LIMIT = new_limit;
      Serial.print("-> DA CAP NHAT NGUONG MOI: ");
      Serial.println(TEMP_LIMIT);
      // Gửi lại ngưỡng mới có RETAIN = true
      client.publish(topic_current_limit, String(TEMP_LIMIT).c_str(), true);
    }
  }
  // --- THÊM ĐOẠN NÀY VÀO SAU ĐOẠN XỬ LÝ NHIỆT ĐỘ ---
  else if (String(topic) == topic_limit_hum) {
    float new_hum_limit = message.toFloat();
    if (new_hum_limit > 0 && new_hum_limit <= 100) {
      HUM_LIMIT = new_hum_limit;
      Serial.print("-> DA CAP NHAT NGUONG DO AM MOI: ");
      Serial.println(HUM_LIMIT);
      // Gửi lại ngưỡng mới có RETAIN = true để App đồng bộ
      client.publish(topic_current_limit_hum, String(HUM_LIMIT).c_str(), true);
    }
  }
  else if (String(topic) == topic_mute) {
    if (message == "MUTE") {
      is_muted = true;
      Serial.println("-> DA TAT TIENG (MUTE)");
      digitalWrite(BUZZER_PIN, LOW); // Tắt còi ngay lập tức
    }
    else if (message == "UNMUTE") {
      
      is_muted = false; 
      Serial.println("-> DA MO LAI TIENG (UNMUTE)");
      // Không cần digitalWrite(HIGH) ở đây
      // Vì vòng lặp loop() tiếp theo sẽ tự kiểm tra nhiệt độ và bật còi lên lại
    }
  }
}

void setup_wifi() {
  delay(10);
  Serial.println();
  Serial.print("Dang ket noi Wifi: ");
  Serial.println(ssid);

  WiFi.begin(ssid, password);

  if (WiFi.status() != WL_CONNECTED) {
    delay(1000);
    //Serial.print(".");
    Serial.println("Ket noi wifi khong thanh cong!");
  }
  else{
    Serial.println("");
    Serial.println("Da ket noi Wifi!");
    // Hiện lên OLED đã kết nối
    display.clearDisplay();
    display.setCursor(0,0);
    display.println("WIFI OK!"); // Thêm text báo wifi ok

    Serial.print("IP address: ");
    Serial.println(WiFi.localIP());
    display.println(WiFi.localIP()); // Hiện IP lên màn hình
    display.display();
  }
}

// Biến lưu thời gian thử kết nối lần cuối
unsigned long lastMqttReconnectAttempt = 0;

void reconnect() {
  // Chỉ thử kết nối khi CÓ WIFI và CHƯA CÓ MQTT
  if (WiFi.status() == WL_CONNECTED && !client.connected()) {
    
    unsigned long now = millis();
    // Cứ 5 giây mới được thử kết nối lại 1 lần (để dành thời gian cho việc đo và ghi thẻ)
    if (now - lastMqttReconnectAttempt > 5000) {
      lastMqttReconnectAttempt = now;
      
      Serial.print("Dang thu ket noi MQTT...");
      String clientId = "ESP32_PhongHoc_" + String(random(1000, 9999));
      
      // Thử kết nối (KHÔNG DÙNG WHILE ĐỂ CHẶN)
      if (client.connect(clientId.c_str(), status_topic, 0, true, "OFFLINE")) {
        Serial.println("THANH CONG!");
        client.publish(status_topic, "ONLINE", true);
        client.subscribe(topic_coi);
        client.subscribe(topic_limit);
        client.subscribe(topic_limit_hum);
        client.subscribe(topic_mute); 
        // GỬI TRẠNG THÁI HIỆN TẠI LÊN (RETAINED) ĐỂ APP VỪA VÀO LÀ THẤY NGAY
        client.publish(topic_current_limit, String(TEMP_LIMIT).c_str(), true);
        client.publish("phong_hoc/trang_thai_coi", alarm_system_enabled ? "ON" : "OFF", true);
        client.publish(topic_current_limit_hum, String(HUM_LIMIT).c_str(), true);
        
        // Đồng bộ dữ liệu ngay khi có lại
        syncOfflineData();
      } else {
        Serial.print("That bai, rc=");
        Serial.print(client.state());
        Serial.println(" -> Se thu lai sau 5s");
      }
    }
  }
}

void loop() {
  // Kiểm tra kết nối
  if(WiFi.status() == WL_CONNECTED){
    if (!client.connected()) {
      reconnect();
    }
    else{
      client.loop();
    }
    
  }

  // --- XỬ LÝ NÚT BẤM CỨNG (CHẾ ĐỘ TOGGLE: BẬT/TẮT) ---
  if (digitalRead(BUTTON_MUTE_PIN) == LOW) {
    delay(50); // Chống rung phím
    if (digitalRead(BUTTON_MUTE_PIN) == LOW) {
      
      // Đảo ngược trạng thái: Đang tắt thành bật, đang bật thành tắt
      is_muted = !is_muted; 

      if (is_muted) {
        Serial.println("-> Nut cung: DA TAT TIENG");
        digitalWrite(BUZZER_PIN, LOW); // Tắt còi ngay
        client.publish(topic_mute, "MUTE"); // Báo App biết là đã tắt
      } 
      else {
        Serial.println("-> Nut cung: DA BAT TIENG LAI");
        // Nếu nhiệt độ đang cao thì còi sẽ tự kêu lại ở vòng loop tiếp theo
        // Gửi báo App biết (nếu cần xử lý thêm trên App)
        client.publish(topic_mute, "UNMUTE"); 
      }

      // Chờ nhả nút (Tránh bấm 1 phát nó nhảy liên tục)
      while(digitalRead(BUTTON_MUTE_PIN) == LOW) { delay(10); }
    }
  }

  // --- ĐỌC CẢM BIẾN VÀ GỬI DỮ LIỆU ---
  // Đọc sau mỗi 2 giây (DHT không đọc nhanh quá được)
  static unsigned long lastMsg = 0;
  unsigned long now = millis();
  
  if (now - lastMsg > 2000) {
    lastMsg = now;
    
    float t = dht.readTemperature(); // Đọc nhiệt độ
    float h = dht.readHumidity();

    // Kiểm tra xem đọc có lỗi không (NaN = Not a Number)
    if (isnan(t) || isnan(h)) {
      Serial.println("Loi doc cam bien DHT!");
      // Hiển thị lỗi lên màn hình để dễ biết
      display.clearDisplay();
      display.setCursor(0,0);
      display.println("LOI CAM BIEN!");
      display.display();
      return;
    }
    
    // --- HIỂN THỊ OLED & CẢNH BÁO TẠI CHỖ (Luôn chạy dù có mạng hay không) ---
    bool qua_nhiet = (t > TEMP_LIMIT);
    bool qua_am = (h > HUM_LIMIT);
    // Hệ thống báo động khi 1 trong 2 vượt ngưỡng
    bool canh_bao = (qua_nhiet || qua_am);
    // 3. HIỂN THỊ OLED (Mới thêm)
    display.clearDisplay();
    
    // Dòng 1: Trạng thái
    display.setTextSize(1);
    display.setCursor(0,0);
    if(qua_nhiet && qua_am) display.print("!CANH BAO QUA NHIET,DO AM CAO!");
    else if(qua_nhiet) display.print("!CANH BAO QUA NHIET!");
    else if(qua_am) display.print("!CANH BAO DO AM CAO!");
    else{
      display.print("-AN TOAN-");
      is_muted = false;
    }
    if (alarm_system_enabled) {
      display.print("COI: ON"); // Đang bật canh gác
    } else {
      display.print("COI: OFF");  // Đã vô hiệu hóa
    }
    // -- Hiển thị Nhiệt độ --
    display.setTextSize(1);
    display.setCursor(0, 20);
    display.print("Nhiet do: ");
    display.setTextSize(2);      // Số to cho dễ nhìn
    display.print(t, 1);
    display.setTextSize(1);
    display.print(" C");

    // -- Hiển thị Độ ẩm (MỚI) --
    display.setCursor(0, 40);    // Xuống dòng dưới
    display.print("Do am:    ");
    display.setTextSize(2);      // Số to
    display.print(h, 1);
    display.setTextSize(1);
    display.print(" %");

    // Dòng 3: Trạng thái MQTT
    display.setTextSize(1);
    display.setCursor(0, 56);

    if (canh_bao && alarm_system_enabled) {
      digitalWrite(LED_RED, HIGH);    
      digitalWrite(LED_GREEN, LOW); 
      // Còi chỉ kêu khi CHƯA bị tắt tiếng (Mute)
      if (!is_muted) {
        digitalWrite(BUZZER_PIN, HIGH);
      } else {
        digitalWrite(BUZZER_PIN, LOW); // Đã bấm nút tắt tiếng
      }

    } else {
      digitalWrite(BUZZER_PIN, LOW); 
      digitalWrite(LED_RED, LOW);    
      digitalWrite(LED_GREEN, HIGH); 
    }

    String tempStr = String(t, 1); 
    String humStr = String(h, 1);
    Serial.println("Nhiet do: " + tempStr + " C, " + " | Do am: " + humStr + " %");
  // KIỂM TRA NGHIÊM NGẶT: Phải có cả Wifi VÀ MQTT mới gọi là Online
    if (WiFi.status() == WL_CONNECTED && client.connected()) {
      // TRƯỜNG HỢP 1: HOÀN HẢO
      // Thử gửi nhiệt độ và LẤY KẾT QUẢ TRẢ VỀ (True/False)
      bool gui_temp_ok = client.publish(mqtt_topic_temp, tempStr.c_str());
      bool gui_hum_ok  = client.publish(mqtt_topic_hum, humStr.c_str());
      // Nếu gửi THÀNH CÔNG cả 2
      if (gui_temp_ok && gui_hum_ok) {
        display.print("MQTT: ONLINE");
      }
      else{
        Serial.println("-> Gửi MQTT thất bại! Chuyển sang ghi thẻ nhớ...");
        client.disconnect(); // Chủ động ngắt luôn để ép kết nối lại
        logToSD(t, h);          // Ghi thẻ ngay lập tức
        display.print("ERR: LOI GUI TIN");
      }
    } 
    else {
      // TRƯỜNG HỢP 2: CÓ SỰ CỐ (Mất Wifi HOẶC Mất MQTT)
      logToSD(t, h); // Ghi vào thẻ nhớ
      
      if (WiFi.status() != WL_CONNECTED) {
        display.print("ERR: MAT WIFI (REC)"); // Báo lỗi Wifi
      } else {
        display.print("ERR: LOI MQTT (REC)"); // Báo lỗi Broker (Wifi vẫn có)
      }
    }
    
    display.display();
  }
}
