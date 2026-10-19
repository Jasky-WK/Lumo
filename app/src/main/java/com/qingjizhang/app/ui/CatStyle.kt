package com.qingjizhang.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.qingjizhang.app.data.AccountType
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.ui.theme.Amber
import com.qingjizhang.app.ui.theme.BrandBlue
import com.qingjizhang.app.ui.theme.Expense
import com.qingjizhang.app.ui.theme.Income
import com.qingjizhang.app.ui.theme.Ink
import com.qingjizhang.app.ui.theme.NeutralGray
import com.qingjizhang.app.ui.theme.Orange
import com.qingjizhang.app.ui.theme.Palette
import com.qingjizhang.app.ui.theme.Pink
import com.qingjizhang.app.ui.theme.Purple
import com.qingjizhang.app.ui.theme.Sky
import com.qingjizhang.app.ui.theme.Teal
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.Money

data class CatStyle(val icon: ImageVector, val color: Color)

private val catStyles: Map<String, CatStyle> = mapOf(
    "餐饮美食" to CatStyle(Icons.Rounded.Restaurant, Expense),
    "交通出行" to CatStyle(Icons.Rounded.DirectionsBus, Sky),
    "居家生活" to CatStyle(Icons.Rounded.Home, Teal),
    "购物消费" to CatStyle(Icons.Rounded.ShoppingBag, Amber),
    "休闲娱乐" to CatStyle(Icons.Rounded.SportsEsports, Purple),
    "医疗健康" to CatStyle(Icons.Rounded.MedicalServices, Pink),
    "人情往来" to CatStyle(Icons.Rounded.CardGiftcard, Orange),
    "其他支出" to CatStyle(Icons.Rounded.MoreHoriz, NeutralGray),
    "工资薪资" to CatStyle(Icons.Rounded.Payments, Income),
    "奖金红包" to CatStyle(Icons.Rounded.Redeem, Expense),
    "理财收益" to CatStyle(Icons.Rounded.TrendingUp, BrandBlue),
    "兼职外快" to CatStyle(Icons.Rounded.WorkOutline, Purple),
    "其他收入" to CatStyle(Icons.Rounded.MoreHoriz, NeutralGray),
    "互转" to CatStyle(Icons.Rounded.SwapHoriz, BrandBlue),
    "借贷" to CatStyle(Icons.Rounded.Savings, Expense)
)

fun catStyle(name: String?): CatStyle =
    catStyles[name] ?: CatStyle(Icons.Rounded.Category, NeutralGray)

// ---------------- 一级分类预设图标（N1） ----------------

/** 可选的预设图标（key 存进数据库；"none" = 不显示图标） */
val categoryIconOptions: List<Pair<String, ImageVector>> = listOf(
    "food" to Icons.Rounded.Restaurant,
    "bus" to Icons.Rounded.DirectionsBus,
    "home" to Icons.Rounded.Home,
    "shop" to Icons.Rounded.ShoppingBag,
    "fun" to Icons.Rounded.SportsEsports,
    "medical" to Icons.Rounded.MedicalServices,
    "gift" to Icons.Rounded.CardGiftcard,
    "salary" to Icons.Rounded.Payments,
    "invest" to Icons.Rounded.TrendingUp,
    "work" to Icons.Rounded.WorkOutline,
    "pet" to Icons.Rounded.Pets,
    "edu" to Icons.Rounded.School,
    "phone" to Icons.Rounded.Smartphone,
    "sport" to Icons.Rounded.FitnessCenter,
    "travel" to Icons.Rounded.Flight,
    "other" to Icons.Rounded.MoreHoriz,
    "none" to Icons.Rounded.Remove
)

private val iconByKey: Map<String, ImageVector> = categoryIconOptions.toMap()

/** 自动分配时用到的顺序（不含「不显示图标」） */
val autoIconKeys: List<String> = categoryIconOptions.map { it.first }.filter { it != "none" }

/** 新建一级分类且没指定图标时，按现有数量自动挑一个预设图标 */
fun autoIconKeyFor(count: Int): String =
    autoIconKeys[Math.floorMod(count, autoIconKeys.size)]

/**
 * 分类图标：优先用户指定的 key → 其次按名称匹配 → 最后按 sortOrder 分配预设图标。
 * 返回 null 表示「不显示图标」。
 */
fun categoryIcon(category: Category?, fallbackName: String? = null): ImageVector? {
    if (category == null) return catStyles[fallbackName]?.icon ?: Icons.Rounded.Category
    if (category.iconKey == "none") return null
    iconByKey[category.iconKey]?.let { return it }
    catStyles[category.name]?.let { return it.icon }
    if (category.parentId == 0L) {
        return iconByKey[autoIconKeys[Math.floorMod(category.sortOrder, autoIconKeys.size)]]
    }
    return Icons.Rounded.Label
}

