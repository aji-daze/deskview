package dev.deskview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebView.WebViewTransport
import android.webkit.WebViewClient

/**
 * デスクトップ表示に特化した WebView。
 * UA・viewport 固定・Cookie 永続化など「本アプリの価値の9割」をここに集約する（設計書 5章）。
 * 各種ハンドラ（設計書 6章）は Callbacks 経由で Activity 側に委譲する。
 */
@SuppressLint("SetJavaScriptEnabled")
class DesktopWebView(context: Context, attrs: AttributeSet? = null) : WebView(context, attrs) {

    interface Callbacks {
        // target=_blank や OAuth ポップアップ用に新規タブを作り、その WebView を返す
        fun onOpenNewTab(url: String?): DesktopWebView
        fun onShowFileChooser(callback: ValueCallback<Array<Uri>>): Boolean
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
        fun onRequestMediaPermission(request: PermissionRequest)
        fun onTitleChanged(webView: DesktopWebView, title: String?)
        fun onPageFinished(webView: DesktopWebView, url: String?)
    }

    var callbacks: Callbacks? = null

    companion object {
        // Chrome のバージョン番号だけを定数化。古くなったらここを更新するだけでよい（設計書 5章補足）。
        private const val DESKTOP_CHROME_VERSION = "128.0.0.0"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$DESKTOP_CHROME_VERSION Safari/537.36"
        private const val DESKTOP_MIN_WIDTH_PX = 1280
    }

    init {
        configureDesktopSettings()
        setupWebViewClient()
        setupWebChromeClient()
        setupDownloadListener()
    }

    private fun configureDesktopSettings() {
        settings.apply {
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@DesktopWebView, true)
        }
    }

    private fun setupWebViewClient() {
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // http(s) は WebView 内で処理し、それ以外（mailto:, intent: など）は外部 Intent に委譲する
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                return try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    // 対応アプリが無い場合は何もしない
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                adjustScaleForDesktopWidth()
                callbacks?.onPageFinished(this@DesktopWebView, url)
            }
        }
    }

    // useWideViewPort だけでは横幅が980px相当になるため、実測してデスクトップ幅に近づける（設計書 5章補足）
    private fun adjustScaleForDesktopWidth() {
        evaluateJavascript("(function(){return window.innerWidth;})()") { result ->
            val width = result?.toDoubleOrNull()?.toInt() ?: return@evaluateJavascript
            if (width in 1 until DESKTOP_MIN_WIDTH_PX) {
                val scale = (width * 100 / DESKTOP_MIN_WIDTH_PX).coerceIn(50, 100)
                setInitialScale(scale)
            }
        }
    }

    private fun setupWebChromeClient() {
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                return callbacks?.onShowFileChooser(filePathCallback) ?: false
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val callback = callbacks ?: return false
                val newWebView = callback.onOpenNewTab(null)
                val transport = resultMsg.obj as WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                callbacks?.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                callbacks?.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                callbacks?.onRequestMediaPermission(request)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                callbacks?.onTitleChanged(this@DesktopWebView, title)
            }
        }
    }

    private fun setupDownloadListener() {
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    addRequestHeader("User-Agent", userAgent)
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
            } catch (_: Exception) {
                // ダウンロード開始に失敗した場合は何もしない（保存先が無い等）
            }
        }
    }
}
