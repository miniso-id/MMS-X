package com.miniso.mms_x

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Inisialisasi WebView
        webView = findViewById(R.id.webview)

        // 2. Pengaturan WebView agar fitur Scanner & JS jalan
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false

                // 1. Tangani jika URL adalah link Intent langsung
                if (url.startsWith("intent://") || url.startsWith("lark://") || url.startsWith("feishu://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            return true
                        }
                        // Jika aplikasi tidak ada, buka Play Store (fallback)
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        view?.loadUrl(fallbackUrl ?: "https://play.google.com/store/apps/details?id=com.ss.android.lark")
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }

                // 2. Tangani khusus link applink.feishu.cn agar tidak error "undefined"
                if (url.contains("applink.feishu.cn") || url.contains("applink.larksuite.com")) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    return true // Paksa buka di browser eksternal atau aplikasi Feishu langsung
                }

                return false
            }
        }

        // 3. Handle izin Kamera di dalam WebView (PENTING untuk Scan Barcode)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // 4. Load URL aplikasi Miniso kamu
        webView.loadUrl("https://mmsx.pages.dev/app/?app=mmsx")

        // 5. Cek Izin Kamera & Jalankan Cek Update
        checkCameraPermission()
        checkUpdate()
    }

    // Fungsi untuk meminta izin kamera ke sistem Android
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    // Fungsi Cek Update otomatis dari file JSON di GitHub
    private fun checkUpdate() {
        val url = "https://mmsx.pages.dev/app/version.json"

        // Ambil versi APK saat ini
        val currentVersion = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 0 }

        val request = JsonObjectRequest(Request.Method.GET, url, null,
            { response ->
                val latestVersion = response.getInt("latestVersionCode")
                val downloadUrl = response.getString("updateUrl")

                // Jika versi di GitHub lebih tinggi, tampilkan dialog
                if (latestVersion > currentVersion) {
                    showUpdateDialog(downloadUrl)
                }
            },
            { error ->
                android.util.Log.e("MMSX_DEBUG", "Gagal cek update: ${error.message}")
            }
        )
        Volley.newRequestQueue(this).add(request)
    }

    // Tampilan Pop-up Notifikasi Update
    private fun showUpdateDialog(downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update MMS-X Tersedia")
            .setMessage("Versi terbaru sudah tersedia. Download sekarang ?")
            .setCancelable(false) // User harus memilih
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                startActivity(intent)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    // Navigasi tombol Back HP (agar tidak langsung keluar app)
    @Deprecated("Deprecated in Java")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}