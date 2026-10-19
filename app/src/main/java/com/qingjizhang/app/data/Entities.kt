package com.qingjizhang.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 账目类型 */
enum class TxnType { EXPENSE, INCOME, TRANSFER, LEND_BORROW }

/** 借贷方向：借出 / 借入 */
enum class Direction { NONE, LEND, BORROW }

/**
 * 账户类型（N19：用户自定义分组）。
 * 类型名按账本独立保存在 `account_types` 表里，账户的 type 直接存类型名称字符串，
 * 所以这里只保留「预设类型名称」常量：新建账本、老库升级时按它播种。
 */
object AccountType {
    const val NETWORK = "网络账户"
    const val BANK_CARD = "银行卡"
    const val CASH = "现金与储值"
    const val LEND = "借贷"
    const val OTHER = "其他"

    /** 每个账本初始化时的预设类型（顺序即分组展示顺序） */
    val presets: List<String> = listOf(NETWORK, BANK_CARD, CASH, LEND, OTHER)
}

/** 分类类型：支出 / 收入 */
enum class CategoryType { EXPENSE, INCOME }

/**
 * 互转子类型（N26）。
 * 与账户类型一样按账本独立存在 `transfer_kinds` 表里，账单只存名称字符串，
 * 所以这里只保留「预设名称」常量：新建账本、老库升级时按它播种。
 */
object TransferKind {
    const val COMMON = "互转"
    const val REPAY = "还款"
    const val INVEST_BUY = "理财买入"
    const val INVEST_SELL = "理财赎回"

    /** 每个账本初始化时的预设互转类型（顺序即胶囊展示顺序） */
    val presets: List<String> = listOf(COMMON, REPAY, INVEST_BUY, INVEST_SELL)
}

/** 自定义预算的统计口径（N30） */
object BudgetDirection {
    /** 支出口径（默认） */
    const val EXPENSE = 0

    /** 收入口径 */
    const val INCOME = 1
}

/**
 * 自定义预算（N30）：自定义事项 + 自定义时间段。
 *
 * - 时间段含首尾两天，即 [startMillis 当天 0 点, endMillis 当天 24 点)；
 * - [categoryId] / [accountId] = 0 表示不限制；[keyword] 为空表示不限制（匹配备注或对方）；
 * - [enabled] = false 表示暂停：定义与历史统计都保留，只是不再提示进度；
 * - 删除预算定义不影响任何历史账单与收支统计。
 */
@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    /** 事项名，如「旅游基金」「学习支出」 */
    val name: String,
    /** 起始日期当天 0 点 */
    val startMillis: Long,
    /** 结束日期当天 0 点（含当天） */
    val endMillis: Long,
    /** 预算上限，单位：分 */
    val amountCents: Long,
    /** 0 = 支出，1 = 收入 */
    val direction: Int = BudgetDirection.EXPENSE,
    /** 绑定的分类 id；0 = 不限 */
    val categoryId: Long = 0L,
    /** 绑定的账户 id（账单主账户）；0 = 不限 */
    val accountId: Long = 0L,
    /** 关键词（匹配备注或对方，忽略大小写）；空 = 不限 */
    val keyword: String = "",
    /** 启用状态；false = 已暂停 */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** 周期频率 */
enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** 周期结束方式 */
enum class EndMode { NEVER, UNTIL, COUNT }

@Entity(tableName = "ledger")
data class Ledger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val isDefault: Boolean = false,
    /** 月度预算，单位：分。0 表示未设置 */
    val monthlyBudgetCents: Long = 0L,
    /**
     * 删除时间。0 = 正常；>0 = 已进回收站，30 天后彻底删除。
     * N59：删除账本改为软删除——账本下的账单 / 账户 / 分类等数据原样保留，
     * 还原账本时一切照旧；彻底删除（回收站里操作）才级联清空。
     */
    val deletedAt: Long = 0L
)

