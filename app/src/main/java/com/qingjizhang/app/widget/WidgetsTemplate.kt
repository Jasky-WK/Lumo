package com.qingjizhang.app.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Label
import androidx.compose.ui.graphics.toArgb
import com.qingjizhang.app.R
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.ui.categoryColor
import com.qingjizhang.app.ui.categoryIcon
import com.qingjizhang.app.ui.categoryIconOptions
import kotlinx.coroutines.runBlocking

/** 一个模板槽位：一级分类 id + 名称 + 图标资源 + 分类主题色（N34） */
data class TemplateSlot(
    /** 一级分类 id：点击时按它打开记一笔 */
    val categoryId: Long,
    val name: String,
    /** drawable 资源 id；0 = 该分类选择了不显示图标 */
    val iconRes: Int,
    val colorArgb: Int
)

data class TemplateSnapshot(val slots: List<TemplateSlot>)

object TemplateData {

    private const val MAX_SLOTS = 4

    /**
     * 常用四个记账模板（N34）：按**一级分类**的使用频次取前四。
     * 支出账单先归到它的一级分类（二级分类取 parentId，一级分类取自身，未分类跳过），
     * 再按一级分类分组计数、次数降序取前四（同次数按分类 id 稳定排序）；
     * 记账还不多时用「未隐藏的一级支出分类里 sortOrder 最靠前的」补齐，
     * 一个一级支出分类都没有就返回空（视图会提示去添加分类）。
     * 每次刷新都会重算，所以常用模板会随记账自动更新。
     */
    suspend fun load(context: Context): TemplateSnapshot {
        val db = AppDatabase.get(context)
        val ledger = WidgetLedger.current(context, db) ?: return TemplateSnapshot(emptyList())
        // 全部支出分类（含二级）：只用来把账单的 categoryId 归到一级
        val expenseCats = db.categoryDao().byLedger(ledger.id)
            .filter { it.type == CategoryType.EXPENSE }
        // 未隐藏的一级支出分类：频次命中与补齐都只从这里取（隐藏 / 已删除的自动跳过）
        val tops = expenseCats.filter { it.parentId == 0L && !it.hidden }
        if (tops.isEmpty()) return TemplateSnapshot(emptyList())

        val topById = tops.associateBy { it.id }
        val parentIdOf = expenseCats.associate { it.id to it.parentId }
        // 1) 支出账单按「一级分类」分组计数：二级分类归到它的一级；未分类（0）/ 找不到的跳过
        val freq = HashMap<Long, Int>()
        db.txnDao().byLedger(ledger.id)
            .filter { it.type == TxnType.EXPENSE && it.categoryId != 0L }
            .forEach { t ->
                val parentId = parentIdOf[t.categoryId] ?: return@forEach
                val topId = if (parentId == 0L) t.categoryId else parentId
                if (topById.containsKey(topId)) freq[topId] = (freq[topId] ?: 0) + 1
            }

        val picked = LinkedHashMap<Long, Category>()
        // 2) 使用频次最高的一级分类（同次数按 id 稳定排序）
        freq.entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .forEach { (id, _) -> topById[id]?.let { if (picked.size < MAX_SLOTS) picked[it.id] = it } }
        // 3) 不足四个：未隐藏的一级支出分类里 sortOrder 最靠前的补齐
        tops.sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .forEach { if (picked.size < MAX_SLOTS) picked.putIfAbsent(it.id, it) }

        return TemplateSnapshot(picked.values.map { slotOf(it) })
    }

    /** 广播接收器里同步取一次数据（已经在子线程调用） */
    fun loadBlocking(context: Context): TemplateSnapshot = runBlocking { load(context) }

    /** 槽位只用一级分类来构建：名称 / 图标 / 颜色都取一级分类本身 */
    private fun slotOf(category: Category): TemplateSlot = TemplateSlot(
        categoryId = category.id,
        name = category.name,
        iconRes = iconResOf(category),
        colorArgb = categoryColor(category).toArgb()
    )

