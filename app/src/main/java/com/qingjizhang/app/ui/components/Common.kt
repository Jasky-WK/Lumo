package com.qingjizhang.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import com.qingjizhang.app.ui.theme.CardWhite
import com.qingjizhang.app.ui.theme.GlassShapes
import com.qingjizhang.app.ui.theme.glass
import com.qingjizhang.app.ui.theme.glassSurface
import com.qingjizhang.app.ui.theme.Chevron
import com.qingjizhang.app.ui.theme.Expense
import com.qingjizhang.app.ui.theme.FieldBg
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Line
import com.qingjizhang.app.ui.theme.Muted
import com.qingjizhang.app.ui.theme.Primary
import com.qingjizhang.app.ui.theme.PrimaryBg
import com.qingjizhang.app.ui.theme.PrimaryDeep
import com.qingjizhang.app.ui.theme.Sub
import com.qingjizhang.app.ui.theme.Sub2
import com.qingjizhang.app.ui.theme.Track
import com.qingjizhang.app.util.KeySound
import com.qingjizhang.app.util.Money
import java.util.Locale

/**
 * 轻量振动反馈（N37）。
 *
 * 只在用户可以感知的关键操作上触发一次「轻点」级别的反馈（记账键盘按键、切换分类 / 账户等），
 * 不做高频振动。走 Compose 的 [LocalHapticFeedback]（即 View.performHapticFeedback），
 * 由系统按窗口的触感设置决定实际强度，**不需要 VIBRATE 权限**，开关关闭时直接不触发。
 *
 * 用法：`val tap = rememberKeyHaptics(vibrateOn)`，在按键 / 选择回调里调 `tap()`。
 */
@Composable
fun rememberKeyHaptics(enabled: Boolean): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(enabled, haptic) {
        {
            if (enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
    }
}

/**
 * 关键操作的轻振回调（N37）。
 * 记一笔页面用 `CompositionLocalProvider(LocalKeyHaptic provides tap)` 提供，
 * 分类胶囊 / 账户选择行 / 记账键盘按键这类组件直接取用，不必一层层往下传参数。
 * 默认是空实现（其它页面不受影响）。
 */
val LocalKeyHaptic = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 按键音回调（N39）。
 *
 * 用法：`val ding = rememberKeySound(soundOn)`，在按键 / 选择回调里调 `ding(KeySound.Scene.XXX)`。
 * 四段音效随包内置在 `res/raw`（记一笔 / 选择分类 / 输入金额 / 保存账单），
 * 播放走闹钟音频流，手机静音 / 勿扰时也照常响（见 [KeySound]）。
 * 组合时就一次性预加载四段，避开第一次按键的加载延迟；开关关闭时不播放。
 */
@Composable
fun rememberKeySound(enabled: Boolean): (KeySound.Scene) -> Unit {
    val context = LocalContext.current
    LaunchedEffect(enabled, context) {
        if (enabled) KeySound.prepare(context.applicationContext)
    }
    return remember(enabled, context) {
        { scene -> if (enabled) KeySound.play(context.applicationContext, scene) }
    }
}

/**
 * 按键音回调（N39），与 [LocalKeyHaptic] 相互独立、可分别开关。
 * 记一笔页面用 `CompositionLocalProvider(LocalKeySound provides ding)` 提供，
 * 按下时按场景传入 [KeySound.Scene]。
 */
val LocalKeySound = staticCompositionLocalOf<(KeySound.Scene) -> Unit> { {} }

/** 白底圆角卡片，全站统一 */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    radius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(radius),
                ambientColor = Color(0x14182648),
                spotColor = Color(0x1A182648)
            ),
        shape = RoundedCornerShape(radius),
        color = CardWhite,
        content = { Column(content = content) }
    )
}

