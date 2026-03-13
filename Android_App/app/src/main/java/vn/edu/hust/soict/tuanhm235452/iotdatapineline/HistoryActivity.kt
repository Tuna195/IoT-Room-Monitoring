package vn.edu.hust.soict.tuanhm235452.iotdatapineline

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class HistoryActivity : AppCompatActivity() {


    private val ipLaptop = AppConfig.SERVER_IP // <--- CHECK LẠI IP
    private val dashboardUID = AppConfig.UID_HISTORY


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val btnBack = findViewById<Button>(R.id.btnBack)
        val webView = findViewById<WebView>(R.id.webViewHistory)

        // Cấu hình WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        // IP Laptop của bạn

        // Link Grafana dạng BẢNG (Table) mà bạn tạo ở Bước 1
        // Lưu ý: Thay đổi UID cho đúng cái Dashboard Table của bạn
        val tableUrl = "http://$ipLaptop:3000/d/$dashboardUID/table_nhiet_do?orgId=1&kiosk&refresh=5s"

        webView.loadUrl(tableUrl)

        // Nút quay lại
        btnBack.setOnClickListener {
            finish() // Đóng Activity này để quay về trang trước
        }
    }
}