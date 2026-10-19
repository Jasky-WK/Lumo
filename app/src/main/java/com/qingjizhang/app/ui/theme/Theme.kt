package com.qingjizhang.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ---------------- 主题色 / 深浅模式（N11） ----------------

/** 主题色：只换主色，收入 / 支出等语义色不变 */
enum class ThemeColor(val label: String) {
    BLUE("蓝"), GREEN("绿"), WHITE("白")
}

/** 深浅模式 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

// ---------------- 不随主题变化的强调色（图表 / 分类 / 账户徽标共用） ----------------

val Expense = Color(0xFFE87461)
val Income = Color(0xFF2E9E6B)

val Amber = Color(0xFFFFB547)
val Teal = Color(0xFF3EC5A8)
val Purple = Color(0xFF9B8CFF)
val Sky = Color(0xFF6EC1E4)
val Pink = Color(0xFFF0709B)
val Orange = Color(0xFFF2A24B)

/** 固定品牌蓝（分类 / 账户徽标在非 Composable 场景下使用） */
val BrandBlue = Color(0xFF4C7DF0)

/** 固定中性灰（同上） */
val NeutralGray = Color(0xFF8A93A5)

/** 分类/图表的调色板 */
val Palette = listOf(
    Expense, Sky, Amber, Teal, Purple, Pink, Orange, Color(0xFF9AA5B8)
)

/** 支付宝 / 微信品牌色（不随主题变化） */
val AlipayBlue = Color(0xFF1677FF)
val WeChatGreen = Color(0xFF07C160)

// ---------------- 语义色表 ----------------

@Immutable
data class AppColors(
    val isDark: Boolean,
    val primary: Color,
    /** 主色底上的前景色：浅色下白字，深色下深墨字（深色主色偏亮，白字对比度不够） */
    val onPrimary: Color,
    val primaryLight: Color,
    val primaryLighter: Color,
    val primaryBg: Color,
    /** 主色的深色版，用在浅底提示条的文字/图标上 */
    val primaryDeep: Color,
    val bg: Color,
    val card: Color,
    val ink: Color,
    val sub: Color,
    val sub2: Color,
    val line: Color,
    /** 输入框 / 次级分段底色 */
    val field: Color,
    /** 更浅一档的填充块底色 */
    val fieldAlt: Color,
    /** 右侧箭头、空态图标 */
    val chevron: Color,
    /** 占位灰字 */
    val muted: Color,
    /** 分组标题灰 */
    val muted2: Color,
    /** 开关未打开时的轨道 */
    val track: Color,
    val warnBg: Color,
    val warnFg: Color,
    val warnIcon: Color,
    val hero: List<Color>,
    val splash: List<Color>
)

private fun accentOf(c: ThemeColor): Color = when (c) {
    ThemeColor.BLUE -> BrandBlue
    ThemeColor.GREEN -> Income
    ThemeColor.WHITE -> Color(0xFF5A6472)
}

private fun colorsFor(mode: ThemeMode, theme: ThemeColor, systemDark: Boolean): AppColors {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val accent = accentOf(theme)
    return if (dark) {
        val primary = lerp(accent, Color.White, 0.22f)
        AppColors(
            isDark = true,
            primary = primary,
            onPrimary = Color(0xFF10161E),
            primaryLight = lerp(accent, Color.White, 0.32f),
            primaryLighter = lerp(accent, Color.White, 0.45f),
            primaryBg = lerp(accent, Color(0xFF0F1319), 0.78f),
            primaryDeep = lerp(accent, Color.White, 0.42f),
            bg = Color(0xFF0F1319),
            card = Color(0xFF1A2028),
            ink = Color(0xFFE9EEF6),
            sub = Color(0xFF8B94A6),
            sub2 = Color(0xFFAEB7C6),
            line = Color(0xFF262E39),
            field = Color(0xFF212933),
            fieldAlt = Color(0xFF1F272F),
            chevron = Color(0xFF525C6D),
            muted = Color(0xFF5C6675),
            muted2 = Color(0xFF6E7889),
            track = Color(0xFF333C4A),
            warnBg = Color(0xFF33291A),
            warnFg = Color(0xFFE5B25F),
            warnIcon = Color(0xFFEFAE4E),
            hero = listOf(lerp(accent, Color.Black, 0.48f), lerp(accent, Color.Black, 0.26f)),
            splash = listOf(lerp(accent, Color.Black, 0.16f), lerp(accent, Color.Black, 0.26f), lerp(accent, Color.Black, 0.38f))
        )
    } else {
        AppColors(
            isDark = false,
            primary = accent,
            onPrimary = Color.White,
            primaryLight = lerp(accent, Color.White, 0.12f),
            primaryLighter = lerp(accent, Color.White, 0.3f),
            primaryBg = lerp(accent, Color.White, 0.88f),
            primaryDeep = lerp(accent, Color.Black, 0.18f),
            bg = Color(0xFFF4F6FB),
            card = Color(0xFFFFFFFF),
            ink = Color(0xFF1B2430),
            sub = NeutralGray,
            sub2 = Color(0xFF5C6577),
            line = Color(0xFFEDF1F8),
            field = Color(0xFFF2F5FB),
            fieldAlt = Color(0xFFF6F8FD),
            chevron = Color(0xFFC6CEDC),
            muted = Color(0xFFB4BDCC),
            muted2 = Color(0xFFA6B0C3),
            track = Color(0xFFE2E8F3),
            warnBg = Color(0xFFFFF7E8),
            warnFg = Color(0xFFA9722A),
            warnIcon = Color(0xFFF09B2E),
            hero = listOf(lerp(accent, Color.White, 0.12f), lerp(accent, Color.White, 0.3f)),
            splash = listOf(lerp(accent, Color.Black, 0.16f), lerp(accent, Color.Black, 0.26f), lerp(accent, Color.Black, 0.38f))
        )
    }
}