/** 分类颜色：优先按名称匹配，其次按 sortOrder 从调色板取 */
fun categoryColor(category: Category?, fallbackName: String? = null): Color {
    if (category == null) return catStyles[fallbackName]?.color ?: NeutralGray
    catStyles[category.name]?.let { return it.color }
    return Palette[Math.floorMod(category.sortOrder, Palette.size)]
}

/**
 * 账单金额文字颜色（M10）：
 * 「不计入收支」（[Txn.excludeFromStats]）的账单金额统一用中性灰 [NeutralGray]，
 * 避免它跟真正计入统计的红 / 绿混淆（灰是工程里「次要 / 不参与统计」的既有语义灰，
 * 与卡片底对比清晰又不抢眼，且深浅色下取值接近，无需再分主题）；
 * 其余账单保持原有语义色：支出红、收入绿、互转中性、借贷按借出 / 借入取红绿。
 * 注意：本函数只用于「金额文字」，分类图标底色 / 账户徽标颜色不要用它。
 */
@Composable
fun amountColor(t: Txn): Color {
    if (t.excludeFromStats) return NeutralGray
    return when (t.type) {
        TxnType.EXPENSE -> Expense
        TxnType.INCOME -> Income
        TxnType.TRANSFER -> Ink
        TxnType.LEND_BORROW -> if (t.direction == Direction.LEND) Expense else Income
    }
}

fun typeLabel(type: TxnType): String = when (type) {
    TxnType.EXPENSE -> "支出"
    TxnType.INCOME -> "收入"
    TxnType.TRANSFER -> "互转"
    TxnType.LEND_BORROW -> "借贷"
}

/** 借贷账单标题：原始借贷是「借出 / 借入 · 对方」，还款流水是「收回 / 还出 · 对方」 */
fun lendTitle(t: Txn): String {
    if (t.type != TxnType.LEND_BORROW) return typeLabel(t.type)
    val base = when {
        Repository.isSettlement(t) -> if (t.direction == Direction.LEND) "还出" else "收回"
        t.direction == Direction.LEND -> "借出"
        else -> "借入"
    }
    return base + (if (t.counterparty.isNotBlank()) " · ${t.counterparty}" else "")
}

/**
 * 某笔借贷的结清状态文案，用于明细列表行。
 * [outstanding] 由 `Repository.outstandingMap()` 一次性算好，避免列表里逐行全表过滤。
 */
fun lendStatusText(outstanding: Long, t: Txn): String {
    if (t.type != TxnType.LEND_BORROW) return ""
    if (Repository.isSettlement(t)) return "还款流水"
    val receivable = t.direction == Direction.LEND
    return when {
        outstanding <= 0L -> "已结清"
        outstanding >= t.amountCents -> if (receivable) "待收回" else "待归还"
        else -> (if (receivable) "待收回 " else "待归还 ") + "¥" + Money.text(outstanding)
    }
}

/** 账户类型颜色：预设类型固定色；自定义类型按名称哈希取调色板，保证同一类型颜色稳定 */
fun accountColor(type: String): Color = when (type) {
    AccountType.NETWORK -> BrandBlue
    AccountType.BANK_CARD -> Purple
    AccountType.CASH -> Teal
    AccountType.LEND -> Expense
    AccountType.OTHER -> NeutralGray
    else -> Palette[Math.floorMod(type.hashCode(), Palette.size)]
}

/**
 * 支付宝 / 微信 用文字徽标更贴近真实观感。
 * 类型名现在由用户自定义（如「网络账户」），所以优先按账户名判断；
 * 调用方只拿得到类型名时（记一笔的账户选择器）退化成图标徽标。
 */
fun accountBadge(type: String, name: String? = null): String? {
    val key = name?.takeIf { it.isNotBlank() } ?: type
    return when {
        key.startsWith("支付宝") -> "支"
        key.startsWith("微信") -> "微"
        else -> null
    }
}

/** 账户图标：预设类型给固定图标，自定义类型用通用钱包图标 */
fun accountIcon(type: String): ImageVector = when (type) {
    AccountType.NETWORK -> Icons.Rounded.Smartphone
    AccountType.CASH -> Icons.Rounded.Payments
    AccountType.BANK_CARD -> Icons.Rounded.CreditCard
    AccountType.LEND -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.AccountBalanceWallet
}