@Entity(
    tableName = "account",
    indices = [Index(value = ["ledgerId", "deletedAt"])]
)
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 创建时所属账本 */
    val ledgerId: Long,
    val name: String,
    /** 账户类型名称：取值来自本账本的 account_types；N19 起用户可自定义（老数据存的是枚举名，迁移时已转成类型名） */
    val type: String = AccountType.OTHER,
    /** 初始余额，单位：分。只在内部参与余额计算，界面上不再展示 */
    val initialBalanceCents: Long = 0L,
    val sortOrder: Int = 0,
    /** 适用账本 id（逗号分隔）。空表示只用 ledgerId；多个账本可共用同一个账户 */
    val ledgerIdsCsv: String = "",
    /** 是否计入净资产与总资产 */
    val countInNetWorth: Boolean = true,
    /** 隐藏账户：账户页默认不显示，长按总览卡可临时显示 */
    val hidden: Boolean = false,
    /** 账户备注，以浅灰小字显示在账户名后面 */
    val note: String = "",
    /**
     * N52 信用卡每月还款日：1~31，0 = 未设置。
     * 只对「信用卡」类型的账户有意义；遇到当月没有该日（如 31 号 / 2 月）取当月最后一天。
     */
    val creditDueDay: Int = 0,
    /** N52 信用卡还款日前一天提醒开关，默认关闭 */
    val creditRemind: Boolean = false,
    /** 删除时间。0 = 正常；>0 = 已进回收站，30 天后彻底删除 */
    val deletedAt: Long = 0L
) {
    /** N52：是不是信用卡账户（按账户类型名判断，N19 起类型名可自定义，所以用包含匹配） */
    val isCreditCard: Boolean
        get() = type.contains("信用")
    /** 这个账户适用的账本 id 列表 */
    val ledgerIds: List<Long>
        get() = (if (ledgerIdsCsv.isBlank()) ledgerId.toString() else ledgerIdsCsv)
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }

    fun appliesTo(ledgerId: Long): Boolean = ledgerIds.contains(ledgerId)
}

/**
 * 账户类型（N19）：每个账本一份自定义分组列表。
 * 用独立表而不是枚举，是为了让用户能新建 / 改名 / 删除类型，并且按账本各自独立。
 */
@Entity(tableName = "account_types")
data class AccountTypeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val sortOrder: Int = 0
)

/**
 * 互转类型（N26）：每个账本一份自定义互转胶囊列表。
 * 与账户类型同构，用独立表让用户能新建 / 改名 / 删除，并且按账本各自独立。
 */
@Entity(tableName = "transfer_kinds")
data class TransferKindEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val sortOrder: Int = 0
)

/**
 * 标签（N38）：每个账本一份自定义标签列表。
 * 与账户类型 / 互转类型同构，按账本隔离；账单与标签是多对多关系，关联表见 [TxnTagEntity]。
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 账单-标签关联（N38）：一笔账单可以带多个标签，一个标签也可以用在多笔账单上。
 * 复合主键 (txnId, tagId) 天然去重；按本工程惯例不建外键，
 * 删除标签 / 账单时的关联行清理交给 Repository（见 Repository.deleteTag / purgeTxn）。
 */
@Entity(tableName = "txn_tags", primaryKeys = ["txnId", "tagId"])
data class TxnTagEntity(
    val txnId: Long,
    val tagId: Long
)

/** 列表展示用的投影：某个账单命中的标签名（N38，配合 TagDao.observeTagNamesByLedger 使用） */
data class TxnTagName(
    val txnId: Long,
    val name: String
)

/**
 * 当月收入 / 支出合计（N60：SQL 聚合投影，配合 TxnDao.observeMonthIncomeExpense）。
 * 口径与 Repository.countsInStats / effectiveCents 一致，见 DAO 上的注释。
 */
data class MonthSum(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L
)

@Entity(tableName = "category")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val type: CategoryType,
    val isPreset: Boolean = false,
    val sortOrder: Int = 0,
    /** 0 = 一级分类，否则为所属一级分类的 id */
    val parentId: Long = 0L,
    /** 一级分类图标 key（空 = 自动：按名称匹配，匹配不到就按顺序分配预设图标；"none" = 不显示图标） */
    val iconKey: String = "",
    /** 隐藏的分类（N27）：记一笔与统计里不出现，历史账单保留，可随时取消隐藏 */
    val hidden: Boolean = false
)

@Entity(
    tableName = "txn",
    indices = [
        Index(value = ["ledgerId", "dateMillis"]),
        Index(value = ["ledgerId", "deletedAt"]),
        Index(value = ["ruleId"])
    ]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val accountId: Long,
    /** 互转时的转入账户 */
    val transferToAccountId: Long = 0L,
    val categoryId: Long = 0L,
    val type: TxnType,
    val direction: Direction = Direction.NONE,
    /** 金额，单位：分，恒为正数，方向由 type/direction 表达 */
    val amountCents: Long,
    val counterparty: String = "",
    val note: String = "",
    val dateMillis: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** 由哪条周期规则生成；0 表示手动记账 */
    val ruleId: Long = 0L,
    /** 不计入收支：不影响收入/支出统计，但仍然改变账户余额 */
    val excludeFromStats: Boolean = false,
    /** 不计入预算：当月预算的「已用 / 剩余 / 超支提醒」不算它 */
    val excludeFromBudget: Boolean = false,
    /**
     * 报销 / 退款金额（分）。实计金额 = amountCents - refundCents，
     * 账户余额变动与收支统计都按实计金额算，原价与退款额都保留可查。
     */
    val refundCents: Long = 0L,
    /**
     * 手续费（N40），单位：分。如转账 / 交易手续费，实际支出要加上它。
     */
    val feeCents: Long = 0L,
    /**
     * 优惠（N40），单位：分。如满减 / 折扣，实际支出要减去它。
     * 实计金额 = amountCents - refundCents - discountCents + feeCents（见 Repository.effectiveCents）。
     */
    val discountCents: Long = 0L,
    /**
     * 借贷还款流水（N7）：这笔借贷是「收回 / 还出」时，指向被偿还的那笔借贷账单 id。
     * 0 = 普通账单（含原始借贷）。
     */
    val settledTxnId: Long = 0L,
    /** 删除时间。0 = 正常；>0 = 已进回收站，30 天后彻底删除 */
    val deletedAt: Long = 0L,
    /** 互转子类型（N26）：互转 / 还款 / 理财买入 / 理财赎回，或用户自定义的名称；非互转类型恒为 [TransferKind.COMMON] */
    val transferKind: String = TransferKind.COMMON
)

