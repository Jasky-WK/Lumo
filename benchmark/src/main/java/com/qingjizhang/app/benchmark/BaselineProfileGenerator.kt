package com.qingjizhang.app.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Profile 生成器（S4）。
 *
 * 在真机/模拟器上跑一次，产出的 profile 会自动被 :app 消费打进 APK，
 * 让首启/主路径的热方法走 AOT，冷启动通常能降 15%~25%。
 *
 * 跑法：
 *   gradlew :benchmark:connectedBenchmarkAndroidTest
 * 产出位置：app/src/main/generated/baselineProfiles/ 下的 profile 文本（插件自动放置）
 *
 * 覆盖的主路径（保持精简、稳定，不依赖具体文案）：
 *   冷启动 → 首页列表滚动 → 打开「记一笔」面板 → 关闭 → 切「总览」Tab
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.qingjizhang.app",
        includeInStartupProfile = true
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg("com.qingjizhang.app")), 5_000)

        // 首页账单列表滚动（用「可滚动容器」定位，不依赖文案）
        val list = device.findObject(By.scrollable(true))
        if (list != null) {
            repeat(2) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
                list.fling(Direction.UP)
                device.waitForIdle()
            }
        }

        // 底部 Tab：三个 Tab 都点一遍（记一笔 FAB / 账户 / 总览 的类加载都进 profile）
        val bottomBar = device.findObject(By.scrollable(true).depth(1))
        if (bottomBar != null) {
            listOf(0.85f, 0.5f, 0.15f).forEach { x ->
                val w = device.displayWidth
                val h = device.displayHeight
                device.click((w * x).toInt(), (h * 0.96).toInt())
                device.waitForIdle()
            }
        }
    }
}
