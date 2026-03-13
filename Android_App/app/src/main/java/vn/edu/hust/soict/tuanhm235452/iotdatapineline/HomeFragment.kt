package vn.edu.hust.soict.tuanhm235452.iotdatapineline

import android.content.BroadcastReceiver
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import android.os.Vibrator // Thêm thư viện Rung
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log.e
import androidx.core.content.ContentProviderCompat.requireContext
import vn.edu.hust.soict.tuanhm235452.iotdatapineline.MqttService
class HomeFragment : Fragment() {

    // Khai báo biến y hệt MainActivity cũ
    private lateinit var mqttClient: MqttAndroidClient
    private lateinit var txtTemp: TextView
    private lateinit var txtHumid: TextView
    private lateinit var txtMQTTStatus: TextView
    private lateinit var txtESP32Status: TextView
    private lateinit var swFan: SwitchMaterial
    private lateinit var btnSystemRecord: ToggleButton

    private lateinit var btnLimitConfig: android.widget.Button
    private lateinit var btnLimitHumid: android.widget.Button
    private val TOPIC_LIMIT_TEMP = "phong_hoc/nguong_canh_bao"
    private val TOPIC_LIMIT_HUMID = "phong_hoc/nguong_canh_bao_do_am"

    // Cấu hình MQTT

    private val serverUri = AppConfig.MQTT_BROKER_URI // <--- CHECK LẠI IP CỦA BẠN
    private val TOPIC_TEMP = "phong_hoc/nhiet_do"
    private val TOPIC_HUMID = "phong_hoc/do_am"
    private val TOPIC_STATUS = "phong_hoc/trang_thai"
    private val TOPIC_FAN = "phong_hoc/coi" //
    private val TOPIC_RECORD = "he_thong/ghi_du_lieu"

    private var currentLimit: Float = 23.5f
    private var currentHumid: Float = 0f
    private var currentTemp: Float = 0f
    private var limitTemp: Float = 23.5f
    private var limitHumid: Float = 70.0f
    private lateinit var txtWarning: TextView // <--- THÊM BIẾN NÀY

