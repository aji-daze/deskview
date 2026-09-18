package dev.deskview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ブックマーク・タブ復元情報を SharedPreferences に JSON 文字列で保存する（設計書 7章）。
 * 件数が少ないため Room は使わず、org.json だけで十分。
 */
class Prefs(context: Context) {

    data class Bookmark(val title: String, val url: String)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "deskview_prefs"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_OPEN_TABS = "openTabs"
        private const val KEY_ZOOM_PERCENT = "zoomPercent"
        private const val KEY_UA_VERSION = "uaVersion"
        private const val KEY_WP_ADMIN_URL = "wpAdminUrl"
        const val DEFAULT_ZOOM_PERCENT = 100
        const val CANVA_URL = "https://www.canva.com/"
        // WP管理URL未設定時のフォールバック（WordPress.com のログイン/ダッシュボード）
        const val DEFAULT_WP_URL = "https://wordpress.com/"
    }

    // 設定画面で入力する WordPress 管理画面のURL（未設定時は空文字）
    var wpAdminUrl: String
        get() = prefs.getString(KEY_WP_ADMIN_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WP_ADMIN_URL, value).apply()

    // 未設定なら DEFAULT_WP_URL を返す。ブックマークや起動時タブはこちらを使う
    val wpUrlOrDefault: String
        get() = wpAdminUrl.ifBlank { DEFAULT_WP_URL }

    var zoomPercent: Int
        get() = prefs.getInt(KEY_ZOOM_PERCENT, DEFAULT_ZOOM_PERCENT)
        set(value) = prefs.edit().putInt(KEY_ZOOM_PERCENT, value).apply()

    // WebView 側の User-Agent 由来バージョン（起動時のバージョンチェックで更新）
    var uaVersion: String
        get() = prefs.getString(KEY_UA_VERSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UA_VERSION, value).apply()

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return defaultBookmarks()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            // 旧バージョンで空URLのまま保存された「WP管理」を救済する
            Bookmark(obj.getString("title"), obj.getString("url").ifBlank { wpUrlOrDefault })
        }
    }

    fun saveBookmarks(bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { b ->
            array.put(
                JSONObject().apply {
                    put("title", b.title)
                    put("url", b.url)
                }
            )
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    fun addBookmark(title: String, url: String) {
        val current = getBookmarks().toMutableList()
        current.add(Bookmark(title, url))
        saveBookmarks(current)
    }

    fun getOpenTabs(): List<String> {
        val raw = prefs.getString(KEY_OPEN_TABS, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getString(it) }
    }

    fun saveOpenTabs(urls: List<String>) {
        val array = JSONArray()
        urls.forEach { array.put(it) }
        prefs.edit().putString(KEY_OPEN_TABS, array.toString()).apply()
    }

    // 初回起動時のプリセット2件（設計書 4章）。wp-admin 未設定時は DEFAULT_WP_URL を使う。
    private fun defaultBookmarks(): List<Bookmark> = listOf(
        Bookmark("WP管理", wpUrlOrDefault),
        Bookmark("Canva", CANVA_URL)
    )
}
