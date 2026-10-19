package com.qingjizhang.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.AccountTypeEntity
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 账户顺序调整（A+A1 方案，v2.6.0 重写）。
 *
 * ## 为什么重写
 * 旧版是**扁平混排 + LazyColumn**，有两个硬伤：
 * 1. 账户页是**按类型分组**展示的，而排序页把全部账户混成一条 —— 跨类型拖动在账户页**看不出任何效果**；
 * 2. 被拖的行靠 `graphicsLayer` 位移，但它仍在 `LazyColumn` 里：LazyList 会裁剪越界绘制、
 *    `zIndex` 对懒加载的兄弟项也不生效，于是拖动过程中「整行消失」；再加上其他行有
 *    `animateItemPlacement` 动画、被拖行直接跳槽，中间帧不同步 → 视觉上「位置乱跳」。
 *
 * ## 现在的做法
 * - **按类型分组**显示，顺序与账户页一致（类型顺序取本账本的类型列表）；
 * - **只允许组内上下调整**（A1），从结构上杜绝「拖了却没效果」；
 * - 列表用普通 `Column + verticalScroll`（账户数量级只有几十个，不需要懒加载），
 *   被拖的行**画在列表之上的独立浮层**里跟着手指走 —— 不参与裁剪、也不和别的行抢绘制顺序；
 *   原来的槽位留空（alpha 0）占位，所以布局不会跳动；
 * - 只改本地顺序做实时预览，**松手才落库**；落库时按「分组显示顺序」拍平成 sortOrder。
 */