    private lateinit var btnMute: android.widget.Button // Nút tắt tiếng mới
    private val TOPIC_MUTE = "phong_hoc/tat_tieng"
    private val TOPIC_CURRENT_LIMIT = "phong_hoc/nguong_hien_tai"
    private val TOPIC_CURRENT_LIMIT_HUMID = "phong_hoc/nguong_do_am_hien_tai"
    private val TOPIC_STATUS_COI = "phong_hoc/trang_thai_coi" // Topic đồng bộ switch
    private var isMutedState = false // Mặc định là chưa tắt tiếng
    // Biến cho bộ rung
    private var vibrator: Vibrator? = null
    private var isVibrating = false
    private val mqttStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MqttService.ACTION_MQTT_STATUS) {
                val status = intent.getStringExtra(MqttService.EXTRA_STATUS)
                updateServerStatusUI(status)
            }
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Nạp giao diện fragment_home
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Khởi tạo Vibrator
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // 1. Ánh xạ View (Dùng view.findViewById)
        txtTemp = view.findViewById(R.id.txtTemp)
        txtHumid = view.findViewById(R.id.txtHumid)
        txtMQTTStatus = view.findViewById(R.id.txtMQTTStatus)
        txtESP32Status = view.findViewById(R.id.txtESP32Status)
        swFan = view.findViewById(R.id.swFan)
        btnSystemRecord = view.findViewById(R.id.btnSystemRecord)

        btnLimitConfig = view.findViewById(R.id.btnLimitConfig)
        btnLimitHumid = view.findViewById(R.id.btnLimitHumid)
        txtWarning = view.findViewById(R.id.txtWarning)
        btnMute = view.findViewById(R.id.btnMute)
        btnMute.visibility = View.GONE // Mặc định ẩn, chỉ hiện khi có báo động
        // 2. Kết nối MQTT
        val clientId = MqttClient.generateClientId()
        mqttClient = MqttAndroidClient(requireContext(), serverUri, clientId)
        connectMQTT()

        // 3. Sự kiện nút bấm
        swFan.setOnCheckedChangeListener { _, isChecked ->
            publishMessage(TOPIC_FAN, if (isChecked) "ON" else "OFF")
        }
        btnSystemRecord.setOnCheckedChangeListener { _, isChecked ->
            publishRetainedMessage(TOPIC_RECORD, if (isChecked) "TRUE" else "FALSE")
        }
        // Nút cài đặt Nhiệt độ (Gọi hàm Dialog chung)
        btnLimitConfig.setOnClickListener {
            showSetLimitDialog("Cài đặt Nhiệt độ", TOPIC_LIMIT_TEMP, limitTemp, "°C")
        }

        // Nút cài đặt Độ ẩm (Gọi hàm Dialog chung)
        btnLimitHumid.setOnClickListener {
            showSetLimitDialog("Cài đặt Độ ẩm", TOPIC_LIMIT_HUMID, limitHumid, "%")
        }
        // Sự kiện nút Tắt tiếng
        btnMute.setOnClickListener {
            // 1. Gửi lệnh Mute xuống ESP32
            // 2. Tắt rung điện thoại
            stopVibration()
            if (isMutedState) {
                // TRƯỜNG HỢP 1: Đang tắt tiếng -> Muốn BẬT LẠI
                publishMessage(TOPIC_MUTE, "UNMUTE") // Gửi lệnh mở tiếng

                // Cập nhật giao diện ngay lập tức
                btnMute.text = "BẤM ĐỂ TẮT TIẾNG CÒI"
                btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")) // Về màu Đỏ
                isMutedState = false
            }
            else {
                // TRƯỜNG HỢP 2: Đang kêu -> Muốn TẮT TIẾNG
                publishMessage(TOPIC_MUTE, "MUTE") // Gửi lệnh tắt tiếng
                stopVibration() // Tắt rung điện thoại

                // Cập nhật giao diện
                btnMute.text = "BẤM ĐỂ BẬT LẠI CÒI" // Đổi chữ
                btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")) // Đổi sang màu Xanh cho dịu mắt
                isMutedState = true
            }

        }
    }
    override fun onResume() {
        super.onResume()
        // 2. ĐĂNG KÝ NGHE KHI MÀN HÌNH HIỆN LÊN
        val filter = IntentFilter(MqttService.ACTION_MQTT_STATUS)
        // Lưu ý: Nếu dùng Android 13+ (API 33) cần thêm cờ RECEIVER_NOT_EXPORTED, nhưng đồ án thường dùng API thấp hơn nên thế này là ổn.
        requireActivity().registerReceiver(mqttStatusReceiver, filter)
    }
    override fun onPause() {
        super.onPause()
        // 3. HỦY ĐĂNG KÝ KHI MÀN HÌNH TẮT (Tránh rò rỉ bộ nhớ)
        try {
            requireActivity().unregisterReceiver(mqttStatusReceiver)
        } catch (e: Exception) { e.printStackTrace() }
    }
    private fun updateServerStatusUI(status: String?) {
        // Giả sử TextView của bạn có id là txtMQTTStatus
        if (status == "CONNECTED") {
            txtMQTTStatus.text = "● Server: Đã kết nối"
            txtMQTTStatus.setTextColor(Color.parseColor("#4CAF50")) // Màu Xanh
        } else {
            txtMQTTStatus.text = "● Server: Mất kết nối!"
            txtMQTTStatus.setTextColor(Color.RED) // Màu Đỏ
        }
    }

    private fun connectMQTT() {
        try {
            val token = mqttClient.connect()
            token.actionCallback = object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    txtMQTTStatus.text = "● Server: Đã kết nối"
                    txtMQTTStatus.setTextColor(Color.parseColor("#4CAF50")) // Xanh lá
                    subscribeTopics()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    txtMQTTStatus.text = "● Server: Lỗi kết nối!"
                    txtMQTTStatus.setTextColor(Color.RED)
                }
            }
            // Callback nhận tin nhắn
            mqttClient.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    txtMQTTStatus.text = "● Server: Mất kết nối!"
                    txtMQTTStatus.setTextColor(Color.RED)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    activity?.runOnUiThread {
                        val payload = message.toString()
                        when (topic) {
//                            TOPIC_TEMP -> {
//                                txtTemp.text = "$payload °C"
//                                val tempVal = payload.toFloatOrNull() ?: 0f // Chuyển chuỗi thành số
//                                if (tempVal > currentLimit) {
//                                    // --- TRƯỜNG HỢP NGUY HIỂM ---
//                                    txtWarning.visibility = View.VISIBLE // Hiện dòng cảnh báo
//                                    txtTemp.setTextColor(Color.RED)      // Số cũng đỏ theo cho sợ
//                                    // Hiện nút tắt tiếng
//                                    if (!btnMute.isEnabled) { // Reset nút nếu nó đang bị disable
//                                        btnMute.isEnabled = true
//                                        btnMute.text = "BẤM ĐỂ TẮT TIẾNG CÒI"
//                                    }
//                                    btnMute.visibility = View.VISIBLE
//
//                                    // KÍCH HOẠT RUNG (Nếu chưa rung)
//                                    if (!isVibrating) startVibration()
//
//                                } else {
//                                    // --- TRƯỜNG HỢP AN TOÀN ---
//                                    txtTemp.setTextColor(Color.parseColor("#FFFFFF")) // Màu cam gốc (hoặc màu bạn thích)
//                                    // Trả về nguyên trạng (chỉ hiện số)
//                                    txtTemp.text = "$payload °C"
//                                    txtWarning.visibility = View.GONE    // Ẩn dòng cảnh báo đi
//                                    btnMute.visibility = View.GONE // Ẩn nút tắt tiếng
//                                    // Tắt rung tự động
//                                    stopVibration()
//
//                                }
//                                handleDeviceStatus("ONLINE")
//                            }
                            TOPIC_TEMP -> {
                                txtTemp.text = "$payload °C"
                                currentTemp = payload.toFloatOrNull() ?: 0f
                                checkSystemSafety() // Kiểm tra an toàn chung
                            }

                            // --- XỬ LÝ ĐỘ ẨM (MỚI) ---
                            TOPIC_HUMID -> {
                                txtHumid.text = "$payload %"
                                currentHumid = payload.toFloatOrNull() ?: 0f
                                checkSystemSafety() // Kiểm tra an toàn chung
                            }
                            TOPIC_CURRENT_LIMIT -> {
                                limitTemp = payload.toFloat()
                                btnLimitConfig.text = "NGƯỠNG CẢNH BÁO: $payload °C"
                                checkSystemSafety()
                            }
                            TOPIC_CURRENT_LIMIT_HUMID -> {
                                limitHumid = payload.toFloat()
                                // Cập nhật text trên nút bấm độ ẩm
                                btnLimitHumid.text = "NGƯỠNG ĐỘ ẨM: $payload %"
                                checkSystemSafety() // Kiểm tra lại ngay
                            }
                            // --- ĐỒNG BỘ: Cập nhật trạng thái Switch ---
                            TOPIC_STATUS_COI -> {
                                // Tránh vòng lặp vô tận khi setChecked kích hoạt lại event listener
                                swFan.setOnCheckedChangeListener(null)
                                swFan.isChecked = (payload == "ON")
                                // Gán lại listener
                                swFan.setOnCheckedChangeListener { _, isChecked ->
                                    publishMessage(TOPIC_FAN, if (isChecked) "ON" else "OFF")
                                }
                            }
                            TOPIC_RECORD -> {
                                // 1. Gỡ sự kiện cũ để tránh lặp vô tận
                                btnSystemRecord.setOnCheckedChangeListener(null)

                                // 2. Cập nhật trạng thái nút (Nếu tin nhắn là "TRUE" thì Bật, ngược lại Tắt)
                                btnSystemRecord.isChecked = (payload == "TRUE")

                                // 3. Gán lại sự kiện lắng nghe
                                btnSystemRecord.setOnCheckedChangeListener { _, isChecked ->
                                    // Gửi tin nhắn có RETAIN (Lưu giữ) để lần sau mở app vẫn nhớ
                                    publishRetainedMessage(TOPIC_RECORD, if (isChecked) "TRUE" else "FALSE")
                                }
                            }
                            TOPIC_MUTE -> {
                                if (payload == "MUTE") {
                                    // Nếu nhận được lệnh Mute (từ ESP32 nút cứng hoặc App khác)
                                    // Cập nhật giao diện App ngay lập tức
                                    isMutedState = true
                                    btnMute.text = "ĐÃ TẮT TIẾNG (NÚT CỨNG)"
                                    btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                                    btnMute.visibility = View.VISIBLE
                                    stopVibration()
                                }
                                else if (payload == "UNMUTE") {
                                    isMutedState = false // <--- CẬP NHẬT TRẠNG THÁI

                                    btnMute.text = "BẤM ĐỂ TẮT TIẾNG CÒI"
                                    btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
                                    btnMute.visibility = View.VISIBLE
                                        // Có thể cho rung lại nếu muốn (tùy chọn)
                                    }
                            }
                            TOPIC_STATUS -> handleDeviceStatus(payload)

                        }
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

        } catch (e: MqttException) { e.printStackTrace() }
    }

    // --- HÀM KIỂM TRA AN TOÀN CHUNG (GỘP CẢ 2) ---
    private fun checkSystemSafety() {
        val isHot = currentTemp > limitTemp
        val isWet = currentHumid > limitHumid

        // Chỉ cần 1 trong 2 vượt ngưỡng là BÁO ĐỘNG
        if (isHot || isWet) {
            txtWarning.visibility = View.VISIBLE
            btnMute.visibility = View.VISIBLE

            // Hiện text cảnh báo cụ thể
            if (isHot && isWet) txtWarning.text = "⚠️ NGUY HIỂM: CẢ NHIỆT VÀ ẨM CAO!"
            else if (isHot) {
                txtWarning.text = "⚠️ CẢNH BÁO: QUÁ NHIỆT!"
                txtTemp.setTextColor(Color.RED)
            }
            else if (isWet) {
                txtWarning.text = "⚠️ CẢNH BÁO: ĐỘ ẨM CAO!"
                txtHumid.setTextColor(Color.RED) // Đổi màu số độ ẩm thành đỏ
            }

            /// --- ĐOẠN SỬA LOGIC NÚT BẤM ---
            btnMute.visibility = View.VISIBLE // Luôn hiện khi có báo động
            btnMute.isEnabled = true          // LUÔN CHO PHÉP BẤM (Không bao giờ disable)

            if (isMutedState) {
                // Nếu đang Mute
                btnMute.text = "BẤM ĐỂ BẬT LẠI CÒI"
                btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50")) // Xanh
                stopVibration()
            } else {
                // Nếu đang kêu
                btnMute.text = "BẤM ĐỂ TẮT TIẾNG CÒI"
                btnMute.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336")) // Đỏ
                if (!isVibrating) startVibration()
            }

        } else {
            // --- AN TOÀN ---
            isMutedState = false
            txtWarning.visibility = View.GONE
            btnMute.visibility = View.GONE

            // Trả màu về trắng
            txtTemp.setTextColor(Color.WHITE)
            txtHumid.setTextColor(Color.WHITE)

            stopVibration()
        }
    }

    // --- HÀM DIALOG DÙNG CHUNG (CẢI TIẾN) ---
    // Giờ đây hàm này nhận vào Topic và Unit để dùng được cho cả 2 nút
    private fun showSetLimitDialog(title: String, topic: String, currentVal: Float, unit: String) {
        val context = requireContext()
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle(title)

        val input = android.widget.EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Hiện tại: $currentVal"
        input.gravity = android.view.Gravity.CENTER
        builder.setView(input)

        builder.setPositiveButton("LƯU") { _, _ ->
            val limitStr = input.text.toString()
            if (limitStr.isNotEmpty()) {
                // Gửi MQTT tới topic tương ứng (Retained để ESP32 nhớ)
                publishRetainedMessage(topic, limitStr)
                android.widget.Toast.makeText(context, "Đã lưu $limitStr $unit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Hủy") { dialog, _ -> dialog.cancel() }
        builder.show()
    }
    // Hàm gửi tin nhắn có lưu giữ (Retained)
    private fun publishRetainedMessage(topic: String, msg: String) {
        try {
            val message = MqttMessage(msg.toByteArray())
            message.isRetained = true // <--- QUAN TRỌNG: Đánh dấu tin nhắn này cần được lưu lại
            mqttClient.publish(topic, message)
        } catch (e: MqttException) { e.printStackTrace() }
    }
    private fun subscribeTopics() {
        try {
            mqttClient.subscribe(TOPIC_TEMP, 0)
            mqttClient.subscribe(TOPIC_HUMID, 0)
            mqttClient.subscribe(TOPIC_STATUS, 0)
            // Đăng ký nghe 2 topic Retained này để đồng bộ App
            mqttClient.subscribe(TOPIC_CURRENT_LIMIT, 0)
            mqttClient.subscribe(TOPIC_LIMIT_HUMID, 0)
            mqttClient.subscribe(TOPIC_CURRENT_LIMIT_HUMID, 0)
            mqttClient.subscribe(TOPIC_STATUS_COI, 0)
            mqttClient.subscribe(TOPIC_MUTE, 0)
            mqttClient.subscribe(TOPIC_RECORD, 0)
        } catch (e: MqttException) { e.printStackTrace() }
    }

    private fun handleDeviceStatus(status: String) {
        if (status == "ONLINE") {
            txtESP32Status.text = "📶 Thiết bị: Online"
            txtESP32Status.setTextColor(Color.parseColor("#4CAF50"))
            swFan.isEnabled = true
        } else {
            txtESP32Status.text = "📶 Thiết bị: Offline"
            txtESP32Status.setTextColor(Color.GRAY)
            swFan.isEnabled = false
        }
    }

    private fun publishMessage(topic: String, msg: String) {
        try {
            val message = MqttMessage(msg.toByteArray())
            mqttClient.publish(topic, message)
        } catch (e: MqttException) { e.printStackTrace() }
    }

    // 3. Hàm hiển thị hộp thoại nhập số (Copy nguyên hàm này vào cuối class HomeFragment)
    private fun showSetLimitDialog() {
        val context = requireContext()
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("Cài đặt ngưỡng nhiệt độ")

        // Tạo ô nhập trong Dialog
        val input = android.widget.EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Nhập nhiệt độ (VD: 30.5)"
        input.gravity = android.view.Gravity.CENTER
        builder.setView(input)

        // Nút Đồng ý
        builder.setPositiveButton("LƯU") { _, _ ->
            val limitStr = input.text.toString()
            if (limitStr.isNotEmpty()) {
                // Gửi MQTT
                publishMessage(TOPIC_LIMIT_TEMP, limitStr)
                currentLimit = limitStr.toFloat()
                // Cập nhật chữ trên nút bấm cho người dùng thấy ngay
                btnLimitConfig.text = "NGƯỠNG NHIỆT ĐỘ: $limitStr °C"

                android.widget.Toast.makeText(context, "Đã lưu ngưỡng mới!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Nút Hủy
        builder.setNegativeButton("Hủy") { dialog, _ ->
            dialog.cancel()
        }

        builder.show()
    }
    // --- CÁC HÀM XỬ LÝ RUNG ---
    private fun startVibration() {
        isVibrating = true
        // Rung theo nhịp: Nghỉ 0ms, Rung 500ms, Nghỉ 500ms... (Lặp lại số 0)
        val pattern = longArrayOf(0, 500, 500)
        vibrator?.vibrate(pattern, 0) // Tham số 0 nghĩa là lặp lại vô hạn
    }

    private fun stopVibration() {
        if (isVibrating) {
            isVibrating = false
            vibrator?.cancel()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        try {
            // Ngắt kết nối MQTT khi thoát màn hình để tránh "Ma" cập nhật số liệu
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Tắt rung nếu đang rung dở
        stopVibration()
    }
}