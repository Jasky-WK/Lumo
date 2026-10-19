import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    // S4：消费 benchmark 模块生成的 Baseline Profile（跑过基准后自动生效）
    id("androidx.baselineprofile")
}

// N60：导出 Room schema（JSON），迁移测试（MigrationTestHelper）靠它创建旧版本库
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 签名文件不入库（见仓库根目录 .gitignore）：本地有 app/bookkeeping.jks 就用它签 Release；
// 别人 clone 下来没有这个文件时退回 debug 签名，保证 assembleRelease 依然能直接跑通。
// 密码同样不入库：优先读 app/keystore.properties（本地自建），其次环境变量，都没有就退回 debug 签名。
// keystore.properties 格式（本地创建，勿提交）：
//   storeFile=bookkeeping.jks
//   storePassword=你的密码
//   keyAlias=你的别名
//   keyPassword=你的密码
val releaseKeystore = file("bookkeeping.jks")
val keystorePropsFile = file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val storePass: String? = keystoreProps.getProperty("storePassword")
    ?: System.getenv("KEYSTORE_PASSWORD")
val keyPass: String? = keystoreProps.getProperty("keyPassword")
    ?: System.getenv("KEYSTORE_PASSWORD")
val keyAliasName: String? = keystoreProps.getProperty("keyAlias")
    ?: System.getenv("KEYSTORE_ALIAS")
val hasReleaseKeystore = releaseKeystore.exists() &&
    storePass != null && keyPass != null && keyAliasName != null

android {
    namespace = "com.qingjizhang.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qingjizhang.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 51
        versionName = "2.9.1"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8：代码压缩（摇树 + 优化）与资源压缩全开。
            // Release 包是体积与流畅度的唯一评估口径（Debug 包未优化，天然卡顿）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        // N60：迁移测试跑在 Robolectric 上，需要能访问资源与 instrumentation
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // S4：安装/消费 Baseline Profile（APK 内 profile 由 profileinstaller 在安装后编译）
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    baselineProfile(project(":benchmark"))

    // P1 液态玻璃：底栏 / 弹窗的「真实背景模糊」（API 31+ 真模糊，低版本自动降级为半透明）
    implementation("dev.chrisbanes.haze:haze:1.2.2")

    testImplementation("junit:junit:4.13.2")
    // N60：Room 迁移测试（Robolectric 提供 Android 运行时，首次运行会下载 android-all）
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
}
