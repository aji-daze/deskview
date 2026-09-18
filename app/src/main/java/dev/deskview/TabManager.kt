package dev.deskview

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * タブの生成・切替・復元を担当する（設計書 9章）。
 * WebView 自体の設定は DesktopWebView に任せ、ここではタブ一覧とタブ列UIだけを扱う。
 */
class TabManager(
    private val container: FrameLayout,
    private val tabStrip: LinearLayout,
    private val webViewFactory: () -> DesktopWebView,
    private val onActiveTabChanged: (DesktopWebView) -> Unit
) {
    private data class Tab(val webView: DesktopWebView, var title: String, val chip: TextView)

    private val tabs = mutableListOf<Tab>()
    private var activeIndex = -1

    val activeWebView: DesktopWebView?
        get() = tabs.getOrNull(activeIndex)?.webView

    val tabCount: Int get() = tabs.size

    fun openTab(url: String?, activate: Boolean = true): DesktopWebView {
        val webView = webViewFactory()
        container.addView(webView, matchParentParams())
        webView.visibility = View.GONE

        val chip = createChip("新規タブ")
        val tab = Tab(webView, "新規タブ", chip)
        tabs.add(tab)
        chip.setOnClickListener { activateTab(tabs.indexOf(tab)) }
        chip.setOnLongClickListener { closeTab(tabs.indexOf(tab)); true }
        tabStrip.addView(chip)

        url?.let { webView.loadUrl(it) }
        if (activate) activateTab(tabs.size - 1)
        return webView
    }

    fun updateTitle(webView: DesktopWebView, title: String?) {
        val tab = tabs.firstOrNull { it.webView === webView } ?: return
        tab.title = title?.takeIf { it.isNotBlank() } ?: tab.title
        tab.chip.text = tab.title
    }

    fun activateTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.forEachIndexed { i, tab ->
            tab.webView.visibility = if (i == index) View.VISIBLE else View.GONE
            tab.chip.isSelected = i == index
        }
        activeIndex = index
        onActiveTabChanged(tabs[index].webView)
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        container.removeView(tab.webView)
        tabStrip.removeView(tab.chip)
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            activeIndex = -1
            return
        }
        activateTab(index.coerceAtMost(tabs.size - 1))
    }

    fun closeActiveTab() {
        if (activeIndex != -1) closeTab(activeIndex)
    }

    // SharedPreferences 保存用（設計書 7章: openTabs: [url]）
    fun urls(): List<String> = tabs.mapNotNull { it.webView.url }

    private fun createChip(label: String): TextView = TextView(tabStrip.context).apply {
        text = label
        setPadding(24, 12, 24, 12)
        isClickable = true
        isFocusable = true
        maxLines = 1
    }

    private fun matchParentParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )
}