/** 金额：¥ 与小数位更小，整数位突出 */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    color: Color = Ink,
    fontSize: TextUnit = 16.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    forceSign: Boolean = false
) {
    val abs = Math.abs(cents)
    val parts = Money.text(abs).split(".")
    val sign = when {
        forceSign && cents > 0 -> "+"
        cents < 0 -> "-"
        else -> ""
    }
    val text = buildAnnotatedString {
        if (sign.isNotEmpty()) append(sign)
        withStyle(SpanStyle(fontSize = fontSize * 0.68f, fontWeight = FontWeight.SemiBold)) {
            append("¥")
        }
        withStyle(SpanStyle(fontSize = fontSize, fontWeight = fontWeight)) {
            append(parts[0])
        }
        if (parts.size > 1) {
            withStyle(SpanStyle(fontSize = fontSize * 0.7f, fontWeight = FontWeight.SemiBold)) {
                append("." + parts[1])
            }
        }
    }
    Text(text = text, color = color, modifier = modifier, maxLines = 1)
}

/** 圆形/圆角图标徽标 */
@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
    radius: Dp = 12.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(iconSize))
    }
}

/** 品牌文字徽标（支付宝「支」/ 微信「微」） */
@Composable
fun TextBadge(text: String, color: Color, size: Dp = 36.dp, radius: Dp = 12.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontSize = (size.value * 0.34f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(trailing, fontSize = 11.5.sp, color = Sub)
        }
    }
}

/** 设置/表单里的一行 */
@Composable
fun SettingRow(
    label: String,
    value: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Primary,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    disabled: Boolean = false,
    showArrow: Boolean = !danger
) {
    val labelColor = when {
        danger -> com.qingjizhang.app.ui.theme.Expense
        disabled -> Muted
        else -> Ink
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = !disabled) { onClick() } else Modifier)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IconBadge(icon, if (disabled) Muted else iconTint, size = 30.dp, iconSize = 17.dp, radius = 10.dp)
            Spacer(Modifier.width(11.dp))
        }
        Text(label, fontSize = 14.sp, color = labelColor)
        Spacer(Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                fontSize = 12.5.sp,
                color = if (disabled) Muted else Sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(2.dp))
        }
        if (showArrow) {
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = Chevron,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

/** 顶栏：左侧抽屉按钮 + 标题 */
@Composable
fun TopBar(
    title: String,
    onMenu: (() -> Unit)? = null,
    caret: Boolean = false,
    /** 长按标题：主页面用来回到顶部 */
    onTitleLongPress: (() -> Unit)? = null,
    /** 点标题（含标题右侧的 ∨）：首页用来弹出账本切换 */
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    // 用 rememberUpdatedState 拿最新的回调：让 pointerInput 的 key 保持稳定，
    // 页面重组不会打断正在进行的点击 / 长按手势
    val titleClick = rememberUpdatedState(onTitleClick)
    val titleLongPress = rememberUpdatedState(onTitleLongPress)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onMenu != null) {
            IconAction(Icons.Rounded.Menu, onMenu)
        } else {
            Spacer(Modifier.width(6.dp))
        }
        Row(
            modifier = Modifier
                .then(if (onTitleClick != null) Modifier.padding(horizontal = 6.dp) else Modifier)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { titleClick.value?.invoke() },
                        onLongPress = { titleLongPress.value?.invoke() }
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (caret) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

/** 顶栏：返回 + 标题 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconAction(Icons.Rounded.ArrowBack, onBack)
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
fun IconAction(icon: ImageVector, onClick: () -> Unit, tint: Color = Ink, size: Dp = 22.dp) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

/** 分段切换（支出/收入/互转 这类主切换） */
@Composable
fun SegTabs(
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = CardWhite) {
        Row(Modifier.padding(4.dp)) {
            items.forEachIndexed { i, label ->
                val on = i == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (on) PrimaryBg else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        fontSize = 13.5.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) Primary else Sub
                    )
                }
            }
        }
    }
}

/** 次级分段（大类/小类/标签/账户） */
@Composable
fun SegSmall(
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(FieldBg)
            .padding(3.dp)
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) CardWhite else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Primary else Sub2
                )
            }
        }
    }
}