@Entity(tableName = "recurring_rule")
data class RecurringRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ledgerId: Long,
    val name: String,
    val type: TxnType = TxnType.EXPENSE,
    val amountCents: Long,
    val accountId: Long,
    val transferToAccountId: Long = 0L,
    val categoryId: Long = 0L,
    val counterparty: String = "",
    val note: String = "",
    val freq: Freq = Freq.MONTHLY,
    /** 每 N 个周期执行一次 */
    val interval: Int = 1,
    val startMillis: Long,
    val endMode: EndMode = EndMode.NEVER,
    val endMillis: Long = 0L,
    val endCount: Int = 0,
    val enabled: Boolean = true,
    /** 提前几天提醒，0 表示不提醒 */
    val notifyAheadDays: Int = 1,
    /** 已经生成到的时间点（不含），下次从这之后开始补齐 */
    val lastGeneratedMillis: Long = 0L,
    /** 已生成的期数 */
    val generatedCount: Int = 0
)

/** 应收应付方向（N44）：应收款 / 应付款 */
enum class ArApDirection { RECEIVABLE, PAYABLE }

/** 应收应付状态（N44）：待收 / 待付 → 已收 / 已付 */
enum class ArApStatus { PENDING, SETTLED }

/**
 * 应收应付（N44）。
 *
 * 记录一笔「还没真正发生」的钱：应收款（别人欠我）与应付款（我欠别人）。
 * - **不计入余额、不进主页账单流**：余额只统计 [Txn]，本表的记录天然不参与；
 * - 字段与「记一笔」对齐：记录时间 [dateMillis]、金额、分类、对方、备注；
 * - 结清（收款 / 付款）时在同一事务里生成一笔真实账单，并把这条记录**删除**（N45 修正 3），
 *   所以列表里只会剩下未结清（或分批结算后仍有剩余）的记录，[status] 恒为 PENDING；
 * - 支持分批收款 / 付款：只结清一部分时保留本行，用 [settledCents] 累计已收 / 已付金额，
 *   [settledAt] / [settledTxnId] 记录最近一次结算的时间与生成的账单，便于追溯。
 */
@Entity(tableName = "receivables")
data class ArAp(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 所属账本；从记一笔 / 应收应付页保存时由 ViewModel 统一填入当前账本 id */
    val ledgerId: Long = 0L,
    val direction: ArApDirection,
    val status: ArApStatus = ArApStatus.PENDING,
    /** 金额，单位：分，恒为正 */
    val amountCents: Long,
    /** 记一笔里选的分类；0 = 未分类 */
    val categoryId: Long = 0L,
    /** 记一笔里选的账户；0 = 未指定（结算前不影响任何账户余额） */
    val accountId: Long = 0L,
    val counterparty: String = "",
    val note: String = "",
    /** 记录时间 */
    val dateMillis: Long,
    /** 最近一次结清（收款 / 付款）时间；0 = 还没结算过 */
    val settledAt: Long = 0L,
    /** 最近一次结算生成的真实账单 id；0 = 还没结算过 */
    val settledTxnId: Long = 0L,
    /** 已收 / 已付的金额（分）：分批收款 / 付款时小于 [amountCents]，剩余部分仍待收 / 待付 */
    val settledCents: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 账户 ↔ 账本关系（P2-2）。
 *
 * 替代 account.ledgerIdsCsv 逗号字符串的查询路径：CSV 列**保留不动**
 * （备份文件兼容 + 回填数据源），新查询走这张表；由 [AccountLedgerSync]
 * 把 CSV 幂等同步进来（启动时 / 账户写入后 / 恢复备份后）。
 * 按本工程惯例不建外键，清理交给 Repository。
 */
@Entity(tableName = "account_ledgers", primaryKeys = ["accountId", "ledgerId"])
data class AccountLedger(
    val accountId: Long,
    val ledgerId: Long
)
