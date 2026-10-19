package com.qingjizhang.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 预设分类与账户：首次启动时写入，之后用户可自由增删 */
object Seed {

    /**
     * Application 与 ViewModel 都会触发初始化，用互斥锁保证只真正写入一次，
     * 否则首次启动可能出现重复的账本 / 分类 / 账户。
     */
    private val mutex = Mutex()

    /** 一级分类 -> 二级分类 */
    private val expensePreset: List<Pair<String, List<String>>> = listOf(
        "餐饮美食" to listOf("早餐", "午餐", "晚餐", "夜宵", "外卖", "零食饮料", "咖啡奶茶"),
        "交通出行" to listOf("公交地铁", "打车", "加油充电", "高铁火车", "共享单车", "停车过路"),
        "居家生活" to listOf("房租物业", "水电燃气", "日用品", "通讯网络", "理发洗护"),
        "购物消费" to listOf("服饰鞋包", "数码家电", "美妆护肤", "生活用品"),
        "休闲娱乐" to listOf("电影演出", "运动健身", "旅游", "游戏"),
        "医疗健康" to listOf("门诊药品", "体检", "保健"),
        "人情往来" to listOf("红包", "请客送礼", "孝敬长辈"),
        // 「设置账户余额」时自动生成的差额账单会归到这里
        "漏记款" to listOf("余额调整", "漏记支出"),
        "其他支出" to listOf("其他")
    )

    private val incomePreset: List<Pair<String, List<String>>> = listOf(
        "工资薪资" to listOf("月薪", "加班费", "年终奖"),
        "奖金红包" to listOf("红包", "礼金", "补贴"),
        "理财收益" to listOf("基金", "股票", "利息", "余额宝"),
        "兼职外快" to listOf("稿费", "接单", "兼职"),
        "漏记款" to listOf("余额调整", "漏记收入"),
        "其他收入" to listOf("其他")
    )

    private data class Acc(val name: String, val type: String)

    private val accountPreset = listOf(
        Acc("支付宝", AccountType.NETWORK),
        Acc("微信钱包", AccountType.NETWORK),
        Acc("现金", AccountType.CASH),
        Acc("储蓄卡", AccountType.BANK_CARD),
        Acc("信用卡", AccountType.BANK_CARD),
        Acc("储值卡", AccountType.CASH),
        Acc("借贷账户", AccountType.LEND)
    )

    /** 一个账本的预设账户类型（N19）：新建账本 / 老库补漏时按这个播种 */
    fun presetAccountTypes(ledgerId: Long): List<AccountTypeEntity> =
        AccountType.presets.mapIndexed { i, name ->
            AccountTypeEntity(ledgerId = ledgerId, name = name, sortOrder = i)
        }

    /** 一个账本的预设互转类型（N26）：互转 / 还款 / 理财买入 / 理财赎回 */
    fun presetTransferKinds(ledgerId: Long): List<TransferKindEntity> =
        TransferKind.presets.mapIndexed { i, name ->
            TransferKindEntity(ledgerId = ledgerId, name = name, sortOrder = i)
        }

    /** 预设一级分类数量，用于界面展示 */
    val presetTopLevelCount: Int get() = expensePreset.size + incomePreset.size

    /** 预设二级分类数量 */
    val presetSubCount: Int
        get() = expensePreset.sumOf { it.second.size } + incomePreset.sumOf { it.second.size }

    val presetAccountCount: Int get() = accountPreset.size

