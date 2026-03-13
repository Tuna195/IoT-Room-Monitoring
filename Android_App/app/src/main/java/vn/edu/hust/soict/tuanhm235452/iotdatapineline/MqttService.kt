package vn.edu.hust.soict.tuanhm235452.iotdatapineline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttService : Service() {

    private lateinit var mqttClient: MqttAndroidClient

    private val serverUri = AppConfig.MQTT_BROKER_URI // <--- KIỂM TRA LẠI IP CỦA BẠN

    // --- KHAI BÁO TÊN BROADCAST ĐỂ GỬI TIN SANG HOME ---
    companion object {
        const val ACTION_MQTT_STATUS = "vn.edu.hust.iot.MQTT_STATUS"
        const val EXTRA_STATUS = "status" // "CONNECTED" hoặc "DISCONNECTED"
    }

    private val TOPIC_TEMP = "phong_hoc/nhiet_do"
    private val TOPIC_HUMID = "phong_hoc/do_am"
    private val TOPIC_MUTE = "phong_hoc/tat_tieng"
    private val TOPIC_LIMIT_TEMP = "phong_hoc/nguong_canh_bao"
    private val TOPIC_LIMIT_HUMID = "phong_hoc/nguong_canh_bao_do_am"

    private var isMuted = false
    private var currentTemp = 0f
    private var currentHumid = 0f
    private var limitTemp = 35.0f
    private var limitHumid = 90.0f
    private var wasDangerBefore = false

    private val CHANNEL_ID = "Kenh_Canh_Bao_Moi_V3"
    private val NOTIF_ID = 1

    override fun onBind(intent: Intent?): IBinder? { return null }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Đang kết nối server...", false, false))
        connectMQTT()
    }

    private fun connectMQTT() {
        val clientId = MqttClient.generateClientId() + "_Service"
        mqttClient = MqttAndroidClient(this, serverUri, clientId)

        try {
            val options = MqttConnectOptions()
            options.isAutomaticReconnect = true // Tự động kết nối lại
            options.isCleanSession = false
            // Giảm thời gian chờ để phát hiện mất mạng nhanh hơn (mặc định là 60s)
            options.keepAliveInterval = 6

            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d("MqttService", "Kết nối lần đầu OK")
                    // (Lưu ý: Logic chính sẽ nằm ở connectComplete bên dưới)
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e("MqttService", "Lỗi kết nối: ${exception?.message}")
                    sendStatusBroadcast("DISCONNECTED") // Báo lỗi ngay
                }
            })

            // SỬ DỤNG MqttCallbackExtended ĐỂ BẮT SỰ KIỆN TỰ KẾT NỐI LẠI
            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d("MqttService", "Đã kết nối thành công! (Reconnect: $reconnect)")
                    sendStatusBroadcast("CONNECTED") // <--- GỬI TIN: ĐÃ KẾT NỐI
                    subscribeTopics()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.e("MqttService", "Mất kết nối Server!")
                    sendStatusBroadcast("DISCONNECTED") // <--- GỬI TIN: MẤT KẾT NỐI
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = message.toString()
                    when(topic) {
                        TOPIC_TEMP -> {
                            currentTemp = payload.toFloatOrNull() ?: 0f
                            checkSafety()
                        }
                        TOPIC_HUMID -> {
                            currentHumid = payload.toFloatOrNull() ?: 0f
                            checkSafety()
                        }
                        TOPIC_LIMIT_TEMP -> {
                            limitTemp = payload.toFloatOrNull() ?: 35.0f
                            checkSafety()
                        }
                        TOPIC_LIMIT_HUMID -> {
                            limitHumid = payload.toFloatOrNull() ?: 90.0f
                            checkSafety()
                        }
                        TOPIC_MUTE -> {
                            if (payload == "MUTE") {
                                isMuted = true
                                stopVibration()
                            } else if (payload == "UNMUTE") isMuted = false
                        }
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- HÀM GỬI BROADCAST SANG HOME FRAGMENT ---
    private fun sendStatusBroadcast(status: String) {
        val intent = Intent(ACTION_MQTT_STATUS)
        intent.putExtra(EXTRA_STATUS, status)
        sendBroadcast(intent) // Bắn tin đi toàn hệ thống

        // Cập nhật luôn thông báo trên thanh trạng thái
        if (status == "DISCONNECTED") {
            val notif = buildNotification("Mất kết nối đến Server!", false, false)
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
        }
    }

    private fun subscribeTopics() {
        try {
            mqttClient.subscribe(arrayOf(TOPIC_TEMP, TOPIC_HUMID, TOPIC_MUTE, TOPIC_LIMIT_TEMP, TOPIC_LIMIT_HUMID), intArrayOf(0,0,0,0,0))
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ... (GIỮ NGUYÊN CÁC HÀM checkSafety, buildNotification, forceVibration, stopVibration CŨ CỦA BẠN Ở ĐÂY) ...
    // Bạn chỉ cần copy lại phần logic checkSafety từ đoạn code trước vào đây là được.

    // --- (Paste lại các hàm phụ trợ vào đây để code đầy đủ) ---
    private fun checkSafety() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isHot = currentTemp > limitTemp
        val isWet = currentHumid > limitHumid
        val isDangerNow = isHot || isWet

        if (isDangerNow) {
            val isNewTrigger = !wasDangerBefore
            var msg = ""
            if (isHot && isWet) msg = "NGUY HIỂM KÉP! Nhiệt: $currentTemp°C, Ẩm: $currentHumid%"
            else if (isHot) msg = "CẢNH BÁO QUÁ NHIỆT! Hiện tại: $currentTemp°C"
            else if (isWet) msg = "CẢNH BÁO ĐỘ ẨM CAO! Hiện tại: $currentHumid%"

            val notif = buildNotification(msg, true, isNewTrigger)
            notificationManager.notify(NOTIF_ID, notif)
            if (!isMuted) forceVibration()
            wasDangerBefore = true
        } else {
            if (wasDangerBefore) {
                stopVibration()
                isMuted = false
            }
            val statusMsg = "Ổn định: $currentTemp°C | $currentHumid%"
            val notif = buildNotification(statusMsg, false, false)
            notificationManager.notify(NOTIF_ID, notif)
            wasDangerBefore = false
        }
    }

    // ... (Giữ nguyên buildNotification, forceVibration, stopVibration, createNotificationChannel...)

    private fun buildNotification(content: String, isWarning: Boolean, forceAlert: Boolean): android.app.Notification {
        val title = if (isWarning) "⚠️ CẢNH BÁO MÔI TRƯỜNG!" else "Giám sát hệ thống IoT"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MAX)
        builder.setOnlyAlertOnce(!forceAlert)
        if (isWarning) {
            builder.setColor(Color.RED)
            builder.setLights(Color.RED, 1000, 1000)
        }
        return builder.build()
    }

    private fun forceVibration() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (v.hasVibrator()) {
            val timings = longArrayOf(0, 1000, 500, 1000)
            if (Build.VERSION.SDK_INT >= 26) {
                val effect = VibrationEffect.createWaveform(timings, -1)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                v.vibrate(effect, audioAttributes)
            } else { v.vibrate(timings, -1) }
        }
    }

    private fun stopVibration() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Kênh Cảnh Báo IoT V3", NotificationManager.IMPORTANCE_HIGH)
            serviceChannel.enableVibration(true)
            serviceChannel.vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            serviceChannel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mqttClient.disconnect() } catch (e: Exception) {}
        stopVibration()
    }
}