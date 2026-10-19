package com.qingjizhang.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 全局动效统一入口（M8）。
 *
 * 页面切换此前走 Navigation 的默认过渡：淡入淡出各 700ms，切一次页面有明显「等一等」的拖沓感。
 * 这里统一压到 180ms 量级，并附一个很小的横向位移，观感更快也更利落；
 * 退出比进入更短（旧页面尽快让位），弹窗 / 抽屉 / 键盘的显隐时长同步参考这个量级，
 * 避免多条动画叠加时互相排队。
 */
object Motion {
    /** 页面进入时长（毫秒） */
    const val PAGE_IN = 180

    /** 页面退出时长：比进入更短，旧页面尽快让位 */
    const val PAGE_OUT = 110

    /** 页面切换附带的横向位移比例（相对屏宽），越小越干脆 */
    private const val SLIDE = 0.06f

    val enter: EnterTransition =
        fadeIn(tween(PAGE_IN)) + slideInHorizontally(tween(PAGE_IN)) { (it * SLIDE).toInt() }

    val exit: ExitTransition =
        fadeOut(tween(PAGE_OUT)) + slideOutHorizontally(tween(PAGE_OUT)) { -(it * SLIDE / 2).toInt() }

    val popEnter: EnterTransition =
        fadeIn(tween(PAGE_IN)) + slideInHorizontally(tween(PAGE_IN)) { -(it * SLIDE).toInt() }

    val popExit: ExitTransition =
        fadeOut(tween(PAGE_OUT)) + slideOutHorizontally(tween(PAGE_OUT)) { (it * SLIDE / 2).toInt() }
}
