package com.miniso.mms_x

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var downloadOverlay: View
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadPercent: TextView
    private lateinit var downloadTitle: TextView

    private val TARGET_URL = "https://mmsx.pages.dev/app/?app=mmsx"
    private var downloadId: Long = -1
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    // Receiver: dipanggil saat download selesai
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) {
                stopProgressPolling()
                hideDownloadOverlay()
                installApk(context)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView           = findViewById(R.id.webview)
        downloadOverlay   = findViewById(R.id.downloadOverlay)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadPercent   = findViewById(R.id.downloadPercent)
        downloadTitle     = findViewById(R.id.downloadTitle)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadUrl("file:///android_asset/offline.html")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false

                if (url.startsWith("intent://") || url.startsWith("lark://") || url.startsWith("feishu://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            return true
                        }
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        view?.loadUrl(fallbackUrl ?: "https://play.google.com/store/apps/details?id=com.ss.android.lark")
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }

                if (url.contains("applink.feishu.cn") || url.contains("applink.larksuite.com")) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    return true
                }

                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        if (isOnline()) {
            webView.loadUrl(TARGET_URL)
        } else {
            webView.loadUrl("file:///android_asset/offline.html")
        }

        checkCameraPermission()
        checkUpdate()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    private fun checkUpdate() {
        val url = "https://mmsx.pages.dev/app/version.json"

        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 0 }

        val request = JsonObjectRequest(Request.Method.GET, url, null,
            { response ->
                val latestVersion = response.getInt("latestVersionCode")
                val latestVersionName = response.getString("latestVersionName")
                val downloadUrl = response.getString("updateUrl")
                if (latestVersion > currentVersion) {
                    showUpdateDialog(latestVersionName, downloadUrl)
                }
            },
            { error ->
                android.util.Log.e("MMSX_DEBUG", "Gagal cek update: ${error.message}")
            }
        )
        Volley.newRequestQueue(this).add(request)
    }

    private fun showUpdateDialog(versionName: String, downloadUrl: String) {
        AlertDialog.Builder(this)
            .setTitle("Update MMS-X Tersedia")
            .setMessage("Versi $versionName sudah tersedia.\nDownload dan install sekarang?")
            .setCancelable(false)
            .setPositiveButton("Download & Install") { _, _ ->
                startDownload(downloadUrl, versionName)
            }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startDownload(downloadUrl: String, versionName: String) {
        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "MMS-X-$versionName.apk")
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
            setTitle("MMS-X Update")
            setDescription("Mengunduh MMS-X v$versionName...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                "MMS-X-$versionName.apk"
            )
            setMimeType("application/vnd.android.package-archive")
        }

        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(request)

        getSharedPreferences("mmsx_prefs", MODE_PRIVATE)
            .edit().putString("pending_apk", "MMS-X-$versionName.apk").apply()

        // Tampilkan overlay progress
        showDownloadOverlay(versionName)
        startProgressPolling()
    }

    // Tampilkan overlay di bawah layar
    private fun showDownloadOverlay(versionName: String) {
        downloadTitle.text = "Mengunduh MMS-X v$versionName..."
        downloadPercent.text = "0%"
        downloadProgressBar.progress = 0
        downloadOverlay.visibility = View.VISIBLE
    }

    private fun hideDownloadOverlay() {
        runOnUiThread { downloadOverlay.visibility = View.GONE }
    }

    // Polling progress setiap 500ms
    private fun startProgressPolling() {
        progressRunnable = object : Runnable {
            override fun run() {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()

                    if (total > 0) {
                        val percent = (downloaded * 100 / total).toInt()
                        runOnUiThread {
                            downloadProgressBar.progress = percent
                            downloadPercent.text = "$percent%"
                        }
                    }
                }

                handler.postDelayed(this, 500)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun installApk(context: Context) {
        val apkName = getSharedPreferences("mmsx_prefs", MODE_PRIVATE)
            .getString("pending_apk", null) ?: return

        val apkFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName)
        if (!apkFile.exists()) return

        val apkUri = FileProvider.getUriForFile(
            context,
            "${packageName}.provider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProgressPolling()
        unregisterReceiver(downloadReceiver)
    }

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