val LocalAppColors = staticCompositionLocalOf {
    colorsFor(ThemeMode.LIGHT, ThemeColor.BLUE, false)
}

/** 需要整体取用时用它（例如自定义绘制） */
val colors: AppColors
    @Composable @ReadOnlyComposable get() = LocalAppColors.current

// ---------------- 语义色（旧的变量名保持不变，改为可组合读取） ----------------

val Primary: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.primary
val OnPrimary: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimary
val PrimaryLight: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryLight
val PrimaryLighter: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryLighter
val PrimaryBg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryBg
val PrimaryDeep: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryDeep

val Bg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.bg
val CardWhite: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.card
val Ink: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.ink
val Sub: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.sub
val Sub2: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.sub2
val Line: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.line

val FieldBg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.field
val FieldBgAlt: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.fieldAlt
val Chevron: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.chevron
val Muted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted
val Muted2: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted2
val Track: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.track
val WarnBg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warnBg
val WarnFg: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warnFg
val WarnIcon: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warnIcon

val IsDarkTheme: Boolean @Composable @ReadOnlyComposable get() = LocalAppColors.current.isDark

/** 结余卡渐变 */
val HeroGradient: Brush
    @Composable @ReadOnlyComposable get() = Brush.linearGradient(LocalAppColors.current.hero)

/** 启动屏渐变（与 app 图标底同色系，白字对比度 ≥ 4.5:1） */
val SplashGradient: Brush
    @Composable @ReadOnlyComposable get() = Brush.linearGradient(LocalAppColors.current.splash)

private val appTypography = Typography().let { t ->
    t.copy(
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontSize = 15.sp),
        bodyMedium = TextStyle(fontSize = 14.sp),
        bodySmall = TextStyle(fontSize = 12.sp)
    )
}

@Composable
fun BookkeepingTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    themeColor: ThemeColor = ThemeColor.BLUE,
    content: @Composable () -> Unit
) {
    val c = colorsFor(themeMode, themeColor, isSystemInDarkTheme())
    // 状态栏 / 导航栏图标颜色跟随 App 内的深浅模式（不只是系统深浅）：
    // MainActivity 用了 enableEdgeToEdge()，只在 xml 里写 windowLightStatusBar 的话，
    // 「系统浅色 + App 选深色」时会出现深底配深图标看不见。这里统一由 Compose 侧覆盖。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !c.isDark
            controller.isAppearanceLightNavigationBars = !c.isDark
        }
    }
    val scheme = if (c.isDark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryBg,
            onPrimaryContainer = c.primary,
            secondary = Teal,
            background = c.bg,
            onBackground = c.ink,
            surface = c.card,
            onSurface = c.ink,
            surfaceVariant = c.line,
            onSurfaceVariant = c.sub2,
            error = Expense
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = c.primaryBg,
            onPrimaryContainer = c.primary,
            secondary = Teal,
            background = c.bg,
            onBackground = c.ink,
            surface = c.card,
            onSurface = c.ink,
            surfaceVariant = c.line,
            onSurfaceVariant = c.sub2,
            error = Expense
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = appTypography,
            content = content
        )
    }
}

/** 从 Compose 的 view.context 一路往外找 Activity（实际拿到的常是 ContextWrapper 包装过的） */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
