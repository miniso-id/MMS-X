package com.miniso.mms_x

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var downloadOverlay: View
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadPercent: TextView
    private lateinit var downloadTitle: TextView

    private val TARGET_URL = "https://mmsx.pages.dev/app/?app=mmsx"
    private var isUpdatePending = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 200
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView             = findViewById(R.id.webview)
        downloadOverlay     = findViewById(R.id.downloadOverlay)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadPercent     = findViewById(R.id.downloadPercent)
        downloadTitle       = findViewById(R.id.downloadTitle)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadUrl("file:///android_asset/offline.html")
            }
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (url.startsWith("intent://") || url.startsWith("lark://") || url.startsWith("feishu://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(packageManager) != null) { startActivity(intent); return true }
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        view?.loadUrl(fallback ?: "https://play.google.com/store/apps/details?id=com.ss.android.lark")
                        return true
                    } catch (e: Exception) { return false }
                }
                if (url.contains("applink.feishu.cn") || url.contains("applink.larksuite.com")) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return true
                }
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.grant(request.resources)
        }

        if (isOnline()) webView.loadUrl(TARGET_URL)
        else webView.loadUrl("file:///android_asset/offline.html")

        checkCameraPermission()
        checkUpdate()
    }

    // ===== KONEKSI =====
    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ===== KAMERA =====
    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    // ===== CEK UPDATE =====
    private fun checkUpdate() {
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 0 }

        val req = JsonObjectRequest(Request.Method.GET, "https://mmsx.pages.dev/app/version.json", null,
            { response ->
                val latest  = response.getInt("latestVersionCode")
                val name    = response.getString("latestVersionName")
                val dlUrl   = response.getString("updateUrl")
                if (latest > currentVersion) showUpdateDialog(name, dlUrl)
            },
            { android.util.Log.e("MMSX", "Gagal cek update: ${it.message}") }
        )
        Volley.newRequestQueue(this).add(req)
    }

    // ===== DIALOG UPDATE WAJIB =====
    private fun showUpdateDialog(versionName: String, downloadUrl: String) {
        isUpdatePending = true
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Update Tersedia")
                .setMessage("Versi $versionName sudah tersedia. Silakan update untuk melanjutkan.")
                .setCancelable(false)
                .setPositiveButton("Download & Install") { _, _ -> startDownload(downloadUrl, versionName) }
                .create().also {
                    it.setCanceledOnTouchOutside(false)
                    it.show()
                }
        }
    }

    // ===== DOWNLOAD PAKAI THREAD (bukan DownloadManager) =====
    private fun startDownload(downloadUrl: String, versionName: String) {
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MMS-X-$versionName.apk")
        if (apkFile.exists()) apkFile.delete()

        // Simpan nama file
        getSharedPreferences("mmsx_prefs", MODE_PRIVATE)
            .edit().putString("pending_apk", apkFile.absolutePath).apply()

        // Tampilkan overlay progress
        runOnUiThread {
            downloadTitle.text = "Mengunduh MMS-X v$versionName..."
            downloadPercent.text = "0%"
            downloadProgressBar.progress = 0
            downloadOverlay.visibility = View.VISIBLE
        }

        // Download di background thread
        Thread {
            try {
                var connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                // Handle redirect manual jika perlu
                var responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == 307 || responseCode == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = URL(newUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.connect()
                    responseCode = connection.responseCode
                }

                val fileSize = connection.contentLength
                val input = connection.inputStream
                val output = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var downloaded = 0L
                var bytes: Int

                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    if (fileSize > 0) {
                        val pct = (downloaded * 100 / fileSize).toInt()
                        handler.post {
                            downloadProgressBar.progress = pct
                            downloadPercent.text = "$pct%"
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()
                connection.disconnect()

                // Download selesai
                handler.post {
                    downloadProgressBar.progress = 100
                    downloadPercent.text = "100%"
                    handler.postDelayed({
                        downloadOverlay.visibility = View.GONE
                        showInstallDialog()
                    }, 500)
                }

            } catch (e: Exception) {
                handler.post {
                    downloadOverlay.visibility = View.GONE
                    AlertDialog.Builder(this)
                        .setTitle("Download Gagal")
                        .setMessage("Gagal mengunduh update: ${e.message}")
                        .setCancelable(false)
                        .setPositiveButton("Coba Lagi") { _, _ -> startDownload(downloadUrl, versionName) }
                        .setNegativeButton("Nanti", null)
                        .show()
                }
            }
        }.start()
    }

    // ===== DIALOG INSTALL =====
    private fun showInstallDialog() {
        AlertDialog.Builder(this)
            .setTitle("Download Selesai")
            .setMessage("MMS-X versi terbaru siap diinstall.")
            .setCancelable(false)
            .setPositiveButton("Install Sekarang") { _, _ -> installApk() }
            .create().also {
                it.setCanceledOnTouchOutside(false)
                it.show()
            }
    }

    // ===== INSTALL APK =====
    private fun installApk() {
        // Cek izin Install Unknown Apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Izin Diperlukan")
                .setMessage("Aktifkan izin \"Install Unknown Apps\" untuk MMS-X agar update dapat diinstall.")
                .setCancelable(false)
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                        REQUEST_INSTALL_PERMISSION
                    )
                }
                .create().also {
                    it.setCanceledOnTouchOutside(false)
                    it.show()
                }
            return
        }

        val apkPath = getSharedPreferences("mmsx_prefs", MODE_PRIVATE)
            .getString("pending_apk", null) ?: return
        val apkFile = File(apkPath)

        if (!apkFile.exists() || apkFile.length() == 0L) {
            AlertDialog.Builder(this)
                .setTitle("File Tidak Ditemukan")
                .setMessage("File APK tidak ditemukan. Coba download ulang.")
                .setPositiveButton("OK", null).show()
            return
        }

        try {
            val apkUri = FileProvider.getUriForFile(this, "${packageName}.provider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Install Gagal")
                .setMessage("Error: ${e.message}")
                .setPositiveButton("OK", null).show()
        }
    }

    // ===== KEMBALI DARI SETTINGS IZIN =====
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                installApk()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Izin Belum Diaktifkan")
                    .setMessage("Update tidak dapat diinstall. Aktifkan izin \"Install Unknown Apps\" untuk MMS-X.")
                    .setCancelable(false)
                    .setPositiveButton("Coba Lagi") { _, _ -> installApk() }
                    .create().also {
                        it.setCanceledOnTouchOutside(false)
                        it.show()
                    }
            }
        }
    }

    // ===== BACK BUTTON =====
    @Deprecated("Deprecated in Java")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (isUpdatePending) return
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
