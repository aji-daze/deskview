# DeskView 設計書

Androidタブレットで WordPress 管理画面や Canva を「PCと同じデスクトップ表示」で使うための専用ブラウザアプリ。

## 1. 目的と非目的

**目的**
- タブレットでも WordPress (`/wp-admin`) と Canva をPC版レイアウトで開き、ブロックエディタやCanvaの編集画面をモバイル簡易版に落とされずに使う。
- ハードウェアキーボード・マウス（Bluetooth）での操作を前提にする。
- 実装量を最小にし、個人で保守できる規模に収める。

**非目的**
- 汎用ブラウザにしない（拡張機能・同期・広告ブロックは対象外）。
- Play Store 配布は前提にしない（GitHub Releases の APK 配布で十分）。

## 2. 方針の要点（なぜこの設計か）

| 選択肢 | 判断 | 理由 |
|---|---|---|
| Chrome の「PC版サイト」トグル | 不採用 | サイトごとに毎回切替が必要で、タブ復帰時に解除されることがある |
| Chrome Custom Tabs / TWA | 不採用 | デスクトップ表示を強制できない |
| **android.webkit.WebView + デスクトップUA** | **採用** | UA と viewport を固定でき、Cookie を自前で永続化できる。単一 Activity で完結 |
| GeckoView | 不採用 | 依存が重く、APK が数十MB増える。利点が本用途では薄い |

## 3. 技術スタック

- Kotlin、Android View + XML（Compose は使わない。WebView 中心の画面なので旧来 View の方が短く書ける）
- minSdk 26 / targetSdk 34
- 依存はほぼ AndroidX 標準のみ（`appcompat`, `webkit`, `material`）
- ビルド: Gradle Kotlin DSL、GitHub Actions で debug APK を自動ビルドし Artifacts に添付

## 4. 画面構成

単一 Activity。上部に薄いツールバー、下は全面 WebView。

```
┌──────────────────────────────────────────────┐
│ ◀ ▶ ⟳ │ https://example.com/wp-admin │ ⋮ │ ← ツールバー(48dp)。全画面時は非表示
├──────────────────────────────────────────────┤
│ [WP管理] [Canva] [+]                         │ ← タブ列。長押しで閉じる
├──────────────────────────────────────────────┤
│                                              │
│                 WebView                      │
│                                              │
└──────────────────────────────────────────────┘
```

「⋮」メニュー: ブックマーク追加 / ブックマーク一覧 / 全画面 / ズーム(80–125%) / 外部ブラウザで開く / 設定。

初回起動時にブックマークを2件プリセット: `https://<自分のサイト>/wp-admin/`（設定画面で入力）、`https://www.canva.com/`。

## 5. デスクトップ表示の核心設定

`WebSettings` に以下をまとめて適用する（ここが本アプリの価値の9割）。

```kotlin
userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
useWideViewPort = true          // meta viewport を無視してデスクトップ幅で描画
loadWithOverviewMode = true     // 全体を画面幅に収める
javaScriptEnabled = true
domStorageEnabled = true        // WP/Canva とも localStorage 必須
databaseEnabled = true
mediaPlaybackRequiresUserGesture = false
setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
```

補足:
- UA 文字列に `wv` や `Mobile` を含めない。Chrome のバージョン番号は定数化し、古くなったら更新するだけにする。
- `useWideViewPort` だけでは横幅が 980px 相当になるため、ページ読込後に `window.innerWidth` が 1280 未満なら `initialScale` を調整してデスクトップ幅を確保する（`onPageFinished` で `setInitialScale` を計算）。
- Cookie は `CookieManager.setAcceptThirdPartyCookies(webView, true)` を有効にし、`onPause` で `flush()` する。これでログイン状態が再起動後も残る。

## 6. 必須のハンドラ実装

