
package vn.edu.hust.soict.tuanhm235452.iotdatapineline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial

class ChartFragment : Fragment() {
    private lateinit var webView: WebView
    private lateinit var swChartType: SwitchMaterial
    private lateinit var textChartType: TextView
    private lateinit var btnExport: Button
    private lateinit var btnHistory: Button

    // Cấu hình Server
    // Cấu hình Server (IP Laptop của bạn)

    private val ipLaptop = AppConfig.SERVER_IP // <--- CHECK LẠI IP
    private val dashboardUID1 = AppConfig.UID_CHART_TEMP
    private val dashboardUID2 = AppConfig.UID_CHART_HUMID

    // Panel ID bạn lấy từ link share của từng biểu đồ
    private val URL_TEMP =
        "http://$ipLaptop:3000/d/$dashboardUID1/test-nhiet-do?orgId=1&kiosk&refresh=5s"

    // Link độ ẩm bạn vừa gửi (panel-1)
    private val URL_HUMID =
        "http://$ipLaptop:3000/d/$dashboardUID2/test-do-am?orgId=1&kiosk&refresh=5s"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_chart, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Ánh xạ View (Đưa hết ra ngoài, không được để null)
        webView = view.findViewById(R.id.webViewChart)
        swChartType = view.findViewById(R.id.swChartType)
        // LƯU Ý: Phải chắc chắn trong XML bạn đã đặt id là @+id/textChartType
        textChartType = view.findViewById(R.id.textChartType)
        btnExport = view.findViewById(R.id.btnExportExcel)
        btnHistory = view.findViewById(R.id.btnViewHistory) // <--- Tìm view ở đây mới đúng

        // 2. Cấu hình WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.webViewClient = WebViewClient()

        // 3. Mặc định load nhiệt độ
        loadChart("TEMP")

        // 4. Sự kiện GẠT CÔNG TẮC
        swChartType.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                loadChart("HUMID")
            } else {
                loadChart("TEMP")
            }
        }

        // 5. Nút Xuất Excel (Đưa ra ngoài Switch)
        btnExport.setOnClickListener {
            val downloadUrl = "http://$ipLaptop:1880/xuat-bao-cao"
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(downloadUrl))
                    .setTitle("Báo cáo Nhiệt độ Độ ẩm")
                    .setDescription("Đang tải CSV...")
                    .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "baocao_iot.csv")
                    .setAllowedOverMetered(true)

                val dm = requireContext().getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                Toast.makeText(context, "Đang tải file...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi tải file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 6. Nút Lịch sử (Đưa ra ngoài Switch)
        btnHistory.setOnClickListener {
            val intent = Intent(requireContext(), HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadChart(type: String) {
        if (type == "TEMP") {
            webView.loadUrl(URL_TEMP)
            textChartType.text = "BIỂU ĐỒ NHIỆT ĐỘ PHÒNG"
        } else {
            webView.loadUrl(URL_HUMID)
            textChartType.text = "BIỂU ĐỒ ĐỘ ẨM PHÒNG"
        }
    }
}