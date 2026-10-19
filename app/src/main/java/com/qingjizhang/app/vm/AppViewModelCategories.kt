package com.qingjizhang.app.vm

import android.net.Uri
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.autoIconKeyFor
import com.qingjizhang.app.ui.theme.ThemeColor
import com.qingjizhang.app.ui.theme.ThemeMode
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptStore
import com.qingjizhang.app.util.UserError
import com.qingjizhang.app.vm.AppViewModel
import com.qingjizhang.app.widget.WidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 分类管理命令（P2-1 拆出）
 */

    /**
     * 新建分类。
     * @param iconKey 一级分类图标；留空表示自动按现有数量分配一个预设图标，"none" 表示不显示图标。
     */
fun AppViewModel.addCategory(name: String, type: CategoryType, parentId: Long, iconKey: String = "") {
        scope.launch {
            runCatching {
                val lid = _currentLedgerId.value
                val existing = repo().categories(lid).first()
                val count = existing.count { it.type == type && it.parentId == parentId }
                val key = when {
                    parentId != 0L -> ""
                    iconKey.isNotEmpty() -> iconKey
                    else -> autoIconKeyFor(count)
                }
                repo().addCategory(
                    Category(
                        ledgerId = lid,
                        name = name,
                        type = type,
                        isPreset = false,
                        sortOrder = count,
                        parentId = parentId,
                        iconKey = key
                    )
                )
                _snack.emit("已添加分类：$name")
            }.onFailure { _snack.emit(UserError.text("添加失败", it)) }
        }
    }

    /** 事后更换一级分类图标 */
fun AppViewModel.setCategoryIcon(category: Category, iconKey: String) {
        scope.launch {
            runCatching {
                repo().updateCategory(category.copy(iconKey = iconKey))
                _snack.emit("已更新图标")
            }.onFailure { _snack.emit(UserError.text("更新失败", it)) }
        }
    }

fun AppViewModel.deleteCategory(category: Category) {
        scope.launch {
            runCatching {
                repo().deleteCategory(category)
                _snack.emit("已删除分类：${category.name}")
            }.onFailure { _snack.emit(UserError.text("删除失败", it)) }
        }
    }

fun AppViewModel.renameCategory(category: Category, name: String) {
        scope.launch {
            runCatching {
                repo().updateCategory(category.copy(name = name))
                _snack.emit("已重命名")
            }.onFailure { _snack.emit(UserError.text("重命名失败", it)) }
        }
    }

    // ---------------- 分类管理增强（N27：排序 / 隐藏 / 合并） ----------------

    /**
     * 分类排序：在同级里上移 / 下移一位，只重排「同 type + 同 parentId」的兄弟，
     * 所以一级与二级各自独立排序。顺序按新位置整段写回，重启后保持。
     */
fun AppViewModel.moveCategory(category: Category, up: Boolean) {
        scope.launch {
            runCatching {
                val siblings = categories.value
                    .filter { it.type == category.type && it.parentId == category.parentId }
                    .sortedWith(compareBy({ it.sortOrder }, { it.id }))
                val from = siblings.indexOfFirst { it.id == category.id }
                val to = if (up) from - 1 else from + 1
                if (from < 0 || to !in siblings.indices) return@runCatching
                val reordered = siblings.toMutableList().apply { add(to, removeAt(from)) }
                reordered.forEachIndexed { i, c ->
                    if (c.sortOrder != i) repo().setCategorySortOrder(c.id, i)
                }
            }.onFailure { _snack.emit(UserError.text("排序失败", it)) }
        }
    }

    /** 隐藏 / 取消隐藏分类：隐藏后不出现在记一笔等选择入口，分类管理页仍能看到全部 */
fun AppViewModel.toggleCategoryHidden(category: Category) {
        scope.launch {
            runCatching {
                val hide = !category.hidden
                repo().setCategoryHidden(category.id, hide)
                _snack.emit(if (hide) "已隐藏：${category.name}" else "已取消隐藏：${category.name}")
            }.onFailure { _snack.emit(UserError.text("操作失败", it)) }
        }
    }

    /** 供搜索页渲染用的分类 / 账户名 */
fun AppViewModel.categoryName(id: Long): String = categories.value.firstOrNull { it.id == id }?.name ?: ""
