package com.linksync.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var lastClip = ""
    private var pendingPeer = ""
    private var clipPolling = false

    private val clipPoll = object : Runnable {
        override fun run() {
            val text = readClipboard()
            if (!text.isNullOrEmpty() && text != lastClip) {
                lastClip = text
                if (Store.autoClipboard && !Store.isRecentlyApplied(text) && text != Runtime.lastAppliedText) {
                    Runtime.service?.broadcastClipboard(text)
                }
            }
            if (clipPolling) handler.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        window.statusBarColor = Color.parseColor("#0e1014")
        window.navigationBarColor = Color.parseColor("#0e1014")

        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        } catch (_: Exception) { }

        startSyncService()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0e1014"))
        setContentView(web)
        setupWebView()
        waitForServer(0)

        if (intent?.getBooleanExtra("sendClipboard", false) == true) {
            handler.postDelayed({ sendCurrentClipboard() }, 1200)
        }
    }

    private fun startSyncService() {
        try {
            val i = Intent(this, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            Log.e(Runtime.TAG, "start service: ${e.message}")
            toast("后台服务启动失败：${e.message}")
        }
    }

    private fun setupWebView() {
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.userAgentString = settings.userAgentString + " LinkSync/1.0"
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webChromeClient = object : WebChromeClient() {}
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }
    }

    private fun waitForServer(attempt: Int) {
        if (Runtime.port > 0 && Runtime.server != null) {
            web.loadUrl("http://127.0.0.1:${Runtime.port}/")
            return
        }
        if (attempt > 80) {
            web.loadDataWithBaseURL(
                null,
                "<html><body style='background:#0e1014;color:#e9ecf3;font-family:sans-serif;padding:32px'>" +
                    "<h3>LinkSync 服务启动失败</h3><p>请检查通知权限，或重新打开应用。</p></body></html>",
                "text/html", "utf-8", null
            )
            return
        }
        handler.postDelayed({ waitForServer(attempt + 1) }, 100)
    }

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(ClipboardManager::class.java)
            if (!cm.hasPrimaryClip()) return null
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(this)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun setClipboard(text: String) {
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("LinkSync", text))
        } catch (e: Exception) {
            Log.w(Runtime.TAG, "set clipboard: ${e.message}")
        }
    }

    private fun sendCurrentClipboard() {
        val text = readClipboard()
        if (text.isNullOrEmpty()) {
            toast("剪贴板里没有文字")
            return
        }
        lastClip = text
        Runtime.service?.broadcastClipboard(text)
        toast("已发送剪贴板")
    }

    inner class Bridge {
        @JavascriptInterface
        fun pickFiles(peerId: String) {
            runOnUiThread {
                pendingPeer = peerId
                try {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(i, REQ_PICK)
                } catch (e: Exception) {
                    toast("无法打开文件选择器")
                }
            }
        }

        @JavascriptInterface
        fun sendClipboard() {
            runOnUiThread { sendCurrentClipboard() }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                if (text.isEmpty()) return@runOnUiThread
                setClipboard(text)
                lastClip = text
                Runtime.lastAppliedText = text
                Runtime.lastAppliedAt = Store.now()
                toast("已复制")
            }
        }

        @JavascriptInterface
        fun scanQr() {
            runOnUiThread {
                try {
                    IntentIntegrator(this@MainActivity)
                        .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                        .setPrompt("将 LinkSync 二维码放入框内")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                        .setBarcodeImageEnabled(false)
                        .initiateScan()
                } catch (e: Exception) {
                    toast("无法启动扫码：${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun requestBattery() {
            runOnUiThread { requestBatteryOptimization() }
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread { toast(message) }
        }
    }

    private fun handleQr(raw: String) {
        val text = raw.trim()
        var host = ""
        var port = 47821
        try {
            val uri = Uri.parse(text)
            if (uri.scheme == "linksync" && uri.host == "add") {
                host = uri.getQueryParameter("ip")?.trim().orEmpty()
                port = uri.getQueryParameter("port")?.toIntOrNull() ?: 47821
            } else {
                val m = Regex("^(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d+)$").find(text)
                if (m != null) {
                    host = m.groupValues[1]
                    port = m.groupValues[2].toIntOrNull() ?: 47821
                }
            }
        } catch (_: Exception) {
        }
        if (host.isEmpty() || port !in 1..65535) {
            toast("不是有效的 LinkSync 二维码")
            return
        }
        toast("正在连接 $host:$port…")
        Thread {
            val info = Net.fetchInfo(host, port)
            runOnUiThread {
                if (info == null) {
                    toast("连接失败，请确认两台设备在同一 Wi-Fi")
                } else {
                    Store.touchPeer(
                        info.id,
                        info.name,
                        info.platform,
                        host,
                        if (info.port in 1..65535) info.port else port
                    )
                    toast("已添加 ${info.name}")
                }
            }
        }.start()
    }

    private fun requestBatteryOptimization() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已在电池优化白名单中")
                return
            }
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("请到系统设置中允许后台运行")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan != null) {
            val contents = scan.contents
            if (contents.isNullOrBlank()) toast("已取消扫码") else handleQr(contents)
            return
        }

        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty() || pendingPeer.isEmpty()) return
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
        Runtime.service?.sendFiles(pendingPeer, uris)
        toast("开始发送 ${uris.size} 个文件")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.getBooleanExtra("sendClipboard", false)) {
            handler.postDelayed({ sendCurrentClipboard() }, 600)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!clipPolling) {
            clipPolling = true
            val current = readClipboard()
            if (!current.isNullOrEmpty()) lastClip = current
            handler.postDelayed(clipPoll, 900)
        }
    }

    override fun onPause() {
        super.onPause()
        clipPolling = false
        handler.removeCallbacks(clipPoll)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        clipPolling = false
        handler.removeCallbacksAndMessages(null)
        try { web.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}
