plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器随 Kotlin 发布（独立的 compose 插件），不再用 composeOptions
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    // S4：Baseline Profile（启动优化）。生成 profile 需要真机/模拟器，见 benchmark/README.md
    id("androidx.baselineprofile") version "1.2.4" apply false
}
