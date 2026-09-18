package dev.deskview

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 単一 Activity。ツールバー・タブ列・キー操作をまとめる（設計書 4章・6章）。
 * WebView 個別の挙動は DesktopWebView、タブ管理は TabManager に委譲する。
 */
class MainActivity : AppCompatActivity(), DesktopWebView.Callbacks {

    private lateinit var prefs: Prefs
    private lateinit var tabManager: TabManager
    private lateinit var etUrl: EditText
    private lateinit var mainLayout: View
    private lateinit var toolbar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var fullscreenContainer: FrameLayout

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isChromeHidden = false

    // ファイル選択（メディアライブラリ／Canva素材アップロード。設計書 6章）
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            callback?.onReceiveValue(uris.toTypedArray())
        }

    // カメラ・マイク権限（Canva の録画機能用。設計書 6章）
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val request = pendingPermissionRequest
            pendingPermissionRequest = null
            if (request == null) return@registerForActivityResult
            if (result.values.all { it }) request.grant(request.resources) else request.deny()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        mainLayout = findViewById(R.id.mainLayout)
        toolbar = findViewById(R.id.toolbar)
        tabBar = findViewById(R.id.tabBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        etUrl = findViewById(R.id.etUrl)
        val webViewContainer: FrameLayout = findViewById(R.id.webViewContainer)
        val tabStrip: LinearLayout = findViewById(R.id.tabStrip)

        tabManager = TabManager(
            container = webViewContainer,
            tabStrip = tabStrip,
            webViewFactory = { createWebView() },
            onActiveTabChanged = { webView -> etUrl.setText(webView.url ?: "") }
        )

        setupToolbar()
        setupBackPressed()
        checkWebViewVersion()
        restoreOrOpenInitialTabs()
    }

    private fun createWebView(): DesktopWebView = DesktopWebView(this).apply {
        callbacks = this@MainActivity
        setInitialScale(prefs.zoomPercent)
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            tabManager.activeWebView?.let { if (it.canGoBack()) it.goBack() }
        }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            tabManager.activeWebView?.let { if (it.canGoForward()) it.goForward() }
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            tabManager.activeWebView?.reload()
        }
        findViewById<ImageButton>(R.id.btnNewTab).setOnClickListener { openDefaultNewTab() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showOverflowMenu(it) }
        etUrl.setOnEditorActionListener { _, _, _ -> loadUrlFromInput(); true }
    }

    private fun openDefaultNewTab() {
        tabManager.openTab(prefs.wpAdminUrl.ifBlank { Prefs.CANVA_URL })
    }

    private fun loadUrlFromInput() {
        var input = etUrl.text.toString().trim()
        if (input.isEmpty()) return
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            input = "https://$input"
        }
        tabManager.activeWebView?.loadUrl(input)
    }

    private fun setupBackPressed() {
        // canGoBack() なら履歴を戻り、なければタブを閉じる（設計書 6章）
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = tabManager.activeWebView
                when {
                    customView != null -> onHideCustomView()
                    webView != null && webView.canGoBack() -> webView.goBack()
                    tabManager.tabCount > 1 -> tabManager.closeActiveTab()
                    else -> finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun restoreOrOpenInitialTabs() {
        val savedTabs = prefs.getOpenTabs()
        if (savedTabs.isNotEmpty()) {
            savedTabs.forEachIndexed { index, url -> tabManager.openTab(url, activate = index == 0) }
        } else {
            val wpUrl = prefs.wpAdminUrl.ifBlank { null }
            wpUrl?.let { tabManager.openTab(it, activate = true) }
            tabManager.openTab(Prefs.CANVA_URL, activate = wpUrl == null)
        }
    }

    // --- メニュー -----------------------------------------------------------

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_add_bookmark -> promptAddBookmark()
                R.id.menu_bookmark_list -> showBookmarkList()
                R.id.menu_fullscreen -> toggleFullscreen()
                R.id.zoom_80 -> applyZoom(80)
                R.id.zoom_90 -> applyZoom(90)
                R.id.zoom_100 -> applyZoom(100)
                R.id.zoom_110 -> applyZoom(110)
                R.id.zoom_125 -> applyZoom(125)
                R.id.menu_open_external -> openInExternalBrowser()
                R.id.menu_settings -> showSettingsDialog()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun applyZoom(percent: Int) {
        prefs.zoomPercent = percent
        tabManager.activeWebView?.setInitialScale(percent)
    }

    private fun openInExternalBrowser() {
        val url = tabManager.activeWebView?.url ?: return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun promptAddBookmark() {
        val webView = tabManager.activeWebView ?: return
        val url = webView.url ?: return
        val input = EditText(this).apply {
            setText(webView.title ?: url)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_bookmark_title_hint)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                prefs.addBookmark(input.text.toString().ifBlank { url }, url)
                Toast.makeText(this, R.string.dialog_bookmark_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showBookmarkList() {
        val bookmarks = prefs.getBookmarks()
        val labels = bookmarks.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmark_list)
            .setItems(labels) { _, which -> tabManager.openTab(bookmarks[which].url) }
            .show()
    }

    private fun showSettingsDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.wpAdminUrl)
            hint = getString(R.string.dialog_wp_admin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_settings_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                prefs.wpAdminUrl = input.text.toString().trim()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // --- 全画面 --------------------------------------------------------------

    private fun toggleFullscreen() {
        isChromeHidden = !isChromeHidden
        toolbar.visibility = if (isChromeHidden) View.GONE else View.VISIBLE
        tabBar.visibility = if (isChromeHidden) View.GONE else View.VISIBLE
        setImmersiveMode(isChromeHidden)
    }

    private fun setImmersiveMode(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // --- キーボードショートカット（設計書 6章） -------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.isCtrlPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_T -> { openDefaultNewTab(); return true }
                    KeyEvent.KEYCODE_W -> {
                        if (tabManager.tabCount > 1) tabManager.closeActiveTab() else finish()
                        return true
                    }
                    KeyEvent.KEYCODE_L -> { etUrl.requestFocus(); etUrl.selectAll(); return true }
                    KeyEvent.KEYCODE_R -> { tabManager.activeWebView?.reload(); return true }
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_F11) { toggleFullscreen(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    // --- WebView バージョン確認（設計書 8章-3） --------------------------------

    private fun checkWebViewVersion() {
        val packageInfo = WebView.getCurrentWebViewPackage() ?: return
        val versionName = packageInfo.versionName ?: return
        prefs.uaVersion = versionName
        val majorVersion = versionName.substringBefore(".").toIntOrNull() ?: return
        if (majorVersion < 110) showWebViewOutdatedDialog()
    }

    private fun showWebViewOutdatedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_webview_outdated_title)
            .setMessage(R.string.dialog_webview_outdated_message)
            .setPositiveButton(R.string.dialog_open_store) { _, _ -> openWebViewStorePage() }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openWebViewStorePage() {
        val marketUri = Uri.parse("market://details?id=com.google.android.webview")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, marketUri))
        } catch (_: Exception) {
            val webUri = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    override fun onPause() {
        super.onPause()
        // ログイン状態が再起動後も残るよう Cookie を明示的に flush する（設計書 5章補足）
        CookieManager.getInstance().flush()
        prefs.saveOpenTabs(tabManager.urls())
    }

    // --- DesktopWebView.Callbacks --------------------------------------------

    override fun onOpenNewTab(url: String?): DesktopWebView = tabManager.openTab(url)

    override fun onShowFileChooser(callback: ValueCallback<Array<Uri>>): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            fileChooserLauncher.launch("*/*")
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            false
        }
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        fullscreenContainer.visibility = View.VISIBLE
        mainLayout.visibility = View.GONE
        setImmersiveMode(true)
    }

    override fun onHideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        mainLayout.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        setImmersiveMode(isChromeHidden)
    }

    override fun onRequestMediaPermission(request: PermissionRequest) {
        val needed = mutableListOf<String>()
        if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needed.add(Manifest.permission.CAMERA)
        if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needed.add(Manifest.permission.RECORD_AUDIO)
        val notGranted = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isEmpty()) {
            request.grant(request.resources)
        } else {
            pendingPermissionRequest = request
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    override fun onTitleChanged(webView: DesktopWebView, title: String?) {
        tabManager.updateTitle(webView, title)
    }

    override fun onPageFinished(webView: DesktopWebView, url: String?) {
        if (webView == tabManager.activeWebView) etUrl.setText(url ?: "")
    }
}
