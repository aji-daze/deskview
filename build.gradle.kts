// ルートビルドスクリプト。プラグインのバージョンだけをここで宣言し、実適用は app モジュール側で行う。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
