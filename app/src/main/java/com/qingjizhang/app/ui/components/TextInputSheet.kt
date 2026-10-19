package com.qingjizhang.app.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.qingjizhang.app.ui.theme.CardWhite
import com.qingjizhang.app.ui.theme.glass
import com.qingjizhang.app.ui.theme.glassSurface
import com.qingjizhang.app.ui.theme.Chevron
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Muted
import com.qingjizhang.app.ui.theme.Primary
import com.qingjizhang.app.ui.theme.Sub2

/**
 * 文本输入公共组件（M5）：所有需要唤起系统输入法的输入都走这里，禁止再各自手写键盘逻辑。
 *
 * 设计要点（F12 / F13 / F14 都靠它统一）：
 * - **独立窗口**：和宿主面板（如记一笔面板）不共用 Window，系统输入法只挂在它上面，
 *   宿主的自绘键盘不参与、也不会被顶起；
 * - **自己算输入法高度**：三路取较大值垫在面板下方，保证输入框与光标始终在键盘上方可见
 *   （对话框窗口在部分机型拿不到 ime inset，所以不能只靠 `imePadding()`）；
 * - 窗口显式设为「不随输入法缩放」，避免「窗口缩放 + 自己垫高」重复位移；
 * - 关闭一律走 onCancel / onConfirm，清焦点 + 收输入法 + 通知宿主，不留残留；
 * - [onActiveChange] 是唯一的「文本输入中」状态源：宿主用它决定自绘键盘的显隐（F13）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextInputSheet(
    title: String,
    initial: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
    /** 单行（金额 / 名称类）还是多行（备注类） */
    singleLine: Boolean = false,
    /** 只允许数字（金额类输入），会给系统数字键盘 */
    numberOnly: Boolean = false,
    /** 联想候选提供者（备注记忆 N22）：按当前输入实时问一次；为空表示不需要联想 */
    candidateProvider: ((String) -> List<String>)? = null,
    /** 右上角齿轮（可选）：留空则不显示 */
    onOpenSettings: (() -> Unit)? = null,
    /** 宿主窗口能读到的输入法高度，作为兜底通道（记一笔面板会传进来） */
    hostImeHeight: Dp = 0.dp,
    /** 「文本输入中」状态变化：宿主据此隐藏自绘键盘（F13） */
    onActiveChange: (Boolean) -> Unit = {}
) {
    // 草稿提在这里：面板内的输入与「点遮罩收起」共用同一份，收起时统一回填
    var draft by remember { mutableStateOf(initial) }

    // 打开期间对外宣告「文本输入中」：宿主的自绘键盘据此隐藏并保持
    DisposableEffect(Unit) {
        onActiveChange(true)
        onDispose { onActiveChange(false) }
    }

    // v2.7.0（方案 B）：从「独立 Dialog 窗口 + 自算键盘高度」改成**同窗口 ModalBottomSheet**。
    // 原来那套要串行走四步（建窗口 → 显示 → 键盘弹出 → 按测量到的高度重新定位），
    // 中间会出现「面板先出现在错误高度、再跳到位」的顿挫；现在是同一个窗口，
    // 由 Material3 的 sheet 一次性把内容抬到输入法上方，动画也是连贯的。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        // 点遮罩 / 下滑收起 = 保存并关闭（与原来「点遮罩即保存」的习惯一致）
        onDismissRequest = { onConfirm(draft) },
        sheetState = sheetState,
        containerColor = CardWhite,
        // 让面板跟着输入法走：内容底部按 ime inset 让位
        contentWindowInsets = { WindowInsets.ime }
    ) {
        TextInputPanel(
            title = title,
            draft = draft,
            onDraftChange = { draft = it },
            placeholder = placeholder,
            singleLine = singleLine,
            numberOnly = numberOnly,
            candidates = candidateProvider?.invoke(draft).orEmpty(),
            onPickCandidate = { draft = it },
            onOpenSettings = onOpenSettings,
            onCancel = onCancel,
            onConfirm = { onConfirm(draft) },
            modifier = Modifier.fillMaxWidth()
        )
        // 底部留一点呼吸空间（有输入法时 contentWindowInsets 已经让位）
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 输入法高度（F12）：三路取较大值 ——
 * 1) 宿主窗口（记一笔面板）读到的 ime 高度，由调用方传入；
 * 2) 本窗口的 `WindowInsetsCompat.Type.ime()`；
 * 3) `getWindowVisibleDisplayFrame` 的可视区差（系统未分发 inset 时反推键盘高度，小于视高 15% 视为系统栏误差）。
 * 键盘收起时全部归零，窗口位置随之复原。
 */
@Composable
private fun rememberImeHeight(hostImeHeight: Dp): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    var insetPx by remember { mutableStateOf(0) }
    var framePx by remember { mutableStateOf(0) }
    val hostPx = with(density) { hostImeHeight.roundToPx() }

    DisposableEffect(view) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            insetPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            insets
        }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val visible = Rect()
            view.getWindowVisibleDisplayFrame(visible)
            val diff = view.height - visible.bottom
            framePx = if (view.height > 0 && diff > view.height * 0.15f) diff else 0
        }
        val observer = view.viewTreeObserver
        observer.addOnGlobalLayoutListener(layoutListener)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
            if (observer.isAlive) observer.removeGlobalOnLayoutListener(layoutListener)
        }
    }
    return with(density) { maxOf(hostPx, insetPx, framePx).toDp() }
}

/**
 * 统一外观的输入面板（M4 样式）：顶栏 = 返回（不保存）+ 标题 + 齿轮；
 * 中间无边框输入区（单行 / 多行）；右下角圆形主色按钮 + 对勾（保存并收起）；下方可带联想候选。
 */
@Composable
private fun TextInputPanel(
    title: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    numberOnly: Boolean,
    candidates: List<String>,
    onPickCandidate: (String) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        // 等这一帧把焦点落定再叫输入法，个别机型直接 show 会被丢掉
        withFrameNanos { }
        keyboard?.show()
    }
    // 窗口被系统直接关掉（返回键 / 点遮罩）时，兜底收一次本窗口的输入法，避免残留
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        // v2.5.1：输入面板必须不透明（半透明会让背景文字透上来）
        color = CardWhite,
        shadowElevation = 12.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 14.dp, bottom = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Sub2,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.weight(1f))
                if (onOpenSettings != null) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable { onOpenSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = Sub2,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp, max = 132.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            if (placeholder.isNotBlank()) placeholder else "。。",
                            fontSize = 16.sp,
                            color = Muted,
                            maxLines = if (singleLine) 1 else 2
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        singleLine = singleLine,
                        textStyle = TextStyle(fontSize = 16.sp, color = Ink, lineHeight = 24.sp),
                        cursorBrush = SolidColor(Primary),
                        keyboardOptions = if (numberOnly) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else KeyboardOptions.Default,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                Spacer(Modifier.width(12.dp))
                // 右下角圆形按钮 + 对勾：完成并保存
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Primary)
                        .clickable { onConfirm() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "完成",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 联想候选：输入框下方列表，不遮挡光标；点一条直接填入（可继续编辑）
            if (candidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("最近用过", fontSize = 11.5.sp, color = Muted)
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        // 面板贴着输入法，可用高度有限：候选最多露 3 条左右，多了自己滚
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    candidates.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPickCandidate(c) }
                                .padding(horizontal = 6.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = Chevron,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                c,
                                fontSize = 14.sp,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