@Composable
fun AccountSortScreen(
    accounts: List<Account>,
    accountTypes: List<AccountTypeEntity>,
    /** 账户余额（只用于展示，方便认出是哪张卡/哪个钱包） */
    balanceOf: (Long) -> Long = { 0L },
    onReorder: (List<Account>) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 尺寸（统一在这里定义，所有换算都基于它）
    val rowHeight = 56.dp
    val rowGap = 6.dp
    val headerHeight = 26.dp
    val headerGap = 8.dp
    val density = LocalDensity.current
    val rowPx = with(density) { (rowHeight + rowGap).toPx() }
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val headerPx = with(density) { (headerHeight + headerGap).toPx() }
    val swapThreshold = rowPx * 0.5f
    val edgeBandPx = with(density) { 56.dp.toPx() }

    // 分组后的可视条目：标题 + 账户行（拖动时在这里换位做实时预览）
    val entries = remember { mutableStateListOf<SortEntry>() }
    // 拖动会话
    var draggedId by remember { mutableStateOf<Long?>(null) }
    val draggedIndex = remember { mutableIntStateOf(-1) }
    var beforeDrag by remember { mutableStateOf<List<SortEntry>>(emptyList()) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
    val dragOffset = remember { mutableFloatStateOf(0f) }
    /** 手指在「列表视口」里的 Y（不是内容坐标，自动滚动时保持不变） */
    val fingerViewportY = remember { mutableFloatStateOf(0f) }
    val lastDy = remember { mutableFloatStateOf(0f) }
    val viewportHeight = remember { mutableFloatStateOf(0f) }

    // 外部数据变化时重建（拖动中不打断）
    LaunchedEffect(accounts, accountTypes) {
        if (draggedId != null) return@LaunchedEffect
        val next = buildEntries(accounts, accountTypes)
        if (next.map { it.key } != entries.map { it.key }) {
            entries.clear()
            entries.addAll(next)
        }
    }

    /** 第 index 个条目在「内容坐标」里的顶部位置 */
    fun topOf(index: Int): Float {
        var y = 0f
        for (i in 0 until index) {
            y += if (entries.getOrNull(i) is SortEntry.Header) headerPx else rowPx
        }
        return y
    }

    /** 被拖行所在分组的账户行区间（组内才能换位 —— A1） */
    fun groupBounds(index: Int): IntRange? {
        val row = entries.getOrNull(index) as? SortEntry.Row ?: return null
        var start = index
        while (start > 0 && (entries[start - 1] as? SortEntry.Row)?.account?.type == row.account.type) start--
        var end = index
        while (end < entries.lastIndex && (entries[end + 1] as? SortEntry.Row)?.account?.type == row.account.type) end++
        return start..end
    }

    fun applyOffset() {
        val id = draggedId ?: return
        var idx = entries.indexOfFirst { (it as? SortEntry.Row)?.account?.id == id }
        if (idx < 0) return
        val bounds = groupBounds(idx) ?: return
        draggedIndex.intValue = idx

        var guard = entries.size + 1
        while (guard-- > 0 && dragOffset.value > swapThreshold && idx < bounds.last) {
            val tmp = entries[idx]
            entries[idx] = entries[idx + 1]
            entries[idx + 1] = tmp
            idx++
            dragOffset.value -= rowPx
        }
        guard = entries.size + 1
        while (guard-- > 0 && dragOffset.value < -swapThreshold && idx > bounds.first) {
            val tmp = entries[idx]
            entries[idx] = entries[idx - 1]
            entries[idx - 1] = tmp
            idx--
            dragOffset.value += rowPx
        }
        draggedIndex.intValue = idx
        // 位移夹在「列表可视区内」：行自己跟着手指走（不画浮层），所以只要保证
        // 它的可视位置不越出视口上下边界，就不会被滚动容器裁掉（旧版「整行消失」的根因）
        val slotTop = topOf(idx) - scrollState.value
        val minTop = 0f
        val maxTop = (viewportHeight.value - rowHeightPx).coerceAtLeast(0f)
        val visualTop = (slotTop + dragOffset.value).coerceIn(minTop, maxTop)
        dragOffset.value = visualTop - slotTop
    }

    /** 手指在视口上下边缘带内、且正在朝该方向拖 → 返回滚动方向 */
    fun edgeDirection(): Int {
        val h = viewportHeight.value
        if (h <= 0f) return 0
        val y = fingerViewportY.value
        return when {
            y < edgeBandPx && lastDy.value <= 0f -> -1
            y > h - edgeBandPx && lastDy.value >= 0f -> 1
            else -> 0
        }
    }

    fun startAutoScroll() {
        autoScrollJob?.cancel()
        val step = rowPx * 0.17f
        autoScrollJob = scope.launch {
            while (isActive && draggedId != null) {
                withFrameNanos { }
                val dir = edgeDirection()
                if (dir == 0) continue
                val consumed = scrollState.dispatchRawDelta(dir * step)
                if (consumed == 0f) break
                // 内容滚动了 consumed，被拖行要跟着内容走（继续贴在手指下）
                dragOffset.value += consumed
                applyOffset()
            }
        }
    }

    fun beginDrag(index: Int) {
        val row = entries.getOrNull(index) as? SortEntry.Row ?: return
        settleJob?.cancel()
        settleJob = null
        autoScrollJob?.cancel()
        autoScrollJob = null
        beforeDrag = entries.toList()
        draggedId = row.account.id
        draggedIndex.intValue = index
        dragOffset.value = 0f
        fingerViewportY.value = topOf(index) - scrollState.value + rowPx / 2f
        lastDy.value = 0f
    }

    fun moveDrag(dy: Float) {
        if (draggedId == null) return
        fingerViewportY.value += dy
        dragOffset.value += dy
        lastDy.value = dy
        applyOffset()
        val dir = edgeDirection()
        when {
            dir != 0 && autoScrollJob?.isActive != true -> startAutoScroll()
            dir == 0 && autoScrollJob?.isActive == true -> {
                autoScrollJob?.cancel()
                autoScrollJob = null
            }
        }
    }

    fun endDrag() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        val id = draggedId ?: return
        // 按「分组显示顺序」拍平成全局顺序落库（组内顺序即用户调整的结果）
        onReorder(entries.mapNotNull { (it as? SortEntry.Row)?.account })
        val leftover = dragOffset.value
        settleJob = scope.launch {
            animate(leftover, 0f, animationSpec = tween(110)) { v, _ -> dragOffset.value = v }
            if (draggedId == id) {
                draggedId = null
                draggedIndex.intValue = -1
                dragOffset.value = 0f
            }
        }
    }

    fun cancelDrag() {
        autoScrollJob?.cancel()
        autoScrollJob = null
        settleJob?.cancel()
        settleJob = null
        if (beforeDrag.isNotEmpty()) {
            entries.clear()
            entries.addAll(beforeDrag)
        }
        draggedId = null
        draggedIndex.intValue = -1
        dragOffset.value = 0f
    }

    // 兜底：页面销毁时若还有未落库的顺序，补存一次
    DisposableEffect(Unit) {
        onDispose {
            if (draggedId == null) {
                val current = entries.mapNotNull { (it as? SortEntry.Row)?.account }
                if (current.map { it.id } != accounts.map { it.id }) onReorder(current)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(title = "账户顺序", onBack = onBack) {
            Text(
                "完成",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }

        Text(
            "拖动右侧 ≡ 调整同一类型内的顺序，松开即保存",
            fontSize = 11.5.sp,
            color = Sub,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        if (entries.none { it is SortEntry.Row }) {
            Text(
                "当前账本还没有账户",
                fontSize = 12.5.sp,
                color = Sub,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            // 列表 + 拖拽浮层的容器：浮层画在这里，不会被列表裁剪
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewportHeight.value = it.height.toFloat() }
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp)
                ) {
                    entries.forEachIndexed { index, entry ->
                        // 必须给每个条目一个稳定 key：换位后 Compose 才会「移动节点」而不是
                        // 按位置复用 —— 否则 pointerInput 会被重建，拖动途中手势直接断掉
                        key(entry.key) {
                        when (entry) {
                            is SortEntry.Header -> GroupHeader(entry.typeName, entry.count, headerHeight, headerGap)
                            is SortEntry.Row -> {
                                val dragging = entry.account.id == draggedId
                                Box(
                                    Modifier
                                        // 普通 Column 里 zIndex 是生效的：被拖行画在其它行之上
                                        .zIndex(if (dragging) 1f else 0f)
                                        .graphicsLayer {
                                            // 被拖行自己跟着手指走；只在绘制阶段读，不触发重组
                                            if (dragging) translationY = dragOffset.value
                                        }
                                ) {
                                    AccountSortRow(
                                        account = entry.account,
                                        balanceCents = balanceOf(entry.account.id),
                                        rowHeight = rowHeight,
                                        gap = rowGap,
                                        dragging = dragging,
                                        onDragStart = { beginDrag(index) },
                                        onDrag = { dy -> moveDrag(dy) },
                                        onDragEnd = { endDrag() },
                                        onDragCancel = { cancelDrag() }
                                    )
                                }
                            }
                        }
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                }

            }
        }
    }
}

// ---------------------------------------------------------------- 数据与行

/** 排序页的一条可视条目：类型标题，或一个账户行 */
private sealed interface SortEntry {
    val key: String

    data class Header(val typeName: String, val count: Int) : SortEntry {
        override val key: String get() = "h:$typeName"
    }

    data class Row(val account: Account) : SortEntry {
        override val key: String get() = "a:${account.id}"
    }
}

/**
 * 按类型分组（顺序与账户页一致：先按本账本的类型列表，类型列表之外的追加在后面）。
 * 组内保持传入顺序（[Account.sortOrder] 已由 DAO 排好）。
 */
private fun buildEntries(
    accounts: List<Account>,
    accountTypes: List<AccountTypeEntity>
): List<SortEntry> {
    if (accounts.isEmpty()) return emptyList()
    val known = accountTypes.map { it.name }
    val types = (known + accounts.map { it.type }.filter { it !in known }).distinct()
    val out = mutableListOf<SortEntry>()
    types.forEach { type ->
        val list = accounts.filter { it.type == type }
        if (list.isEmpty()) return@forEach
        out += SortEntry.Header(type, list.size)
        list.forEach { out += SortEntry.Row(it) }
    }
    // 保险：任何没被归组的账户（理论上不会有）追加在末尾
    val grouped = out.mapNotNull { (it as? SortEntry.Row)?.account?.id }.toSet()
    accounts.filter { it.id !in grouped }.forEach { out += SortEntry.Row(it) }
    return out
}

/**
 * 分组标题：类型名 + 账户数。
 * 占用高度必须等于 headerHeight + headerGap —— 拖动位置是用 [topOf] 累加算的，
 * 这里少 1dp 浮层就会一路偏下去。
 */
@Composable
private fun GroupHeader(
    typeName: String,
    count: Int,
    height: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(height + gap)
            .padding(start = 4.dp, bottom = gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            typeName,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = Muted2,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.width(6.dp))
        Text("$count", fontSize = 11.sp, color = Muted2)
    }
}

/** 一行账户：图标 + 名称（+已隐藏）+ 右侧拖动手柄 */
@Composable
private fun AccountSortRow(
    account: Account,
    balanceCents: Long,
    rowHeight: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(rowHeight + gap)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .then(if (dragging) Modifier.shadow(10.dp, RoundedCornerShape(14.dp)) else Modifier)
                .background(if (dragging) PrimaryBg else CardWhite, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val badge = accountBadge(account.type, account.name)
            if (badge != null) {
                TextBadge(badge, accountColor(account.type), size = 32.dp, radius = 11.dp)
            } else {
                IconBadge(
                    accountIcon(account.type),
                    accountColor(account.type),
                    size = 32.dp,
                    iconSize = 17.dp,
                    radius = 11.dp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    fontSize = 14.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (account.hidden) {
                    Spacer(Modifier.height(3.dp))
                    TagChip("已隐藏")
                }
            }
            Spacer(Modifier.width(8.dp))
            // 余额：和账户页一致的口径（初始余额 + 流水净影响），用来辨认账户
            Text(
                Money.text(balanceCents),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (balanceCents == 0L) Muted2 else Ink,
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
            // 只有这块手柄接收拖动手势，行的其它区域交给列表滚动
            Box(
                Modifier
                    .size(width = 48.dp, height = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (dragging) Primary.copy(alpha = 0.14f) else Color.Transparent)
                    .pointerInput(account.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDrag = { change, delta ->
                                change.consume()
                                onDrag(delta.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragCancel() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.DragHandle,
                    contentDescription = "按住拖动排序",
                    tint = if (dragging) Primary else Sub2,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
