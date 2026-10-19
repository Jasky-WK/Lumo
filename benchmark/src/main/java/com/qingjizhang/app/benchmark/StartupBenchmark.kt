package com.qingjizhang.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 启动与滚动性能基准（S4）。
 *
 * 用法（真机连上、开发者选项里打开「不锁定屏幕」与「USB 调试」）：
 *   gradlew :benchmark:connectedBenchmarkAndroidTest
 * 输出在 build/outputs/connected_android_test_additional_output/ 与
 * benchmark/build/outputs/androidTest-results/connected/ 里。
 *
 * 关注的数字：
 * - timeToInitialDisplay（TTID）：冷启动到首帧
 * - timeToFullDisplay（TTFD）：冷启动到界面完全可用
 * - frameDurationCpuMs P90/P95：滚动时是否有掉帧（玻璃质感改造后用它守门）
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val packageName = "com.qingjizhang.app"

    /** 冷启动（编译模式给满，测"最好情况"基线） */
    @Test
    fun coldStartupCompilationNone() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    /** 冷启动（默认编译模式，更接近用户升级后第一次打开的真实情况） */
    @Test
    fun coldStartupDefault() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    /** 热启动 */
    @Test
    fun warmStartup() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = { pressHome() }
    ) {
        startActivityAndWait()
    }

    /** 首页账单列表滚动流畅度（不依赖具体文案，找第一个可滚动容器） */
    @Test
    fun homeScrollFrameTiming() = benchmarkRule.measureRepeated(
        packageName = packageName,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = 5
    ) {
        startActivityAndWait()
        val list = device.findObject(By.scrollable(true)) ?: return@measureRepeated
        repeat(3) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
            list.fling(Direction.UP)
            device.waitForIdle()
        }
    }
}
