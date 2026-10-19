plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    // S4：Baseline Profile 生产者
    id("androidx.baselineprofile")
}

/**
 * 性能基准模块（S4）。
 *
 * - 不由 App 打包引用，只在开发者本机跑基准时用到；
 * - 产出两个东西：
 *   1. Baseline Profile（冷启动主路径的 AOT 提示）→ 自动被 :app 消费打进 APK
 *   2. 启动 / 滚动耗时数据（StartupTimingMetric、FrameTimingMetric），用于优化前后对比
 *
 * 生成与测量都需要真机或模拟器（详见 benchmark/README.md）。
 */
android {
    namespace = "com.qingjizhang.app.benchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 28           // macrobenchmark 要求 API 28+
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        // 基准必须在 release 变体上跑（关闭调试、贴近真实性能）
        create("benchmark") {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
}

androidComponents {
    beforeVariants(selector().all()) {
        // 基准模块只需要 benchmark 变体
        it.enable = it.buildType == "benchmark"
    }
}