| 機能 | 実装箇所 | 要点 |
|---|---|---|
| 画像アップロード（メディアライブラリ／Canva素材） | `WebChromeClient.onShowFileChooser` | `ActivityResultContracts.GetMultipleContents` で複数選択に対応 |
| ダウンロード（Canva書き出し、WPエクスポート） | `WebView.setDownloadListener` | `DownloadManager` に投げ、Cookie と UA を引き継ぐ。保存先は `Download/` |
| 新規ウィンドウ（`target=_blank`、OAuth ポップアップ） | `WebChromeClient.onCreateWindow` | 新規タブとして開く。`setSupportMultipleWindows(true)` が必要 |
| 外部スキーム（`mailto:`、`intent:`） | `shouldOverrideUrlLoading` | Intent に渡す。http(s) は WebView 内で処理 |
| 戻るキー | `onBackPressedDispatcher` | `canGoBack()` なら履歴を戻り、なければタブを閉じる |
| 全画面動画・全画面編集 | `onShowCustomView` / `onHideCustomView` | Canva のプレゼン再生に必要 |
| ハードウェアキーボード | `dispatchKeyEvent` | Ctrl+T 新規タブ、Ctrl+W 閉じる、Ctrl+L URL欄、Ctrl+R 再読込、F11 全画面 |
| 権限 | `WebChromeClient.onPermissionRequest` | カメラ・マイクは Canva の録画機能用に許可（要ランタイム権限） |

## 7. データ保存

- ブックマークとタブの復元情報は `SharedPreferences` に JSON 文字列で保存（Room は使わない。件数が少ない）。
- 保存内容: `bookmarks: [{title, url}]`, `openTabs: [url]`, `zoomPercent`, `uaVersion`。

## 8. 既知のリスクと対処

1. **Google ログインが WebView で拒否される（`disallowed_useragent`）**
   Google は埋め込み WebView での OAuth を検知して拒否する。デスクトップ Chrome の UA を名乗ることで大半は通るが、保証はない。
   対処: 設定画面に「ログインだけ外部ブラウザで行う」導線は作れないため（Cookie が共有されない）、アプリ内では **メール+パスワードでのログイン** を推奨として README に明記する。Canva はメールログイン可、WordPress は通常自前認証なので影響なし。
2. **Canva の描画負荷**
   WebView は Chrome 本体と同じ Blink なので機能面は問題ないが、`android:hardwareAccelerated="true"` をマニフェストで明示する。RAM 4GB 未満の端末は対象外と README に書く。
3. **Android System WebView のバージョン依存**
   端末側の WebView が古いと Canva が動かない。起動時に `WebView.getCurrentWebViewPackage()` でバージョンを確認し、110 未満なら更新を促すダイアログを出す。
4. **画面回転でタブが消える**
   `android:configChanges="orientation|screenSize|keyboardHidden"` で Activity 再生成を防ぐ。

## 9. リポジトリ構成

```
deskview/
├── .github/workflows/build.yml     # push で debug APK を Artifacts に添付
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/deskview/
│       │   ├── MainActivity.kt        # ツールバー・タブ・キー操作
│       │   ├── DesktopWebView.kt      # WebSettings 一式と各ハンドラ
│       │   ├── TabManager.kt          # タブの生成・切替・復元
│       │   └── Prefs.kt               # ブックマーク/タブの永続化
│       └── res/layout/activity_main.xml
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── README.md                          # 使い方・ログインの注意・対応端末
└── DESIGN.md                          # 本書
```

## 10. マイルストーン

| 段階 | 内容 | 完了条件 |
|---|---|---|
| M1 | 単一タブ WebView でデスクトップUA表示 | wp-admin と canva.com がPC版レイアウトで開く |
| M2 | ファイル選択・ダウンロード・新規ウィンドウ | WPメディアに画像を上げ、Canva から PNG を書き出せる |
| M3 | タブ・ブックマーク・復元 | 再起動後にログイン状態とタブが残る |
| M4 | キーボードショートカット・全画面 | 上記ショートカットが全て動く |
| M5 | CI で APK 自動ビルド、README 整備 | GitHub Actions が緑で APK を落とせる |

M1 で価値の大半が出るため、M1 を最優先で動かしてから残りを足す。
