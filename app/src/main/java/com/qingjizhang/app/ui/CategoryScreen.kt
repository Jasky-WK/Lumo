package com.qingjizhang.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.ui.components.*
import com.qingjizhang.app.ui.theme.*
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.vm.addCategory
import com.qingjizhang.app.vm.deleteCategory
import com.qingjizhang.app.vm.moveCategory
import com.qingjizhang.app.vm.renameCategory
import com.qingjizhang.app.vm.setCategoryIcon
import com.qingjizhang.app.vm.toggleCategoryHidden

/**
 * 分类管理（N1 + N27）：
 * - 一级 / 二级都可以上移、下移（各自独立排序，持久化到 sortOrder）
 * - 可以隐藏 / 取消隐藏（隐藏项在选择入口不出现，这里仍全量展示并打「已隐藏」标签）
 * - 可以把某个分类合并到同类型的另一个分类（账单整体归入目标分类，原分类删除）
 */
@Composable
fun CategoryScreen(
    vm: AppViewModel,
    onBack: () -> Unit
) {
    // 管理页需要看到全量（含隐藏），所以直接用 vm.categories，不做 hidden 过滤
    val categories by vm.categories.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val type = if (tab == 0) CategoryType.EXPENSE else CategoryType.INCOME

    var addParentFor by remember { mutableStateOf<Category?>(null) }
    var addTopLevel by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Category?>(null) }
    var delete by remember { mutableStateOf<Category?>(null) }
    var mergeFrom by remember { mutableStateOf<Category?>(null) }
    var mergeTo by remember { mutableStateOf<Category?>(null) }
    var openMenu by remember { mutableStateOf(0L) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    if (addTopLevel) {
        CategoryDialog(
            title = "添加一级分类",
            initialName = "",
            initialIconKey = "",
            withIcon = true,
            onDismiss = { addTopLevel = false },
            onConfirm = { n, key -> vm.addCategory(n, type, 0L, key) }
        )
    }
    addParentFor?.let { p ->
        InputDialog(
            title = "在「${p.name}」下添加二级分类",
            initial = "",
            label = "二级分类名称",
            onConfirm = { if (it.isNotBlank()) vm.addCategory(it, type, p.id) },
            onDismiss = { addParentFor = null }
        )
    }
    rename?.let { c ->
        CategoryDialog(
            title = if (c.parentId == 0L) "修改一级分类" else "重命名分类",
            initialName = c.name,
            initialIconKey = c.iconKey,
            withIcon = c.parentId == 0L,
            onDismiss = { rename = null },
            onConfirm = { n, key ->
                if (n != c.name) vm.renameCategory(c, n)
                if (key != c.iconKey) vm.setCategoryIcon(c, key)
            }
        )
    }
    delete?.let { c ->
        ConfirmDialog(
            title = "删除分类「${c.name}」？",
            text = if (c.parentId == 0L) "它下面的二级分类会一起删除，已记账的账单会变成「未分类」，金额不受影响。"
            else "已使用该分类的账单会变成「未分类」，金额不受影响。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteCategory(c) },
            onDismiss = { delete = null }
        )
    }

    // 合并第一步：选一个同类型的目标分类
    val pickingFrom = mergeFrom
    val pickedTo = mergeTo
    if (pickingFrom != null && pickedTo == null) {
        CategoryPickDialog(
            title = "把「${pickingFrom.name}」合并到",
            subtitle = if (pickingFrom.parentId == 0L) "只能合并到一级分类；原有的二级分类会整体挂到目标分类下"
            else "可合并到一级或二级分类",
            options = mergeTargetsOf(pickingFrom, categories),
            onPick = { mergeTo = it },
            onDismiss = { mergeFrom = null }
        )
    }
    // 合并第二步：明确告知影响后再执行，禁止静默操作
    if (pickingFrom != null && pickedTo != null) {
        val n = vm.categoryUsage(pickingFrom.id)
        val kids = vm.categoryChildCount(pickingFrom.id)
        ConfirmDialog(
            title = "合并分类「${pickingFrom.name}」？",
            text = if (pickingFrom.parentId == 0L)
                "「${pickingFrom.name}」下有 $n 笔账单，将全部归入「${pickedTo.name}」；它下面的 $kids 个二级分类会整体挂到「${pickedTo.name}」下，之后「${pickingFrom.name}」会被删除。"
            else
                "「${pickingFrom.name}」下有 $n 笔账单，将全部归入「${pickedTo.name}」，「${pickingFrom.name}」会被删除。",
            confirmText = "合并",
            danger = true,
            onConfirm = {
                vm.mergeCategory(pickingFrom, pickedTo)
                mergeFrom = null
            },
            onDismiss = { mergeTo = null }
        )
    }

    val level1 = categories.filter { it.type == type && it.parentId == 0L }
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        BackTopBar(
            title = "分类管理",
            onBack = onBack,
            actions = { IconAction(Icons.Rounded.Add, { addTopLevel = true }) }
        )
        Column(Modifier.padding(horizontal = 14.dp)) {
            SegTabs(listOf("支出分类", "收入分类"), tab) { tab = it }
            Spacer(Modifier.height(12.dp))
            Text(
                "预设分类可以直接删除，也可以自己加；每个分类都能上移 / 下移、隐藏或合并到同类分类。",
                fontSize = 11.5.sp,
                color = Sub,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(12.dp))

            CardBox {
                Column {
                    level1.forEachIndexed { index, parent ->
                        val kids = categories.filter { it.parentId == parent.id }
                            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                        val icon = categoryIcon(parent) ?: Icons.Rounded.Circle
                        val color = categoryColor(parent)
                        val isOpen = expanded[parent.id] != false
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded[parent.id] = !isOpen }
                                    .padding(start = 15.dp, end = 2.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconBadge(icon, color, size = 34.dp, iconSize = 18.dp, radius = 11.dp)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            parent.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (parent.hidden) Sub2 else Ink
                                        )
                                        if (parent.hidden) HiddenTag()
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        if (kids.isEmpty()) "暂无二级分类" else "${kids.size} 个二级分类",
                                        fontSize = 11.sp,
                                        color = Sub
                                    )
                                }
                                IconAction(Icons.Rounded.Add, { addParentFor = parent }, tint = Sub, size = 19.dp)
                                MoveArrow(Icons.Rounded.KeyboardArrowUp, index > 0) {
                                    vm.moveCategory(parent, true)
                                }
                                MoveArrow(Icons.Rounded.KeyboardArrowDown, index < level1.lastIndex) {
                                    vm.moveCategory(parent, false)
                                }
                                CategoryMenuAnchor(
                                    expanded = openMenu == parent.id,
                                    hidden = parent.hidden,
                                    onOpen = { openMenu = parent.id },
                                    onDismiss = { openMenu = 0L },
                                    canUp = index > 0,
                                    canDown = index < level1.lastIndex,
                                    onEdit = { rename = parent },
                                    onUp = { vm.moveCategory(parent, true) },
                                    onDown = { vm.moveCategory(parent, false) },
                                    onToggleHidden = { vm.toggleCategoryHidden(parent) },
                                    onMerge = { mergeFrom = parent },
                                    onDelete = { delete = parent }
                                )
                            }
                            if (isOpen) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 60.dp, end = 6.dp, bottom = 8.dp)
                                ) {
                                    kids.forEachIndexed { kIndex, k ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(Modifier.weight(1f)) {
                                                Text(
                                                    k.name,
                                                    fontSize = 13.sp,
                                                    color = if (k.hidden) Muted else Sub2,
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(9.dp))
                                                        .clickable {
                                                            openMenu = if (openMenu == k.id) 0L else k.id
                                                        }
                                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                                )
                                            }
                                            if (k.hidden) HiddenTag()
                                            MoveArrow(Icons.Rounded.KeyboardArrowUp, kIndex > 0) {
                                                vm.moveCategory(k, true)
                                            }
                                            MoveArrow(Icons.Rounded.KeyboardArrowDown, kIndex < kids.lastIndex) {
                                                vm.moveCategory(k, false)
                                            }
                                            CategoryMenuAnchor(
                                                expanded = openMenu == k.id,
                                                hidden = k.hidden,
                                                onOpen = { openMenu = k.id },
                                                onDismiss = { openMenu = 0L },
                                                canUp = kIndex > 0,
                                                canDown = kIndex < kids.lastIndex,
                                                onEdit = { rename = k },
                                                onUp = { vm.moveCategory(k, true) },
                                                onDown = { vm.moveCategory(k, false) },
                                                onToggleHidden = { vm.toggleCategoryHidden(k) },
                                                onMerge = { mergeFrom = k },
                                                onDelete = { delete = k }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(PrimaryBg)
                                            .clickable { addParentFor = parent }
                                            .padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("添加", fontSize = 12.sp, color = Primary, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (index != level1.lastIndex) RowDivider()
                        }
                    }
                    if (level1.isEmpty()) {
                        EmptyState(Icons.Rounded.Category, "还没有分类", "点右上角 ＋ 添加")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            CardBox {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { addTopLevel = true }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加一级分类", fontSize = 13.5.sp, color = Primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

/** 合并候选：同类型、不是自己；一级分类只能并入一级分类（这样它的二级分类才有地方挂） */
private fun mergeTargetsOf(from: Category, all: List<Category>): List<Category> {
    val sameType = all.filter { it.type == from.type && it.id != from.id }
    return if (from.parentId == 0L) sameType.filter { it.parentId == 0L } else sameType
}

/** 「上移 / 下移」按钮：不可用时用等宽占位，保证每行操作区对齐 */
@Composable
private fun MoveArrow(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    if (enabled) {
        IconAction(icon, onClick, tint = Sub, size = 19.dp)
    } else {
        Box(Modifier.size(38.dp))
    }
}

/** 「已隐藏」小标签 */
@Composable
private fun HiddenTag() {
    Spacer(Modifier.width(5.dp))
    Text(
        "已隐藏",
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FieldBg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** 每个分类行的操作入口：编辑 / 隐藏 / 上移 / 下移 / 合并 / 删除 */
@Composable
private fun CategoryMenuAnchor(
    expanded: Boolean,
    hidden: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onToggleHidden: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        IconAction(Icons.Rounded.MoreVert, onOpen, tint = Sub, size = 19.dp)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss
        ) {
            MenuItem("编辑") { onDismiss(); onEdit() }
            MenuItem("上移", enabled = canUp) { onDismiss(); onUp() }
            MenuItem("下移", enabled = canDown) { onDismiss(); onDown() }
            MenuItem(if (hidden) "取消隐藏" else "隐藏") { onDismiss(); onToggleHidden() }
            MenuItem("合并到…", enabled = true) { onDismiss(); onMerge() }
            MenuItem("删除", danger = true) { onDismiss(); onDelete() }
        }
    }
}

@Composable
private fun MenuItem(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                fontSize = 13.5.sp,
                color = when {
                    danger -> Expense
                    !enabled -> Muted
                    else -> Ink
                }
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}

/** 合并目标选择弹窗 */
@Composable
private fun CategoryPickDialog(
    title: String,
    subtitle: String,
    options: List<Category>,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Sub) } },
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(subtitle, fontSize = 11.5.sp, color = Sub, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                if (options.isEmpty()) {
                    Text("没有可合并到的分类", fontSize = 13.sp, color = Sub2)
                } else {
                    options.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(c) }
                                .padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (c.parentId == 0L) c.name else "　└ " + c.name,
                                fontSize = 14.sp,
                                color = Ink,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Rounded.KeyboardArrowRight,
                                null,
                                tint = Chevron,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    )
}
