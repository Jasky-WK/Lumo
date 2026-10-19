package com.qingjizhang.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.ui.theme.CardWhite
import com.qingjizhang.app.ui.theme.Expense
import com.qingjizhang.app.ui.theme.FieldBg
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.Primary
import com.qingjizhang.app.ui.theme.PrimaryBg
import com.qingjizhang.app.ui.theme.Sub
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 左滑展开项的统一托管（F20）。
 *
 * 以前每行用自己私有的 `Animatable` 记展开状态，结果是：滚动列表不收、点别处不收、
 * 还能同时展开好几行。改成由列表持有一个「当前展开的是哪一行」，
 * 滚动开始时统一收起，天然做到同一时间最多一行展开。
 */
@Stable
class SwipeOpenState {
    var id by mutableStateOf<Long?>(null)
        private set

    fun isOpen(rowId: Long): Boolean = id == rowId

    /** 某行滑开 / 收起：滑开即把展开权抢过来（其它行自动收起），收起只影响自己 */
    fun setOpen(rowId: Long, open: Boolean) {
        id = if (open) rowId else id.takeIf { it != rowId }
    }

    fun close() {
        id = null
    }
}

/**
 * 记住左滑展开状态，并在列表开始滚动时自动收起。
 * [scrollInProgress] 传宿主的列表滚动状态（LazyListState / ScrollState 的 isScrollInProgress）。
 */
@Composable
fun rememberSwipeOpenState(scrollInProgress: Boolean): SwipeOpenState {
    val state = remember { SwipeOpenState() }
    LaunchedEffect(scrollInProgress) {
        if (scrollInProgress) state.close()
    }
    return state
}

/**
 * 左滑露出「修改 / 删除」。列表默认保持干净，不做常驻删除按钮。
 * [onLongPress] 用于长按进入多选模式（N28）。
 *
 * F20：展开状态由宿主统一托管（[expanded] / [anyExpanded] / [onExpand] / [onCollapse]），
 * 这样滚动列表、点击别处、或别的行被滑开时都能自动收回，不会留下挡着列表的按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeRow(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /** 本行是否处于展开状态（由 [SwipeOpenState] 托管） */
    expanded: Boolean = false,
    /** 列表中是否有任意一行处于展开状态：用来实现「点其它行先收起、不误触详情」 */
    anyExpanded: Boolean = false,
    onExpand: () -> Unit = {},
    onCollapse: () -> Unit = {},
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val maxPx = with(LocalDensity.current) { 140.dp.toPx() }
    val offset = remember { Animatable(if (expanded) -maxPx else 0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    // 展开状态跟着宿主走：滚动、点别处、别的行滑开，都会让本行动画收回
    LaunchedEffect(expanded) {
        if (!dragging) offset.animateTo(if (expanded) -maxPx else 0f, tween(140))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF8C97AC))
    ) {
        // 背后按钮层
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(70.dp)
                    .background(Color(0xFF8C97AC))
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center
            ) {
                Text("修改", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(70.dp)
                    .background(Expense)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text("删除", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 内容层
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(CardWhite)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = false },
                        onHorizontalDrag = { _, dragAmount ->
                            dragging = true
                            scope.launch {
                                val next = (offset.value + dragAmount).coerceIn(-maxPx, 0f)
                                offset.snapTo(next)
                            }
                        },
                        onDragEnd = {
                            dragging = false
                            // 轻柔左滑（没过阈值）不展开，直接回位
                            val open = offset.value < -maxPx / 2.4f
                            when {
                                open && !expanded -> onExpand()
                                !open && expanded -> onCollapse()
                                else -> scope.launch {
                                    offset.animateTo(if (open) -maxPx else 0f, tween(140))
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            scope.launch { offset.animateTo(if (expanded) -maxPx else 0f, tween(140)) }
                        }
                    )
                }
                .combinedClickable(
                    enabled = onTap != null || onLongPress != null,
                    onClick = {
                        // 已经展开了（自己或别的行）就先收起，避免误触详情
                        if (expanded || anyExpanded) onCollapse() else onTap?.invoke()
                    },
                    onLongClick = { onLongPress?.invoke() }
                )
        ) { content() }
    }
}

/** 账单行内容（图标 + 标题/副标题 + 金额/账户） */
@Composable
fun TxnRowContent(
    title: String,
    subtitle: String,
    amountCents: Long,
    amountColor: Color,
    bottomRight: String,
    /** 统计口径标记：不计收支 / 不计预算 */
    tag: String? = null,
    badge: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badge()
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tag,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (tag == "不计预算") Primary else Sub,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (tag == "不计预算") PrimaryBg else FieldBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 11.5.sp,
                color = Sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            AmountText(amountCents, color = amountColor, fontSize = 15.5.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                bottomRight,
                fontSize = 11.sp,
                color = Sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