@Composable
fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 25.dp)
            .clip(CircleShape)
            .background(if (checked) Primary else Track)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(start = if (checked) 22.dp else 3.dp)
                .size(19.dp)
                .clip(CircleShape)
                .background(CardWhite)
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Chevron, modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 14.sp, color = Sub2, textAlign = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, fontSize = 12.sp, color = Sub, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** 提示条（说明 / 待生成提醒） */
@Composable
fun TipBanner(
    text: String,
    icon: ImageVector,
    bg: Color = PrimaryBg,
    fg: Color = PrimaryDeep,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = if (bg == PrimaryBg) null else BorderStroke(1.dp, bg.copy(alpha = 0.9f))
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(9.dp))
            Text(text, fontSize = 12.sp, color = fg, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            if (action != null) action()
        }
    }
}

fun pct(a: Long, b: Long): String =
    if (b <= 0L) "0.0%" else String.format(Locale.CHINA, "%.1f%%", a * 100.0 / b)

/** 二次确认弹窗（删除账单 / 恢复备份 / 清空数据都用它） */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "确认",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // P1 液态玻璃：对话框用半透明玻璃底 + 高光边（「减弱视觉效果」时自动退化为不透明）
        containerColor = glass.tint,
        shape = GlassShapes.Dialog,
        modifier = if (glass.decorated) Modifier.border(1.dp, glass.border, GlassShapes.Dialog) else Modifier,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text(text, fontSize = 13.5.sp, color = Sub2, lineHeight = 20.sp) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (danger) Expense else Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}

/**
 * N35 删除账户弹窗：先告诉用户该账户下有多少笔账单，再让用户明确选择账单怎么处理。
 * - 「保留账单」（推荐）：只删账户，账单全部保留，仍参与流水 / 报表 / 统计；
 * - 「删除账单」：账户与账单一起进回收站（保留 30 天，可还原）。
 */
@Composable
fun DeleteAccountDialog(
    accountName: String,
    txnCount: Int,
    onKeepTxns: () -> Unit,
    onDeleteTxns: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // P1 液态玻璃：对话框用半透明玻璃底 + 高光边（「减弱视觉效果」时自动退化为不透明）
        containerColor = glass.tint,
        shape = GlassShapes.Dialog,
        modifier = if (glass.decorated) Modifier.border(1.dp, glass.border, GlassShapes.Dialog) else Modifier,
        title = { Text("删除账户「$accountName」？", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "该账户下有 $txnCount 笔账单，请选择账单怎么处理：",
                    fontSize = 13.5.sp,
                    color = Ink,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "· 保留账单（推荐）：只删除账户，账单全部保留，仍出现在流水、报表与统计里，" +
                        "账户名处显示「已删除账户」，收支合计不受影响。账户进回收站保留 30 天。",
                    fontSize = 12.5.sp,
                    color = Sub2,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "· 删除账单：账户与它名下的 $txnCount 笔账单一起进回收站，保留 30 天，期间可随时还原。",
                    fontSize = 12.5.sp,
                    color = Expense,
                    lineHeight = 19.sp
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDeleteTxns) {
                    Text("删除账单", color = Expense, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onKeepTxns) {
                    Text("保留账单", color = Primary, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}

/** 输入弹窗（改余额、改名称、设预算） */
@Composable
fun InputDialog(
    title: String,
    initial: String,
    label: String = "",
    numberOnly: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // P1 液态玻璃：对话框用半透明玻璃底 + 高光边（「减弱视觉效果」时自动退化为不透明）
        containerColor = glass.tint,
        shape = GlassShapes.Dialog,
        modifier = if (glass.decorated) Modifier.border(1.dp, glass.border, GlassShapes.Dialog) else Modifier,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = if (label.isBlank()) null else ({ Text(label) }),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (numberOnly) androidx.compose.ui.text.input.KeyboardType.Decimal
                    else androidx.compose.ui.text.input.KeyboardType.Text
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value); onDismiss() }) {
                Text("确定", color = Primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Sub) }
        }
    )
}