    /**
     * 首次启动：建默认账本 + 分类 + 账户。
     * 同时兜底修复：已经存在但没有任何分类的账本（例如早前新建的家庭账本）会补上预设分类。
     */
    suspend fun initIfEmpty(db: AppDatabase) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (db.ledgerDao().count() == 0) {
                val ledgerId = db.ledgerDao().insert(
                    Ledger(name = "日常账本", isDefault = true, monthlyBudgetCents = 0L)
                )
                initLedgerPresets(db, ledgerId)
            } else {
                // 补分类：任何账本只要一个分类都没有，就补一套预设
                db.ledgerDao().all().forEach { ledger ->
                    if (db.categoryDao().count(ledger.id) == 0) {
                        initLedgerPresets(db, ledger.id)
                    }
                }
                // 兜底：账户类型为空（老库迁移异常 / 从旧备份恢复）的账本也补上预设类型
                db.ledgerDao().all().forEach { ledger ->
                    if (db.accountTypeDao().count(ledger.id) == 0) {
                        db.accountTypeDao().insertAll(presetAccountTypes(ledger.id))
                    }
                    // 互转类型同理（N26）
                    if (db.transferKindDao().count(ledger.id) == 0) {
                        db.transferKindDao().insertAll(presetTransferKinds(ledger.id))
                    }
                }
            }
        }
    }

    /**
     * 给指定账本写入预设一级 / 二级分类与默认账户。
     * 新建账本时调用，保证每个账本都有完整的预设分类。
     */
    suspend fun initLedgerPresets(db: AppDatabase, ledgerId: Long) = withContext(Dispatchers.IO) {
        val categories = mutableListOf<Category>()
        var order = 0
        expensePreset.forEach { (parent, children) ->
            val tmpId = -categoryTmpId(expensePreset, incomePreset, parent, true)
            categories += Category(
                id = tmpId,
                ledgerId = ledgerId,
                name = parent,
                type = CategoryType.EXPENSE,
                isPreset = true,
                sortOrder = order++,
                parentId = 0L
            )
            children.forEachIndexed { ci, child ->
                categories += Category(
                    ledgerId = ledgerId,
                    name = child,
                    type = CategoryType.EXPENSE,
                    isPreset = true,
                    sortOrder = ci,
                    parentId = tmpId
                )
            }
        }
        order = 0
        incomePreset.forEach { (parent, children) ->
            val tmpId = -categoryTmpId(expensePreset, incomePreset, parent, false)
            categories += Category(
                id = tmpId,
                ledgerId = ledgerId,
                name = parent,
                type = CategoryType.INCOME,
                isPreset = true,
                sortOrder = order++,
                parentId = 0L
            )
            children.forEachIndexed { ci, child ->
                categories += Category(
                    ledgerId = ledgerId,
                    name = child,
                    type = CategoryType.INCOME,
                    isPreset = true,
                    sortOrder = ci,
                    parentId = tmpId
                )
            }
        }
        insertCategoriesWithParents(db, categories)

        // 账户类型（N19）：新建账本按预设播种；已有账本不动用户自己的类型设置
        if (db.accountTypeDao().count(ledgerId) == 0) {
            db.accountTypeDao().insertAll(presetAccountTypes(ledgerId))
        }

        // 互转类型（N26）：同样按预设播种，老账本不动用户自定义的胶囊
        if (db.transferKindDao().count(ledgerId) == 0) {
            db.transferKindDao().insertAll(presetTransferKinds(ledgerId))
        }

        // 账户只在账本里一个都没有时才创建，避免覆盖用户自己的设置
        if (db.accountDao().count(ledgerId) == 0) {
            db.accountDao().insertAll(
                accountPreset.mapIndexed { i, a ->
                    Account(
                        ledgerId = ledgerId,
                        name = a.name,
                        type = a.type,
                        initialBalanceCents = 0L,
                        sortOrder = i,
                        ledgerIdsCsv = ledgerId.toString()
                    )
                }
            )
        }
    }

    /** 给一级分类生成唯一的负数占位 id（插入后回填真实 id） */
    private fun categoryTmpId(
        expense: List<Pair<String, List<String>>>,
        income: List<Pair<String, List<String>>>,
        name: String,
        isExpense: Boolean
    ): Long {
        var n = 1L
        if (!isExpense) n += expense.size
        val idx = (if (isExpense) expense else income).indexOfFirst { it.first == name }
        return n + idx
    }

    /**
     * 一级分类先用负数 id 占位，插入拿到真实 id 后再把二级分类的 parentId 换回来。
     */
    private suspend fun insertCategoriesWithParents(
        db: AppDatabase,
        list: List<Category>
    ) {
        val idMap = mutableMapOf<Long, Long>()
        // 先插一级
        list.filter { it.parentId == 0L }.forEach { c ->
            val newId = db.categoryDao().insert(c.copy(id = 0L))
            idMap[c.id] = newId
        }
        // 再插二级
        list.filter { it.parentId != 0L }.forEach { c ->
            val parent = idMap[c.parentId] ?: 0L
            db.categoryDao().insert(c.copy(id = 0L, parentId = parent))
        }
    }
}
