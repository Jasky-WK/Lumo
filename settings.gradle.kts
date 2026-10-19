pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bookkeeping"
include(":app")
// S4：性能基准 / Baseline Profile 生成模块（不参与 App 打包，只在跑基准时用到）
include(":benchmark")
