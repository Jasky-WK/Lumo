package com.qingjizhang.app.ui.theme

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）风格令牌（P1）。
 *
 * 实现路线（见规划报告二）：**视觉仿制**——半透明底 + 高光描边 + 顶部高光渐变，
 * 不用真正的 backdrop blur（minSdk 26 下 API 31 以下无法真模糊，且模糊代价高）。
 * 因此本方案全版本一致、零兼容风险、几乎不增加渲染成本。
 *
 * 落地范围（刻意收敛，保证可读性与性能）：
 * - 适合：底部导航栏、FAB、底部面板（ModalBottomSheet）、对话框、顶部栏；
 * - 不适合：账单列表行、图表、表单输入区、表格页——这些继续用不透明卡片。
 *
 * 「减弱视觉效果」开关（设置页可切）会把所有玻璃面换成不透明卡片色，一键回退。
 */
val LocalReduceEffects = staticCompositionLocalOf { false }

data class GlassStyle(
    /** 玻璃底色（半透明） */
    val tint: Color,
    /** 高光描边（1dp） */
    val border: Color,
    /** 顶部高光渐变的起始色 */
    val topHighlight: Color,
    /** 是否画描边与高光（减弱视觉效果时为 false） */
    val decorated: Boolean
)

/** 当前玻璃风格（跟随主题深浅与「减弱视觉效果」开关） */
val glass: GlassStyle
    @Composable @ReadOnlyComposable
    get() {
        val c = LocalAppColors.current
        if (LocalReduceEffects.current) {
            return GlassStyle(c.card, c.line, Color.Transparent, decorated = false)
        }
        // 修正（v2.5.1）：底色一律不透明。
        // 只有底栏那一条用真毛玻璃（haze 取样模糊）；其余浮层（记一笔面板 / 文本输入 /
        // 对话框）如果也半透明，背后密密麻麻的账单文字会透上来，读起来非常乱。
        // 玻璃质感改由「1dp 高光描边 + 顶部高光渐变」表达，内容区保持纯色。
        return if (c.isDark) {
            GlassStyle(
                tint = c.card,
                border = Color.White.copy(alpha = 0.12f),
                topHighlight = Color.White.copy(alpha = 0.05f),
                decorated = true
            )
        } else {
            GlassStyle(
                tint = c.card,
                border = Color.White.copy(alpha = 0.9f),
                topHighlight = Color.White.copy(alpha = 0.45f),
                decorated = true
            )
        }
    }

/**
 * 玻璃面修饰符：圆角裁剪 + 纯色底 + 顶部高光 + 1dp 高光描边。
 * 高光画在内容下方（drawBehind），文字对比度不受影响。
 */
@Composable
fun Modifier.glassSurface(shape: Shape, style: GlassStyle = glass): Modifier = this
    .clip(shape)
    .drawBehind {
        drawRect(style.tint)
        if (style.decorated) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(style.topHighlight, Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.45f
                ),
                size = Size(size.width, size.height * 0.45f)
            )
        }
    }
    .then(if (style.decorated) Modifier.border(1.dp, style.border, shape) else Modifier)

/** 玻璃面用的组件形状（与 Material3 组件的默认圆角保持一致） */
object GlassShapes {
    val Sheet = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val Dialog = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    val Bar = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    /** 悬浮胶囊（底栏 / 选中高亮用） */
    val Pill = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
}

/** 便捷：拿 MaterialTheme 的 shape（当前工程未自定义 shape 体系，保留兜底） */
@Composable
fun sheetShape(): Shape = GlassShapes.Sheet

/** 便捷：对话框形状兜底 */
@Composable
fun dialogShape(): Shape = GlassShapes.Dialog