    /**
     * 图标判定直接复用 App 内同一套逻辑（CatStyle.categoryIcon：iconKey → 名称 → 按序号自动分配），
     * 传入的是一级分类（iconKey 本身就挂在一级分类上），
     * 再把 ImageVector 换算成小组件自己的矢量图资源；"none"（不显示图标）返回 0 由视图隐藏图标位。
     */
    private fun iconResOf(category: Category): Int {
        val icon = categoryIcon(category) ?: return 0
        val key = categoryIconOptions.firstOrNull { it.second === icon }?.first
        return when {
            key != null -> ICON_RES[key] ?: R.drawable.widget_ic_category
            icon === Icons.Rounded.Label -> R.drawable.widget_ic_label
            else -> R.drawable.widget_ic_category
        }
    }

    /** iconKey -> 小组件用矢量图（与 CatStyle.categoryIconOptions 的 key 一一对应） */
    private val ICON_RES: Map<String, Int> = mapOf(
        "food" to R.drawable.widget_ic_food,
        "bus" to R.drawable.widget_ic_bus,
        "home" to R.drawable.widget_ic_home,
        "shop" to R.drawable.widget_ic_shop,
        "fun" to R.drawable.widget_ic_fun,
        "medical" to R.drawable.widget_ic_medical,
        "gift" to R.drawable.widget_ic_gift,
        "salary" to R.drawable.widget_ic_salary,
        "invest" to R.drawable.widget_ic_invest,
        "work" to R.drawable.widget_ic_work,
        "pet" to R.drawable.widget_ic_pet,
        "edu" to R.drawable.widget_ic_edu,
        "phone" to R.drawable.widget_ic_phone,
        "sport" to R.drawable.widget_ic_sport,
        "travel" to R.drawable.widget_ic_travel,
        "other" to R.drawable.widget_ic_other
    )
}

object TemplateViews {

    /** 四个槽位：整块 / 图标 / 名称 */
    private val SLOTS = listOf(
        Triple(R.id.t_slot_1, R.id.t_icon_1, R.id.t_name_1),
        Triple(R.id.t_slot_2, R.id.t_icon_2, R.id.t_name_2),
        Triple(R.id.t_slot_3, R.id.t_icon_3, R.id.t_name_3),
        Triple(R.id.t_slot_4, R.id.t_icon_4, R.id.t_name_4)
    )

    fun build(context: Context, snap: TemplateSnapshot): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_template).apply {
            setOnClickPendingIntent(R.id.t_root, WidgetIntents.openApp(context))
            // 一个分类都没有时的提示（点一下进 App 去添加）
            if (snap.slots.isEmpty()) {
                setViewVisibility(R.id.t_empty, View.VISIBLE)
                setOnClickPendingIntent(R.id.t_empty, WidgetIntents.openApp(context))
            } else {
                setViewVisibility(R.id.t_empty, View.GONE)
            }

            SLOTS.forEachIndexed { i, (slotId, iconId, nameId) ->
                val slot = snap.slots.getOrNull(i)
                if (slot == null) {
                    setViewVisibility(slotId, View.GONE)
                } else {
                    setViewVisibility(slotId, View.VISIBLE)
                    setTextViewText(nameId, slot.name)
                    setTextColor(nameId, slot.colorArgb)
                    if (slot.iconRes != 0) {
                        setViewVisibility(iconId, View.VISIBLE)
                        setImageViewResource(iconId, slot.iconRes)
                    } else {
                        setViewVisibility(iconId, View.GONE)
                    }
                    setOnClickPendingIntent(
                        slotId,
                        WidgetIntents.template(context, slot.categoryId, i)
                    )
                }
            }
        }
}

/** 模板记账小组件（N34，4×1） */
class TemplateWidgetProvider : BaseSnapshotWidget<TemplateSnapshot>() {

    override fun load(context: Context): TemplateSnapshot = TemplateData.loadBlocking(context)

    override fun build(context: Context, data: TemplateSnapshot, widgetId: Int): RemoteViews =
        TemplateViews.build(context, data)
}
