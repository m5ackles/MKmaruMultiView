package com.myeolkong.multiview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val APP_ORIGIN = "https://$APP_HOST"
        private const val REQ_FILE = 1001
        private const val MAX_BODY = 8 * 1024 * 1024 // 응답 최대 8MB
    }

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private val pool = Executors.newFixedThreadPool(6)

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0E11"))
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        // Android 15(targetSdk 35)는 화면이 상태바 밑까지 깔리므로 여백 처리
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }

        // 앱 내부 파일을 https://appassets.androidplatform.net/assets/ 주소로 제공
        // (file:// 로 열면 유튜브 임베드가 '오류 153' 으로 막히는 문제 회피)
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(APP_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        WebView.setWebContentsDebuggingEnabled(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (request.url.host == APP_HOST) return false
                // ▶ 버튼 등 외부 링크 → 유튜브 앱 / 브라우저로 열기
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            // 유튜브 플레이어 전체화면
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(MATCH, MATCH))
                setSystemBarsHidden(true)
            }

            override fun onHideCustomView() { exitFullscreen() }

            // <input type="file"> (대체 이미지 선택, 설정 가져오기)
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    startActivityForResult(params.createIntent(), REQ_FILE); true
                } catch (e: Exception) {
                    fileCallback = null; false
                }
            }
        }

        // JS ↔ 네이티브 통신 (앱 자체 페이지에서만 허용, 유튜브 iframe 에서는 접근 불가)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "NativeBridge", setOf(APP_ORIGIN)) { _, message, _, isMainFrame, reply ->
                if (isMainFrame) message.data?.let { handleMessage(it, reply) }
            }
        } else {
            Toast.makeText(this, "WebView 버전이 낮아요. Play 스토어에서 Android System WebView 를 업데이트하세요.", Toast.LENGTH_LONG).show()
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("$APP_ORIGIN/assets/index.html")
    }

    // ---------------- bridge ----------------
    private fun handleMessage(raw: String, reply: JavaScriptReplyProxy) {
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        val id = msg.optString("id")
        when (msg.optString("type")) {
            "http" -> pool.execute {
                val out = JSONObject().put("id", id)
                try {
                    val r = httpRequest(
                        msg.getString("url"),
                        msg.optString("method", "GET"),
                        msg.optJSONObject("headers"),
                        if (msg.has("body") && !msg.isNull("body")) msg.getString("body") else null
                    )
                    out.put("status", r.first).put("url", r.second).put("text", r.third)
                } catch (e: Exception) {
                    out.put("status", -1).put("url", "").put("text", e.message ?: e.toString())
                }
                runOnUiThread { reply.postMessage(out.toString()) }
            }
            "save" -> {
                val out = JSONObject().put("id", id)
                try {
                    val where = saveToDownloads(msg.getString("name"), msg.getString("text"), msg.optString("mime", "text/plain"))
                    out.put("ok", true).put("path", where)
                    Toast.makeText(this, "$where 에 저장됨", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    out.put("ok", false).put("error", e.message ?: e.toString())
                }
                reply.postMessage(out.toString())
            }
        }
    }

    /** CORS 제약 없이 네이티브에서 직접 요청 → (상태코드, 최종URL, 본문) */
    private fun httpRequest(url: String, method: String, headers: JSONObject?, body: String?): Triple<Int, String, String> {
        val u = URL(url)
        if (u.protocol != "https" || !(u.host == "youtube.com" || u.host.endsWith(".youtube.com"))) {
            throw IOException("허용되지 않은 주소: ${u.host}")
        }
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.requestMethod = method
            headers?.keys()?.forEach { k -> c.setRequestProperty(k, headers.getString(k)) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.use { s ->
                val bytes = s.readBytes()
                String(if (bytes.size > MAX_BODY) bytes.copyOf(MAX_BODY) else bytes, Charsets.UTF_8)
            } ?: ""
            return Triple(status, c.url.toString(), text)
        } finally {
            c.disconnect()
        }
    }

    /** 다운로드/Multiview 폴더에 텍스트 파일 저장 (CSV 로그, 설정 백업) */
    private fun saveToDownloads(name: String, text: String, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Multiview")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("파일을 만들 수 없음")
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IOException("파일을 쓸 수 없음")
        return "다운로드/Multiview/$name"
    }

    // ---------------- fullscreen / lifecycle ----------------
    private fun exitFullscreen() {
        val v = customView ?: return
        (window.decorView as ViewGroup).removeView(v)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setSystemBarsHidden(false)
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                if (hidden) {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (hidden) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                else 0
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        pool.shutdownNow()
        web.destroy()
        super.onDestroy()
    }
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
