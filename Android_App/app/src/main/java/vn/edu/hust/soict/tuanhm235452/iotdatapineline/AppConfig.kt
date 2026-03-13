package vn.edu.hust.soict.tuanhm235452.iotdatapineline

object AppConfig {
    // 1. IP CỦA BẠN
    const val SERVER_IP = "YOUR_SERVER_IP"
    // 2. Cấu hình Server
    val MQTT_BROKER_URI = "tcp://$SERVER_IP:1883"
    val GRAFANA_BASE_URL = "http://$SERVER_IP:3000"
    // 3. Cấu hình UID của Grafana Dashboard
    const val UID_CHART_TEMP = "adpxbxd"     // UID cho màn hình Biểu đồ TEMP
    const val UID_CHART_HUMID = "adph8mx"
    const val UID_HISTORY = "adxqscq"   // UID cho màn hình Lịch sử bảng
}