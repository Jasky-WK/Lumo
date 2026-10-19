package com.qingjizhang.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qingjizhang.app.data.Account
import com.qingjizhang.app.data.AccountType
import com.qingjizhang.app.data.AppDatabase
import com.qingjizhang.app.data.Budget
import com.qingjizhang.app.data.BudgetDirection
import com.qingjizhang.app.data.Category
import com.qingjizhang.app.data.CategoryType
import com.qingjizhang.app.data.Direction
import com.qingjizhang.app.data.Ledger
import com.qingjizhang.app.data.Repository
import com.qingjizhang.app.data.Txn
import com.qingjizhang.app.data.TxnType
import com.qingjizhang.app.util.DateFmt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * T3：跨页数字一致性对拍测试（v2.9.0 安全网，P0-2）。
 *
 * 【为什么有这份测试】
 * v2.8.3 出过一类 bug：总览 / 首页显示有数，下钻明细却是空白——各页面各算一遍
 * 聚合，口径悄悄漂移。这份测试用**一份固定数据集 + 两套独立实现**算同一批数字，
 * 对不上就挂，守住这类 bug 不再发生。
 *
 * 【两套实现】
 * - A 套（现有实现）：内存 Room + Repository（effectiveCents / countsInStats /
 *   countsInBudget / inStatsAccountScope / accountDeltas / lendTotals /
 *   budgetUsedCents）+ DAO 查询（observeMonthIncomeExpense / observeBudgetUsedCents
 *   / byLedger），逐页复刻聚合口径（首页 SQL 下推、总览 buildStats、报表 buildReport、
 *   日历 calDays、明细列表、账户余额）。
 * - B 套（参考实现）：测试内纯 Kotlin 集合操作**手写**一遍口径语义（见文末 [Ref]），
 *   严禁调用 Repository 的任何函数（否则是自证）。日期边界也自己用 java.time 算，
 *   不借 DateFmt，两套实现完全独立。
 *
 * 【比对面】（全部 A vs B 断言一致）
 * 1. 本月收入 / 本月支出 / 本月结余（首页 SQL + 总览 Kotlin 两个来源互证）
 * 2. 各一级分类汇总金额（支出 / 收入，含「未分类」桶；之和必须等于本月收支）
 * 3. 逐月桶：报表按年 12 桶（2026-01~12）+ 近 12 个月桶（2025-04~2026-03，覆盖跨年）
 * 4. 预算已用：月度预算（SQL + Kotlin）+ 自定义预算（绑分类 / 带关键词各一）
 * 5. 日历当日合计（今天 2026-03-15 的收入 / 支出 / 其他笔数）
 * 6. 明细列表条数与金额合计（deletedAt=0 口径）
 * 7. 各账户余额（初始余额 + 流水净影响）
 * 8. 借贷汇总（待收回 / 待归还）
 * 9. 账本隔离（ledger2 独立对拍，ledger1 的统计不含 ledger2 的账单）
 *
 * 【数据集】（固定 today = 2026-03-15 周日，全部日期由它推算，不依赖真实系统时间；
 * 金额单位：分；L1 = ledger1 日常账，L2 = ledger2 家庭账）
 *
 * ── 结构 ──
 * L1 账户：微信(网络账户, 计净资产, 初始 ¥10) / 招行卡(银行卡, 计, ¥50) /
 *          借贷账户(借贷, 计, ¥0) / 纪念册账户(其他, **不计入净资产**, ¥0)
 * L1 支出分类：餐饮[午餐, 咖啡] / 交通[打车] / 购物；收入分类：工资
 * L1 月度预算 ¥5000；自定义预算：餐饮预算(绑「餐饮」一级, 本月) / 咖啡预算(关键词"咖啡", 本月)
 * L2 账户：家庭卡(银行卡, 计, ¥200) / 家庭借贷(借贷, 计, ¥0) / 红包账户(其他, **不计入净资产**, ¥0)
 * L2 支出分类：餐饮 / 教育；收入分类：工资；月度预算 ¥3000
 *
 * ── L1 账单（42 笔，每笔标注口径变量）──
 *  #1  EXPENSE 3800   餐饮/午餐  微信      03-01 月初     二级分类 + 月初边界
 *  #2  EXPENSE 2500   餐饮/咖啡  微信      03-02          二级分类 + 备注含"咖啡"(命中关键词预算)
 *  #3  EXPENSE 12000  餐饮(一级) 微信      03-03          一级分类 + 命中餐饮预算
 *  #4  EXPENSE 8800   交通/打车  招行卡    03-05          二级分类
 *  #5  EXPENSE 1500   无分类     微信      03-06          无分类支出
 *  #6  EXPENSE 30000  购物       招行卡    03-08 退款5000 带退款(实计 25000)
 *  #7  EXPENSE 10000  购物       微信      03-09 手续费200 优惠500  带手续费+优惠(实计 9700)
 *  #8  EXPENSE 5000   餐饮/午餐  微信      03-10 不计收支 不计收支(仍进预算! countsInBudget 不看它)
 *  #9  EXPENSE 7000   交通       微信      03-11 不计预算 不计预算(仍进收支)
 *  #10 EXPENSE 4500   餐饮/咖啡  纪念册    03-12          不计净资产账户(整笔排除出统计/预算)
 *  #11 INCOME  1200000 工资      招行卡    03-15 今天     今天 + 本月收入
 *  #12 INCOME  80000   工资      微信      03-14 昨天     昨天
 *  #13 INCOME  50000   无分类    微信      03-13          无分类收入
 *  #14 INCOME  30000   工资      纪念册    03-13          排除账户收入(不进统计)
 *  #15 TRANSFER 50000 微信→招行卡          03-07          互转(不计收支, 余额互转)
 *  #16 TRANSFER 20000 招行卡→纪念册        03-16          互转到排除账户(余额有, 统计无)
 *  #17 LEND 100000 微信→借贷账户           03-04          借出(待收回)
 *  #18 BORROW 60000 招行卡→借贷账户        03-17          借入(待归还)
 *  #19 BORROW 30000 微信→借贷账户 settledTxnId=#17 03-18  部分收回(direction 与原借贷相反)
 *  #20 EXPENSE 6600   餐饮/午餐  微信      03-31 月末     月末边界
 *  #21 EXPENSE 1200   餐饮/咖啡  微信      03-08 上周     上周 + 命中关键词预算
 *  #22 EXPENSE 8888   购物       微信      03-10 回收站   回收站(deletedAt>0, 不进一切)
 *  #23 EXPENSE 1800   餐饮/咖啡  微信      03-15 今天     今天 + 命中关键词预算
 *  #24 TRANSFER 30000 微信→招行卡          03-15 今天     今天互转(日历 otherCount)
 *  #25 LEND 50000 招行卡→借贷账户          03-15 今天     今天借贷(日历 otherCount)
 *  #26 EXPENSE 2200   餐饮/午餐  微信      03-14 昨天     昨天
 *  #27 EXPENSE 2600   交通/打车  招行卡    03-08 上周     上周
 *  #28 INCOME  20000   工资      微信      03-08 上周     上周收入
 *  #29 EXPENSE 1000   餐饮       微信      03-19 退款5000 超额退款(实计下限 0)
 *  #30 INCOME  1200000 工资      招行卡    03-01 月初     月初收入
 *  #31 EXPENSE 5500   餐饮       微信      2026-02-10     上月
 *  #32 INCOME  1200000 工资      招行卡    2026-02-15     上月收入
 *  #33 EXPENSE 3300   交通       微信      2025-12-15     跨年(12 月, 报表年桶外)
 *  #34 INCOME  1200000 工资      招行卡    2025-12-15     跨年(12 月)
 *  #35 EXPENSE 4400   购物       微信      2026-01-10     跨年(1 月, 报表年桶内)
 *  #36 INCOME  1200000 工资      招行卡    2026-01-15     跨年(1 月)
 *  #37 EXPENSE 3600   餐饮       微信      2025-11-20     近 12 个月桶内
 *  #38 EXPENSE 4700   交通       微信      2025-08-15     近 12 个月桶内
 *  #39 EXPENSE 5800   餐饮       微信      2024-06-15     桶外(前年)
 *  #40 EXPENSE 6900   餐饮/午餐  微信      2026-04-10     未来(2026-04 桶内)
 *  #41 LEND 80000 微信→借贷账户            2026-02-20     借出
 *  #42 BORROW 80000 微信→借贷账户 settledTxnId=#41 03-12  完全收回
 *
 * ── L2 账单（8 笔）──
 *  #43 EXPENSE 5000   餐饮  家庭卡          03-05
 *  #44 EXPENSE 8000   教育  家庭卡          03-10
 *  #45 INCOME  500000  工资  家庭卡          03-15 今天
 *  #46 TRANSFER 30000 家庭卡→家庭借贷       03-08
 *  #47 LEND 40000 家庭卡→家庭借贷           03-09
 *  #48 EXPENSE 6000   餐饮  红包账户        03-11          不计净资产账户
 *  #49 EXPENSE 7000   教育  家庭卡          2026-02-15     上月
 *  #50 EXPENSE 9999   餐饮  家庭卡          03-12 回收站   回收站
 *
 * 【如何反向验证（不需要真改代码，照做即可让测试挂）】
 * 1. 把 Repository.effectiveCents 的互转 / 借贷分支改成也减 refundCents
 *    → 明细金额合计 / 账户余额对不上 → 挂；
 * 2. 把 countsInStats 的 !excludeFromStats 条件去掉 →「不计收支」账单(#8)混进收支 → 挂；
 * 3. 把 inStatsAccountScope 的排除判断去掉 → 排除账户(#10/#14/#16/#48)流水混进统计 → 挂；
 * 4. 把 observeMonthIncomeExpense 的 excludeFromStats = 0 删掉 → SQL 与 Kotlin 对不上 → 挂；
 * 5. 把 lendEndpoints 的 LEND / BORROW 分支对调 → 借贷余额与汇总全错 → 挂；
 * 6. 把 byLedger 的 deletedAt = 0 去掉 → 明细条数 / 金额含回收站账单(#22/#50) → 挂；
 * 7. 把 countsInBudget 改成也看 excludeFromStats →「不计收支但计预算」的 #8 从预算里消失 → 挂。
 *
 * 【复刻的口径语义摘要】（以 Repository.kt 源码为准，读不懂处标注理解）
 * - effectiveCents = 金额 − 退款 − 优惠 + 手续费，下限 0；仅 EXPENSE / INCOME 走这套，
 *   TRANSFER / LEND_BORROW 返回原金额（手续费 / 优惠只针对收支账单）；
 * - countsInStats = (EXPENSE 或 INCOME) 且未勾「不计入收支」；
 * - countsInBudget = EXPENSE 且未勾「不计入预算」——**与「不计入收支」无关**：
 *   勾了「不计收支」的支出仍进预算（数据集 #8 专门守这个反直觉点）；
 * - inStatsAccountScope：主账户或对方账户（互转转入 / 借贷对端）命中「不计入净资产」
 *   账户时整笔排除；只过滤统计聚合，余额与流水列表不受影响；
 * - lendEndpoints：LEND = (资金账户, 借贷账户)，BORROW = (借贷账户, 资金账户)；
 *   还款流水 direction 与原借贷相反、accountId / transferTo 与原借贷相同
 *   （照 AppViewModelTxn.lendSettlementOf 的记法，数据集 #19/#42 照此构造）；
 * - accountDeltas：EXPENSE −实计、INCOME +实计、TRANSFER ±原金额、
 *   LEND_BORROW 按 lendEndpoints ±原金额（借贷双端都记，整体净影响为 0）；
 * - lendTotals：待收回 = 借出 − 已收回；待归还 = 借入 − 已还出；还款流水冲减，下限 0；
 * - 自定义预算 matchesBudget：区间（含首尾两天）+ 分类 / 账户**精确 id** 绑定
 *   （绑一级分类只算直接挂它的账单，不含其二级分类——数据集 #1/#2 挂二级、
 *   #3 挂一级，专门守这点）+ 关键词（备注或对方，忽略大小写）+ 口径 + 账户范围；
 * - 逐月桶 / 分类汇总 / 日历都只认 countsInStats 的账单（互转 / 借贷 / 不计收支不进）。
 *
 * 【对拍中发现的已知跨页差异（记录待统一，不影响本测试全绿）】
 * 报表页 buildReport 的 budgetUsed 在 countsInBudget 之外**多要求 countsInStats**
 * （它从 counted 里再过滤），而首页 observeBudgetUsedCents 的 SQL 不要求——
 * 于是「不计收支但计预算」的账单（#8）进首页预算已用、不进报表预算已用。
 * 本测试的预算已用比对面以首页口径（= Repository.countsInBudget 语义）为准，
 * 该差异已记录，建议后续把 buildReport 的口径统一到 countsInBudget。
 */
@RunWith(RobolectricTestRunner::class)
class CrossCheckTest {

    companion object {
        /** 固定「今天」：2026-03-15（周日）。全部日期由它推算，不依赖真实系统时间 */
        val TODAY: LocalDate = LocalDate.of(2026, 3, 15)
        val YESTERDAY: LocalDate = TODAY.minusDays(1) // 03-14
        val LAST_WEEK: LocalDate = TODAY.minusDays(7) // 03-08
        val MONTH_START: LocalDate = TODAY.withDayOfMonth(1) // 03-01
        val MONTH_END: LocalDate = TODAY.withDayOfMonth(TODAY.lengthOfMonth()) // 03-31
        val LAST_MONTH_A: LocalDate = LocalDate.of(2026, 2, 10)
        val LAST_MONTH_B: LocalDate = LocalDate.of(2026, 2, 15)
        val LAST_FEB: LocalDate = LocalDate.of(2026, 2, 20)
        val DEC_LAST_YEAR: LocalDate = LocalDate.of(2025, 12, 15)
        val JAN_A: LocalDate = LocalDate.of(2026, 1, 10)
        val JAN_B: LocalDate = LocalDate.of(2026, 1, 15)
        val NOV_LAST_YEAR: LocalDate = LocalDate.of(2025, 11, 20)
        val AUG_LAST_YEAR: LocalDate = LocalDate.of(2025, 8, 15)
        val JUN_2024: LocalDate = LocalDate.of(2024, 6, 15)
        val NEXT_MONTH: LocalDate = LocalDate.of(2026, 4, 10)
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    // ledger1（日常账）
    private var ledger1 = 0L
    private var accWechat = 0L
    private var accBank = 0L
    private var accLend = 0L
    private var accKeepsake = 0L // 不计入净资产
    private var catFood = 0L
    private var catLunch = 0L
    private var catCoffee = 0L
    private var catTransit = 0L
    private var catTaxi = 0L
    private var catShop = 0L
    private var catSalary = 0L

    // ledger2（家庭账）
    private var ledger2 = 0L
    private var accFamily = 0L
    private var accFamilyLend = 0L
    private var accRedpacket = 0L // 不计入净资产
    private var catFamilyFood = 0L
    private var catFamilyEdu = 0L
    private var catFamilySalary = 0L

    private var originalZone: TimeZone? = null

    @Before
    fun setUp() = runBlocking {
        // 固定时区：DateFmt 走 ZoneId.systemDefault()，锁定后日期边界完全确定
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = Repository(db)
        seed()
    }

    @After
    fun tearDown() {
        db.close()
        originalZone?.let { TimeZone.setDefault(it) }
    }

    // ================= 数据集 =================

    private suspend fun seed() {
        // ---- 账本（2 个，验证隔离）----
        ledger1 = db.ledgerDao().insert(
            Ledger(name = "日常账", isDefault = true, monthlyBudgetCents = 500_000)
        )
        ledger2 = db.ledgerDao().insert(
            Ledger(name = "家庭账", isDefault = false, monthlyBudgetCents = 300_000)
        )

        // ---- ledger1 账户（含 1 个不计入净资产、1 个借贷类型）----
        accWechat = db.accountDao().insert(
            Account(ledgerId = ledger1, name = "微信", type = AccountType.NETWORK, initialBalanceCents = 100_000, sortOrder = 0)
        )
        accBank = db.accountDao().insert(
            Account(ledgerId = ledger1, name = "招行卡", type = AccountType.BANK_CARD, initialBalanceCents = 500_000, sortOrder = 1)
        )
        accLend = db.accountDao().insert(
            Account(ledgerId = ledger1, name = "借贷账户", type = AccountType.LEND, initialBalanceCents = 0, sortOrder = 2)
        )
        accKeepsake = db.accountDao().insert(
            Account(ledgerId = ledger1, name = "纪念册账户", type = AccountType.OTHER, initialBalanceCents = 0, sortOrder = 3, countInNetWorth = false)
        )

        // ---- ledger1 分类（支出 3 个一级：餐饮[午餐,咖啡] / 交通[打车] / 购物；收入 1 个一级：工资）----
        catFood = db.categoryDao().insert(Category(ledgerId = ledger1, name = "餐饮", type = CategoryType.EXPENSE, isPreset = true, sortOrder = 0))
        catLunch = db.categoryDao().insert(Category(ledgerId = ledger1, name = "午餐", type = CategoryType.EXPENSE, parentId = catFood, sortOrder = 0))
        catCoffee = db.categoryDao().insert(Category(ledgerId = ledger1, name = "咖啡", type = CategoryType.EXPENSE, parentId = catFood, sortOrder = 1))
        catTransit = db.categoryDao().insert(Category(ledgerId = ledger1, name = "交通", type = CategoryType.EXPENSE, isPreset = true, sortOrder = 1))
        catTaxi = db.categoryDao().insert(Category(ledgerId = ledger1, name = "打车", type = CategoryType.EXPENSE, parentId = catTransit, sortOrder = 0))
        catShop = db.categoryDao().insert(Category(ledgerId = ledger1, name = "购物", type = CategoryType.EXPENSE, isPreset = true, sortOrder = 2))
        catSalary = db.categoryDao().insert(Category(ledgerId = ledger1, name = "工资", type = CategoryType.INCOME, isPreset = true, sortOrder = 0))

        // ---- ledger2 账户与分类 ----
        accFamily = db.accountDao().insert(
            Account(ledgerId = ledger2, name = "家庭卡", type = AccountType.BANK_CARD, initialBalanceCents = 200_000, sortOrder = 0)
        )
        accFamilyLend = db.accountDao().insert(
            Account(ledgerId = ledger2, name = "家庭借贷", type = AccountType.LEND, initialBalanceCents = 0, sortOrder = 1)
        )
        accRedpacket = db.accountDao().insert(
            Account(ledgerId = ledger2, name = "红包账户", type = AccountType.OTHER, initialBalanceCents = 0, sortOrder = 2, countInNetWorth = false)
        )
        catFamilyFood = db.categoryDao().insert(Category(ledgerId = ledger2, name = "餐饮", type = CategoryType.EXPENSE, isPreset = true, sortOrder = 0))
        catFamilyEdu = db.categoryDao().insert(Category(ledgerId = ledger2, name = "教育", type = CategoryType.EXPENSE, isPreset = true, sortOrder = 1))
        catFamilySalary = db.categoryDao().insert(Category(ledgerId = ledger2, name = "工资", type = CategoryType.INCOME, isPreset = true, sortOrder = 0))

        // ---- 自定义预算（ledger1：绑分类的 + 带关键词的各一）----
        val budgetStart = DateFmt.toMillis(MONTH_START)
        val budgetEnd = DateFmt.toMillis(MONTH_END)
        val ts = DateFmt.toMillis(TODAY)
        db.budgetDao().insert(
            Budget(
                ledgerId = ledger1, name = "餐饮预算", startMillis = budgetStart, endMillis = budgetEnd,
                amountCents = 100_000, direction = BudgetDirection.EXPENSE, categoryId = catFood, createdAt = ts
            )
        )
        db.budgetDao().insert(
            Budget(
                ledgerId = ledger1, name = "咖啡预算", startMillis = budgetStart, endMillis = budgetEnd,
                amountCents = 50_000, direction = BudgetDirection.EXPENSE, keyword = "咖啡", createdAt = ts
            )
        )

        // ---- 账单（50 笔；每笔的口径变量见类顶部文档注释的编号表）----
        val trashAt = DateFmt.toMillis(TODAY.plusDays(5))

        // ===== ledger1：本月（2026-03）收支主战场 =====
        listOf(
            // #1 月初边界 + 二级分类支出
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catLunch, type = TxnType.EXPENSE, amountCents = 3800, dateMillis = DateFmt.toMillis(MONTH_START), createdAt = ts, note = "月初午餐"),
            // #2 二级分类支出 + 备注含「咖啡」（命中关键词预算）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catCoffee, type = TxnType.EXPENSE, amountCents = 2500, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(1)), createdAt = ts, note = "公司咖啡"),
            // #3 一级分类支出 + 命中餐饮预算（精确 id 绑定）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catFood, type = TxnType.EXPENSE, amountCents = 12000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(2)), createdAt = ts, note = "周末聚餐"),
            // #4 二级分类支出
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catTaxi, type = TxnType.EXPENSE, amountCents = 8800, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(4)), createdAt = ts, note = "打车去机场"),
            // #5 无分类支出
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = 0, type = TxnType.EXPENSE, amountCents = 1500, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(5)), createdAt = ts, note = "不明支出"),
            // #6 带退款（实计 25000）
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catShop, type = TxnType.EXPENSE, amountCents = 30000, refundCents = 5000, dateMillis = DateFmt.toMillis(LAST_WEEK), createdAt = ts, note = "耳机退款"),
            // #7 带手续费 + 优惠（实计 9700）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catShop, type = TxnType.EXPENSE, amountCents = 10000, feeCents = 200, discountCents = 500, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(8)), createdAt = ts, note = "满减购物"),
            // #8 不计收支（仍进预算：countsInBudget 不看 excludeFromStats）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catLunch, type = TxnType.EXPENSE, amountCents = 5000, excludeFromStats = true, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(9)), createdAt = ts, note = "不计收支的午餐"),
            // #9 不计预算（仍进收支）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catTransit, type = TxnType.EXPENSE, amountCents = 7000, excludeFromBudget = true, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(10)), createdAt = ts, note = "不计预算的交通"),
            // #10 不计入净资产账户（整笔排除出统计与预算，余额照算）
            Txn(ledgerId = ledger1, accountId = accKeepsake, categoryId = catCoffee, type = TxnType.EXPENSE, amountCents = 4500, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(11)), createdAt = ts, note = "纪念品咖啡"),
            // #11 今天 + 本月收入
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catSalary, type = TxnType.INCOME, amountCents = 1200000, dateMillis = DateFmt.toMillis(TODAY), createdAt = ts, note = "三月工资"),
            // #12 昨天
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catSalary, type = TxnType.INCOME, amountCents = 80000, dateMillis = DateFmt.toMillis(YESTERDAY), createdAt = ts, note = "昨天奖金"),
            // #13 无分类收入
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = 0, type = TxnType.INCOME, amountCents = 50000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(12)), createdAt = ts, note = "不明收入"),
            // #14 排除账户收入（不进统计）
            Txn(ledgerId = ledger1, accountId = accKeepsake, categoryId = catSalary, type = TxnType.INCOME, amountCents = 30000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(12)), createdAt = ts, note = "纪念品收入"),
            // #15 互转
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accBank, type = TxnType.TRANSFER, amountCents = 50000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(6)), createdAt = ts, note = "微信提现"),
            // #16 互转到排除账户（余额有变动，统计无）
            Txn(ledgerId = ledger1, accountId = accBank, transferToAccountId = accKeepsake, type = TxnType.TRANSFER, amountCents = 20000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(15)), createdAt = ts, note = "转纪念册"),
            // #17 借出（待收回）
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.LEND, amountCents = 100000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(3)), createdAt = ts, note = "借给老王"),
            // #18 借入（待归还）
            Txn(ledgerId = ledger1, accountId = accBank, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.BORROW, amountCents = 60000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(16)), createdAt = ts, note = "向老李借"),
            // #19 部分收回（direction 与原借贷相反，accountId/transferTo 与原借贷相同）
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.BORROW, amountCents = 30000, settledTxnId = 0, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(17)), createdAt = ts, note = "老王还了一部分"),
            // #20 月末边界
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catLunch, type = TxnType.EXPENSE, amountCents = 6600, dateMillis = DateFmt.toMillis(MONTH_END), createdAt = ts, note = "月末午餐"),
            // #21 上周 + 命中关键词预算
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catCoffee, type = TxnType.EXPENSE, amountCents = 1200, dateMillis = DateFmt.toMillis(LAST_WEEK), createdAt = ts, note = "上周咖啡"),
            // #22 回收站（deletedAt>0，不进一切统计与余额）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catShop, type = TxnType.EXPENSE, amountCents = 8888, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(9)), createdAt = ts, deletedAt = trashAt, note = "回收站购物"),
            // #23 今天 + 命中关键词预算
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catCoffee, type = TxnType.EXPENSE, amountCents = 1800, dateMillis = DateFmt.toMillis(TODAY), createdAt = ts, note = "今天咖啡"),
            // #24 今天互转（日历 otherCount）
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accBank, type = TxnType.TRANSFER, amountCents = 30000, dateMillis = DateFmt.toMillis(TODAY), createdAt = ts, note = "今天互转"),
            // #25 今天借贷（日历 otherCount）
            Txn(ledgerId = ledger1, accountId = accBank, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.LEND, amountCents = 50000, dateMillis = DateFmt.toMillis(TODAY), createdAt = ts, note = "今天借出"),
            // #26 昨天
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catLunch, type = TxnType.EXPENSE, amountCents = 2200, dateMillis = DateFmt.toMillis(YESTERDAY), createdAt = ts, note = "昨天午餐"),
            // #27 上周
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catTaxi, type = TxnType.EXPENSE, amountCents = 2600, dateMillis = DateFmt.toMillis(LAST_WEEK), createdAt = ts, note = "上周打车"),
            // #28 上周收入
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catSalary, type = TxnType.INCOME, amountCents = 20000, dateMillis = DateFmt.toMillis(LAST_WEEK), createdAt = ts, note = "上周红包"),
            // #29 超额退款（实计下限 0）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catFood, type = TxnType.EXPENSE, amountCents = 1000, refundCents = 5000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(18)), createdAt = ts, note = "超额退款"),
            // #30 月初收入
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catSalary, type = TxnType.INCOME, amountCents = 1200000, dateMillis = DateFmt.toMillis(MONTH_START), createdAt = ts, note = "月初工资"),

            // ===== ledger1：上月 / 跨年 / 桶边界 =====
            // #31 上月支出
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catFood, type = TxnType.EXPENSE, amountCents = 5500, dateMillis = DateFmt.toMillis(LAST_MONTH_A), createdAt = ts, note = "上月餐饮"),
            // #32 上月收入
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catSalary, type = TxnType.INCOME, amountCents = 1200000, dateMillis = DateFmt.toMillis(LAST_MONTH_B), createdAt = ts, note = "上月工资"),
            // #33 跨年（12 月，报表按年桶外）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catTransit, type = TxnType.EXPENSE, amountCents = 3300, dateMillis = DateFmt.toMillis(DEC_LAST_YEAR), createdAt = ts, note = "去年十二月交通"),
            // #34 跨年（12 月）
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catSalary, type = TxnType.INCOME, amountCents = 1200000, dateMillis = DateFmt.toMillis(DEC_LAST_YEAR), createdAt = ts, note = "去年十二月工资"),
            // #35 跨年（1 月，报表按年桶内）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catShop, type = TxnType.EXPENSE, amountCents = 4400, dateMillis = DateFmt.toMillis(JAN_A), createdAt = ts, note = "一月购物"),
            // #36 跨年（1 月）
            Txn(ledgerId = ledger1, accountId = accBank, categoryId = catSalary, type = TxnType.INCOME, amountCents = 1200000, dateMillis = DateFmt.toMillis(JAN_B), createdAt = ts, note = "一月工资"),
            // #37 近 12 个月桶内
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catFood, type = TxnType.EXPENSE, amountCents = 3600, dateMillis = DateFmt.toMillis(NOV_LAST_YEAR), createdAt = ts, note = "十一月餐饮"),
            // #38 近 12 个月桶内
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catTransit, type = TxnType.EXPENSE, amountCents = 4700, dateMillis = DateFmt.toMillis(AUG_LAST_YEAR), createdAt = ts, note = "八月交通"),
            // #39 桶外（前年）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catFood, type = TxnType.EXPENSE, amountCents = 5800, dateMillis = DateFmt.toMillis(JUN_2024), createdAt = ts, note = "前年餐饮"),
            // #40 未来（2026-04 桶内）
            Txn(ledgerId = ledger1, accountId = accWechat, categoryId = catLunch, type = TxnType.EXPENSE, amountCents = 6900, dateMillis = DateFmt.toMillis(NEXT_MONTH), createdAt = ts, note = "下月午餐"),
            // #41 借出
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.LEND, amountCents = 80000, dateMillis = DateFmt.toMillis(LAST_FEB), createdAt = ts, note = "借给老张"),
            // #42 完全收回
            Txn(ledgerId = ledger1, accountId = accWechat, transferToAccountId = accLend, type = TxnType.LEND_BORROW, direction = Direction.BORROW, amountCents = 80000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(11)), createdAt = ts, note = "老张还清了"),

            // ===== ledger2：家庭账（隔离验证）=====
            // #43
            Txn(ledgerId = ledger2, accountId = accFamily, categoryId = catFamilyFood, type = TxnType.EXPENSE, amountCents = 5000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(4)), createdAt = ts, note = "家庭餐饮"),
            // #44
            Txn(ledgerId = ledger2, accountId = accFamily, categoryId = catFamilyEdu, type = TxnType.EXPENSE, amountCents = 8000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(9)), createdAt = ts, note = "孩子学费"),
            // #45 今天
            Txn(ledgerId = ledger2, accountId = accFamily, categoryId = catFamilySalary, type = TxnType.INCOME, amountCents = 500000, dateMillis = DateFmt.toMillis(TODAY), createdAt = ts, note = "家庭收入"),
            // #46 互转
            Txn(ledgerId = ledger2, accountId = accFamily, transferToAccountId = accFamilyLend, type = TxnType.TRANSFER, amountCents = 30000, dateMillis = DateFmt.toMillis(LAST_WEEK), createdAt = ts, note = "家庭互转"),
            // #47 借出
            Txn(ledgerId = ledger2, accountId = accFamily, transferToAccountId = accFamilyLend, type = TxnType.LEND_BORROW, direction = Direction.LEND, amountCents = 40000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(8)), createdAt = ts, note = "家庭借出"),
            // #48 不计入净资产账户
            Txn(ledgerId = ledger2, accountId = accRedpacket, categoryId = catFamilyFood, type = TxnType.EXPENSE, amountCents = 6000, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(10)), createdAt = ts, note = "红包支出"),
            // #49 上月
            Txn(ledgerId = ledger2, accountId = accFamily, categoryId = catFamilyEdu, type = TxnType.EXPENSE, amountCents = 7000, dateMillis = DateFmt.toMillis(LAST_MONTH_B), createdAt = ts, note = "上月教育"),
            // #50 回收站
            Txn(ledgerId = ledger2, accountId = accFamily, categoryId = catFamilyFood, type = TxnType.EXPENSE, amountCents = 9999, dateMillis = DateFmt.toMillis(MONTH_START.plusDays(11)), createdAt = ts, deletedAt = trashAt, note = "回收站家庭")
        ).forEach { db.txnDao().insert(it) }

        // 回填 settledTxnId：#19 指向 #17、#42 指向 #41（先插后补，模拟真实写入顺序）
        val all = db.txnDao().byLedger(ledger1)
        val lend17 = all.first { it.note == "借给老王" }
        val lend41 = all.first { it.note == "借给老张" }
        val settle19 = all.first { it.note == "老王还了一部分" }
        val settle42 = all.first { it.note == "老张还清了" }
        db.txnDao().update(settle19.copy(settledTxnId = lend17.id))
        db.txnDao().update(settle42.copy(settledTxnId = lend41.id))
    }

    // ================= A 套：现有实现（Repository + DAO，复刻各页面聚合口径） =================

    /** 首页「当月收支」：走 SQL 下推（TxnDao.observeMonthIncomeExpense，N60） */
    private suspend fun homeMonthSumSql(ledgerId: Long, excluded: Set<Long>): Pair<Long, Long> {
        val (s, e) = DateFmt.monthRange(TODAY)
        // 空集合传哨兵 -1：NOT IN (-1) 恒真（与 AppViewModel.monthStats 一致）
        val sum = db.txnDao()
            .observeMonthIncomeExpense(ledgerId, s, e, excluded.ifEmpty { setOf(-1L) })
            .first()
        return sum.incomeCents to sum.expenseCents
    }

    /** 首页「当月预算已用」：走 SQL 下推（TxnDao.observeBudgetUsedCents） */
    private suspend fun homeBudgetUsedSql(ledgerId: Long, excluded: Set<Long>): Long {
        val (s, e) = DateFmt.monthRange(TODAY)
        return db.txnDao()
            .observeBudgetUsedCents(ledgerId, s, e, excluded.ifEmpty { setOf(-1L) })
            .first()
    }

    /**
     * 总览页「本月收支」：复刻 statsTxns（近 6 个月窗口 + inStatsAccountScope 过滤）
     * + buildStats 的当月聚合（countsInStats + effectiveCents）。
     */
    private fun overviewMonthSum(all: List<Txn>, excluded: Set<Long>): Pair<Long, Long> {
        val windowStart = DateFmt.toMillis(TODAY.minusMonths(5).withDayOfMonth(1))
        val windowEnd = DateFmt.toMillis(TODAY.withDayOfMonth(1).plusMonths(1))
        val statsTxns = all.filter { it.dateMillis in windowStart until windowEnd }
            .filter { Repository.inStatsAccountScope(it, excluded) }
        val (s, e) = DateFmt.monthRange(TODAY)
        val counted = statsTxns.filter { it.dateMillis in s until e && Repository.countsInStats(it) }
        val income = counted.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) }
        val expense = counted.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
        return income to expense
    }

    /**
     * 报表页「逐月桶」（复刻 buildReport kind==2 按年）：statsTxns（5 年窗口 + 过滤）
     * → 当年区间 + countsInStats → 12 个月桶（每月收入 / 支出）。
     */
    private fun reportYearBuckets(all: List<Txn>, excluded: Set<Long>): List<Triple<Int, Long, Long>> {
        val windowStart = DateFmt.toMillis(LocalDate.of(TODAY.year - 4, 1, 1))
        val windowEnd = DateFmt.toMillis(LocalDate.of(TODAY.year + 1, 1, 1))
        val statsTxns = all.filter { it.dateMillis in windowStart until windowEnd }
            .filter { Repository.inStatsAccountScope(it, excluded) }
        val yearStart = DateFmt.toMillis(LocalDate.of(TODAY.year, 1, 1))
        val yearEnd = DateFmt.toMillis(LocalDate.of(TODAY.year + 1, 1, 1))
        val counted = statsTxns.filter { it.dateMillis in yearStart until yearEnd && Repository.countsInStats(it) }
        return (1..12).map { m ->
            val ms = DateFmt.toMillis(LocalDate.of(TODAY.year, m, 1))
            val me = DateFmt.toMillis(LocalDate.of(TODAY.year, m, 1).plusMonths(1))
            val sub = counted.filter { it.dateMillis in ms until me }
            Triple(
                m,
                sub.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) },
                sub.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
            )
        }
    }

    /**
     * 近 12 个月桶（2025-04 ~ 2026-03）：报表 5 年窗口内的逐月收支，
     * 专门覆盖跨年（12 月 / 1 月）边界。
     */
    private fun recent12Buckets(all: List<Txn>, excluded: Set<Long>): List<Triple<Int, Long, Long>> {
        val windowStart = DateFmt.toMillis(LocalDate.of(TODAY.year - 4, 1, 1))
        val windowEnd = DateFmt.toMillis(LocalDate.of(TODAY.year + 1, 1, 1))
        val statsTxns = all.filter { it.dateMillis in windowStart until windowEnd }
            .filter { Repository.inStatsAccountScope(it, excluded) }
        return (11 downTo 0).map { TODAY.withDayOfMonth(1).minusMonths(it.toLong()) }.map { m ->
            val (ms, me) = DateFmt.monthRange(m)
            val sub = statsTxns.filter { it.dateMillis in ms until me && Repository.countsInStats(it) }
            Triple(
                m.monthValue,
                sub.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) },
                sub.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
            )
        }
    }

    /**
     * 报表页「各一级分类汇总」（复刻 buildReport 的 rank）：当期范围内 countsInStats
     * 的账单按一级分类名分组求和（二级分类归到父分类，无分类进「未分类」桶）。
     */
    private fun reportCategoryRank(
        all: List<Txn>,
        cats: List<Category>,
        excluded: Set<Long>,
        type: TxnType
    ): Map<String, Long> {
        val (s, e) = DateFmt.monthRange(TODAY)
        val parentName = { id: Long ->
            val c = cats.firstOrNull { it.id == id }
            val pid = c?.parentId ?: 0L
            if (pid != 0L) cats.firstOrNull { it.id == pid }?.name ?: "未分类" else c?.name ?: "未分类"
        }
        return all.filter {
            it.dateMillis in s until e &&
                Repository.inStatsAccountScope(it, excluded) && // 真实路径的 statsTxns 已按账户口径过滤过
                Repository.countsInStats(it) && it.type == type
        }
            .groupBy { parentName(it.categoryId) }
            .mapValues { (_, ts) -> ts.sumOf { Repository.effectiveCents(it) } }
    }

    /**
     * 日历「当日合计」（复刻 calDays）：statsTxns（近 6 个月窗口 + 过滤）→ 当月 →
     * 当日：countsInStats 的进收入 / 支出，互转 / 借贷 / 不计收支只进「其他笔数」。
     */
    private fun calendarToday(all: List<Txn>, excluded: Set<Long>): Triple<Long, Long, Int> {
        val windowStart = DateFmt.toMillis(TODAY.minusMonths(5).withDayOfMonth(1))
        val windowEnd = DateFmt.toMillis(TODAY.withDayOfMonth(1).plusMonths(1))
        val statsTxns = all.filter { it.dateMillis in windowStart until windowEnd }
            .filter { Repository.inStatsAccountScope(it, excluded) }
        val (monthStart, monthEnd) = DateFmt.monthRange(TODAY)
        val (dayStart, dayEnd) = DateFmt.dayRange(TODAY)
        val dayTxns = statsTxns.filter { it.dateMillis in monthStart until monthEnd && it.dateMillis in dayStart until dayEnd }
        val income = dayTxns.filter { Repository.countsInStats(it) && it.type == TxnType.INCOME }
            .sumOf { Repository.effectiveCents(it) }
        val expense = dayTxns.filter { Repository.countsInStats(it) && it.type == TxnType.EXPENSE }
            .sumOf { Repository.effectiveCents(it) }
        val otherCount = dayTxns.count { !Repository.countsInStats(it) }
        return Triple(income, expense, otherCount)
    }

    // ================= 比对面 1~9 =================

    /** 本月收入 / 支出 / 结余：首页 SQL + 总览 Kotlin 两个来源，都与手写实现一致 */
    @Test
    fun monthIncomeExpense_allImplementationsMatch() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val excluded1 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger1))

        // A 套 1：首页 SQL 下推
        val (sqlIncome, sqlExpense) = homeMonthSumSql(ledger1, excluded1)
        // A 套 2：总览页 Kotlin 口径
        val (ovIncome, ovExpense) = overviewMonthSum(all1, excluded1)
        // B 套：手写（排除账户集合也自己按 countInNetWorth 算，不借 Repository）
        val bExcluded = db.accountDao().byLedger(ledger1).filter { !it.countInNetWorth }.map { it.id }.toSet()
        val (ms, me) = Ref.monthRange(TODAY)
        val bCounted = all1.filter { it.dateMillis in ms until me }
            .filter { Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) }
        val bIncome = bCounted.filter { it.type == TxnType.INCOME }.sumOf { Ref.effectiveCents(it) }
        val bExpense = bCounted.filter { it.type == TxnType.EXPENSE }.sumOf { Ref.effectiveCents(it) }

        assertEquals("本月收入：首页 SQL vs 手写", bIncome, sqlIncome)
        assertEquals("本月支出：首页 SQL vs 手写", bExpense, sqlExpense)
        assertEquals("本月收入：总览 Kotlin vs 手写", bIncome, ovIncome)
        assertEquals("本月支出：总览 Kotlin vs 手写", bExpense, ovExpense)
        assertEquals("本月结余", bIncome - bExpense, sqlIncome - sqlExpense)
        // 数据集有效性：本月确实有收支（防止空跑导致假绿）
        assertTrue("本月收入应为正", bIncome > 0)
        assertTrue("本月支出应为正", bExpense > 0)
    }

    /** 各一级分类汇总：支出 / 收入（含「未分类」桶），且汇总之和 = 本月收支 */
    @Test
    fun categorySummary_matchesHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val cats = db.categoryDao().byLedger(ledger1)
        val excluded1 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger1))
        val bExcluded = db.accountDao().byLedger(ledger1).filter { !it.countInNetWorth }.map { it.id }.toSet()

        // A 套：复刻 buildReport 的 rank
        val aExpense = reportCategoryRank(all1, cats, excluded1, TxnType.EXPENSE)
        val aIncome = reportCategoryRank(all1, cats, excluded1, TxnType.INCOME)
        // B 套：同样的分组逻辑 + 手写 effectiveCents
        val (s, e) = DateFmt.monthRange(TODAY)
        val (ms, me) = Ref.monthRange(TODAY)
        val parentName = { id: Long ->
            val c = cats.firstOrNull { it.id == id }
            val pid = c?.parentId ?: 0L
            if (pid != 0L) cats.firstOrNull { it.id == pid }?.name ?: "未分类" else c?.name ?: "未分类"
        }
        fun bRank(type: TxnType): Map<String, Long> =
            all1.filter { it.dateMillis in ms until me && Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) && it.type == type }
                .groupBy { parentName(it.categoryId) }
                .mapValues { (_, ts) -> ts.sumOf { Ref.effectiveCents(it) } }
        val bExpense = bRank(TxnType.EXPENSE)
        val bIncome = bRank(TxnType.INCOME)

        assertEquals("支出分类汇总", bExpense, aExpense)
        assertEquals("收入分类汇总", bIncome, aIncome)

        // 交叉验证：分类汇总之和必须等于本月收支（守住「总览有数、下钻空白」类 bug）
        val bMonthExpense = all1.filter { it.dateMillis in ms until me && Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) && it.type == TxnType.EXPENSE }
            .sumOf { Ref.effectiveCents(it) }
        val bMonthIncome = all1.filter { it.dateMillis in ms until me && Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) && it.type == TxnType.INCOME }
            .sumOf { Ref.effectiveCents(it) }
        assertEquals("支出分类汇总之和 = 本月支出", bMonthExpense, bExpense.values.sum())
        assertEquals("收入分类汇总之和 = 本月收入", bMonthIncome, bIncome.values.sum())
        // 数据集有效性：餐饮 / 交通 / 购物 / 未分类 四个支出桶都应有数
        assertTrue("餐饮桶应有数", (bExpense["餐饮"] ?: 0L) > 0)
        assertTrue("交通桶应有数", (bExpense["交通"] ?: 0L) > 0)
        assertTrue("购物桶应有数", (bExpense["购物"] ?: 0L) > 0)
        assertTrue("未分类支出桶应有数", (bExpense["未分类"] ?: 0L) > 0)
        assertTrue("未分类收入桶应有数", (bIncome["未分类"] ?: 0L) > 0)
    }

    /** 逐月桶：报表按年 12 桶（2026-01~12）+ 近 12 个月桶（2025-04~2026-03，覆盖跨年） */
    @Test
    fun monthlyBuckets_matchHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val excluded1 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger1))
        val bExcluded = db.accountDao().byLedger(ledger1).filter { !it.countInNetWorth }.map { it.id }.toSet()

        // ---- 报表按年 12 桶 ----
        val aYear = reportYearBuckets(all1, excluded1)
        val yearStart = DateFmt.toMillis(LocalDate.of(TODAY.year, 1, 1))
        val yearEnd = DateFmt.toMillis(LocalDate.of(TODAY.year + 1, 1, 1))
        val bCounted = all1.filter { Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) }
            .filter { it.dateMillis in yearStart until yearEnd }
        val bYear = (1..12).map { m ->
            val ms = Ref.millisOf(LocalDate.of(TODAY.year, m, 1))
            val me = Ref.millisOf(LocalDate.of(TODAY.year, m, 1).plusMonths(1))
            val sub = bCounted.filter { it.dateMillis in ms until me }
            Triple(
                m,
                sub.filter { it.type == TxnType.INCOME }.sumOf { Ref.effectiveCents(it) },
                sub.filter { it.type == TxnType.EXPENSE }.sumOf { Ref.effectiveCents(it) }
            )
        }
        assertEquals("报表按年 12 桶", bYear, aYear)
        // 跨年验证：2025-12 的账单（#33/#34）不在 2026 年任何桶里；2026-01 桶内有数
        assertEquals("2026-01 桶支出（#35）", 4400L, aYear[0].third)
        assertEquals("2026-01 桶收入（#36）", 1200000L, aYear[0].second)
        assertEquals("2026-03 桶支出 = 本月支出", aYear[2].third, overviewMonthSum(all1, excluded1).second)
        assertEquals("2026-04 桶支出（#40 未来账单）", 6900L, aYear[3].third)

        // ---- 近 12 个月桶（2025-04 ~ 2026-03）----
        val aRecent = recent12Buckets(all1, excluded1)
        val bRecent = (11 downTo 0).map { TODAY.withDayOfMonth(1).minusMonths(it.toLong()) }.map { m ->
            val (ms, me) = Ref.monthRange(m)
            val sub = all1.filter { Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) && it.dateMillis in ms until me }
            Triple(
                m.monthValue,
                sub.filter { it.type == TxnType.INCOME }.sumOf { Ref.effectiveCents(it) },
                sub.filter { it.type == TxnType.EXPENSE }.sumOf { Ref.effectiveCents(it) }
            )
        }
        assertEquals("近 12 个月桶", bRecent, aRecent)
        // 跨年验证：2025-12 桶（近 12 个月索引 8）有 #33/#34；2024-06 的 #39 不在任何桶
        assertEquals("2025-12 桶支出（#33）", 3300L, aRecent[8].third)
        assertEquals("2025-12 桶收入（#34）", 1200000L, aRecent[8].second)
        assertTrue(
            "近 12 个月桶应不含 2024-06 的 #39（2025-06 桶应为 0）",
            aRecent.filter { it.first == 6 }.all { it.second == 0L && it.third == 0L }
        )
        // 桶数 sanity
        assertEquals(12, aYear.size)
        assertEquals(12, aRecent.size)
    }

    /** 预算已用：月度预算（SQL + Kotlin）+ 自定义预算（绑分类 / 带关键词各一） */
    @Test
    fun budgetUsed_matchesHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val excluded1 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger1))
        val bExcluded = db.accountDao().byLedger(ledger1).filter { !it.countInNetWorth }.map { it.id }.toSet()
        val (s, e) = DateFmt.monthRange(TODAY)
        val (ms, me) = Ref.monthRange(TODAY)

        // ---- 月度预算已用 ----
        // A 套 1：首页 SQL 下推
        val sqlBudget = homeBudgetUsedSql(ledger1, excluded1)
        // A 套 2：Kotlin 口径（countsInBudget + inStatsAccountScope + effectiveCents）
        val kotlinBudget = all1.filter { it.dateMillis in s until e && Repository.countsInBudget(it) && Repository.inStatsAccountScope(it, excluded1) }
            .sumOf { Repository.effectiveCents(it) }
        // B 套：手写
        val bBudget = all1.filter { it.dateMillis in ms until me && Ref.countsInBudget(it) && Ref.inStatsAccountScope(it, bExcluded) }
            .sumOf { Ref.effectiveCents(it) }

        assertEquals("月度预算已用：SQL vs 手写", bBudget, sqlBudget)
        assertEquals("月度预算已用：Kotlin vs 手写", bBudget, kotlinBudget)
        assertTrue("预算已用应为正", bBudget > 0)
        // 口径验证：预算已用 ≠ 本月支出（#8 不计收支但进预算 +5000，#9 计收支但不进预算 −7000）
        val bMonthExpense = all1.filter { it.dateMillis in ms until me && Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded) && it.type == TxnType.EXPENSE }
            .sumOf { Ref.effectiveCents(it) }
        assertTrue("预算已用应与本月支出不同（#8/#9 制造的口径差）", bBudget != bMonthExpense)

        // ---- 自定义预算（Repository.budgetUsedCents vs 手写）----
        val budgets = db.budgetDao().byLedger(ledger1)
        assertEquals("应有 2 条自定义预算", 2, budgets.size)
        budgets.forEach { b ->
            val aUsed = Repository.budgetUsedCents(b, all1, excluded1)
            val bUsed = Ref.budgetUsedCents(b, all1, bExcluded)
            assertEquals("自定义预算「${b.name}」已用", bUsed, aUsed)
        }
        // 口径验证：餐饮预算只算直接挂「餐饮」一级的 #3（12000）+ #29（实计 0），
        // 挂二级的 #1/#2/#20/#21/#23/#26 不命中（精确 id 绑定）
        val foodBudget = budgets.first { it.name == "餐饮预算" }
        assertEquals("餐饮预算已用（只算挂一级的）", 12000L, Repository.budgetUsedCents(foodBudget, all1, excluded1))
        // 咖啡预算：备注含「咖啡」的 #2/#21/#23（#10 在排除账户，不命中）
        val coffeeBudget = budgets.first { it.name == "咖啡预算" }
        assertEquals("咖啡预算已用（关键词命中）", 5500L, Repository.budgetUsedCents(coffeeBudget, all1, excluded1))
    }

    /** 日历当日合计：今天（2026-03-15）的收入 / 支出 / 其他笔数 */
    @Test
    fun calendarToday_matchesHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val excluded1 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger1))
        val bExcluded = db.accountDao().byLedger(ledger1).filter { !it.countInNetWorth }.map { it.id }.toSet()

        // A 套：复刻 calDays 当日口径
        val (aIncome, aExpense, aOther) = calendarToday(all1, excluded1)
        // B 套：手写
        val (dayStart, dayEnd) = Ref.dayRange(TODAY)
        val bDayTxns = all1.filter { it.dateMillis in dayStart until dayEnd && Ref.inStatsAccountScope(it, bExcluded) }
        val bIncome = bDayTxns.filter { Ref.countsInStats(it) && it.type == TxnType.INCOME }.sumOf { Ref.effectiveCents(it) }
        val bExpense = bDayTxns.filter { Ref.countsInStats(it) && it.type == TxnType.EXPENSE }.sumOf { Ref.effectiveCents(it) }
        val bOther = bDayTxns.count { !Ref.countsInStats(it) }

        assertEquals("日历当日收入", bIncome, aIncome)
        assertEquals("日历当日支出", bExpense, aExpense)
        assertEquals("日历当日其他笔数（互转/借贷/不计收支）", bOther, aOther)
        // 数据集有效性：今天有 #11 收入、#23 支出、#24 互转、#25 借贷
        assertEquals("当日收入 = #11", 1200000L, bIncome)
        assertEquals("当日支出 = #23", 1800L, bExpense)
        assertEquals("当日其他笔数 = #24 + #25", 2, bOther)
    }

    /** 明细列表条数与金额合计：deletedAt=0 口径（回收站账单必须排除） */
    @Test
    fun txnListCountAndSum_matchesHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)

        // A 套：明细列表 = byLedger（deletedAt=0），金额合计 = Σ 实计金额
        val aCount = all1.size
        val aSum = all1.sumOf { Repository.effectiveCents(it) }
        // B 套：手写过滤（deletedAt=0 + 账本匹配）+ 手写实计金额
        val bCount = all1.count { it.deletedAt == 0L && it.ledgerId == ledger1 }
        val bSum = all1.filter { it.deletedAt == 0L }.sumOf { Ref.effectiveCents(it) }

        assertEquals("明细列表条数", bCount, aCount)
        assertEquals("明细列表金额合计", bSum, aSum)
        // 数据集有效性：42 笔 - 1 笔回收站（#22）= 41 笔
        assertEquals("明细条数（42 笔减去回收站 1 笔）", 41, aCount)
        // 回收站里确实有 1 笔（DAO 口径 sanity）
        val trash = db.txnDao().observeTrash(ledger1).first()
        assertEquals("回收站应有 1 笔", 1, trash.size)
    }

    /** 各账户余额：初始余额 + 流水净影响（含互转 / 借贷双端，回收站不进） */
    @Test
    fun accountBalances_matchHandwritten() = runBlocking {
        val accounts = db.accountDao().byLedger(ledger1)
        val all1 = db.txnDao().byLedger(ledger1)

        // A 套：Repository.balancesOf
        val aBalances = repo.balancesOf(accounts, all1)
        // B 套：手写 accountDeltas
        val bDeltas = Ref.accountDeltas(all1)

        assertEquals("应有 4 个账户", 4, aBalances.size)
        aBalances.forEach { ab ->
            val expected = ab.account.initialBalanceCents + (bDeltas[ab.account.id] ?: 0L)
            assertEquals("账户「${ab.account.name}」余额", expected, ab.balanceCents)
        }
        // 数据集有效性：借贷账户余额 = 借出/借入/收回的净影响（双端记账，整体守恒）
        val lendBalance = aBalances.first { it.account.id == accLend }.balanceCents
        assertTrue("借贷账户应有非零余额（双端记账）", lendBalance != 0L)
        // 排除账户的余额照常计算（只是不进净资产）
        val keepsakeBalance = aBalances.first { it.account.id == accKeepsake }.balanceCents
        assertTrue("纪念册账户余额照算", keepsakeBalance != 0L)
    }

    /** 借贷汇总：待收回 / 待归还（还款流水冲减，已结清的不重复计） */
    @Test
    fun lendTotals_matchHandwritten() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val lends = all1.filter { it.type == TxnType.LEND_BORROW }

        // A 套：Repository.lendTotals
        val a = Repository.lendTotals(lends)
        // B 套：手写
        val b = Ref.lendTotals(lends)

        assertEquals("待收回", b.first, a.first)
        assertEquals("待归还", b.second, a.second)
        // 数据集有效性：#17 借出 100000 − #19 收回 30000 + #25 借出 50000 + #41 借出 80000 − #42 收回 80000 = 120000
        assertEquals("待收回 = 120000", 120000L, a.first)
        // #18 借入 60000 未还
        assertEquals("待归还 = 60000", 60000L, a.second)
    }

    /** 账本隔离：ledger2 的数字独立对拍，ledger1 的统计不含 ledger2 的账单 */
    @Test
    fun ledgerIsolation_secondLedgerIndependent() = runBlocking {
        val all1 = db.txnDao().byLedger(ledger1)
        val all2 = db.txnDao().byLedger(ledger2)

        // 两个账本的账单互不串门
        assertTrue("ledger1 不应含 ledger2 的账单", all1.none { it.ledgerId == ledger2 })
        assertTrue("ledger2 不应含 ledger1 的账单", all2.none { it.ledgerId == ledger1 })
        assertTrue("ledger2 应有账单", all2.isNotEmpty())

        // ledger2 的数字独立对拍（A 套 SQL vs B 套手写）
        val excluded2 = Repository.statsExcludedAccountIds(db.accountDao().byLedger(ledger2))
        val bExcluded2 = db.accountDao().byLedger(ledger2).filter { !it.countInNetWorth }.map { it.id }.toSet()
        val (s, e) = DateFmt.monthRange(TODAY)
        val (ms, me) = Ref.monthRange(TODAY)

        val sql = db.txnDao().observeMonthIncomeExpense(ledger2, s, e, excluded2.ifEmpty { setOf(-1L) }).first()
        val bCounted = all2.filter { it.dateMillis in ms until me && Ref.countsInStats(it) && Ref.inStatsAccountScope(it, bExcluded2) }
        val bIncome = bCounted.filter { it.type == TxnType.INCOME }.sumOf { Ref.effectiveCents(it) }
        val bExpense = bCounted.filter { it.type == TxnType.EXPENSE }.sumOf { Ref.effectiveCents(it) }
        assertEquals("ledger2 本月收入", bIncome, sql.incomeCents)
        assertEquals("ledger2 本月支出", bExpense, sql.expenseCents)
        // #45 收入 500000；#43/#44 支出 13000（#48 在排除账户不算）
        assertEquals("ledger2 本月收入 = #45", 500000L, bIncome)
        assertEquals("ledger2 本月支出 = #43 + #44", 13000L, bExpense)

        // ledger2 的月度预算已用也独立对拍
        val sqlBudget = db.txnDao().observeBudgetUsedCents(ledger2, s, e, excluded2.ifEmpty { setOf(-1L) }).first()
        val bBudget = all2.filter { it.dateMillis in ms until me && Ref.countsInBudget(it) && Ref.inStatsAccountScope(it, bExcluded2) }
            .sumOf { Ref.effectiveCents(it) }
        assertEquals("ledger2 预算已用", bBudget, sqlBudget)
        assertEquals("ledger2 预算已用 = #43 + #44", 13000L, bBudget)

        // ledger2 的余额也独立对拍
        val accounts2 = db.accountDao().byLedger(ledger2)
        val aBalances2 = repo.balancesOf(accounts2, all2)
        val bDeltas2 = Ref.accountDeltas(all2)
        aBalances2.forEach { ab ->
            assertEquals("ledger2 账户「${ab.account.name}」余额", ab.account.initialBalanceCents + (bDeltas2[ab.account.id] ?: 0L), ab.balanceCents)
        }
    }

    // ================= B 套：参考实现（纯 Kotlin 手写口径语义） =================

    /**
     * B 套（参考实现）：纯 Kotlin 集合操作手写的口径语义。
     *
     * ⚠️ 严禁调用 [Repository] 的任何函数（含 companion）——否则就是自证。
     * 这里逐条复刻 Repository.kt 里读到的当前正确语义（见类顶部「复刻的口径语义摘要」），
     * 日期边界也自己用 java.time 算（不借 DateFmt），与 A 套完全独立。
     */
    private object Ref {

        private val zone: ZoneId get() = ZoneId.systemDefault()

        /** LocalDate -> 当天 0 点的 epoch 毫秒（与 DateFmt.toMillis 等价，独立实现） */
        fun millisOf(date: LocalDate): Long =
            date.atStartOfDay(zone).toInstant().toEpochMilli()

        /** 某天的 [start, end) 毫秒区间 */
        fun dayRange(date: LocalDate): Pair<Long, Long> =
            millisOf(date) to millisOf(date.plusDays(1))

        /** 某自然月的 [start, end) 毫秒区间 */
        fun monthRange(anchor: LocalDate): Pair<Long, Long> {
            val start = anchor.withDayOfMonth(1)
            return millisOf(start) to millisOf(start.plusMonths(1))
        }

        /** 实计金额 = 金额 − 退款 − 优惠 + 手续费（下限 0）；互转 / 借贷返回原金额 */
        fun effectiveCents(t: Txn): Long = when (t.type) {
            TxnType.EXPENSE, TxnType.INCOME ->
                (t.amountCents - t.refundCents - t.discountCents + t.feeCents).coerceAtLeast(0L)
            else -> t.amountCents
        }

        /** 计入收入 / 支出统计：只算收支两类，且没勾「不计入收支」 */
        fun countsInStats(t: Txn): Boolean =
            (t.type == TxnType.EXPENSE || t.type == TxnType.INCOME) && !t.excludeFromStats

        /** 计入当月预算：只算支出，且没勾「不计入预算」（与「不计入收支」无关） */
        fun countsInBudget(t: Txn): Boolean = t.type == TxnType.EXPENSE && !t.excludeFromBudget

        /** 自定义预算的支出口径 / 收入口径 */
        fun countsInBudget(t: Txn, direction: Int): Boolean =
            if (direction == BudgetDirection.INCOME) {
                t.type == TxnType.INCOME && !t.excludeFromBudget
            } else {
                countsInBudget(t)
            }

        /** 统计口径的账户范围：主账户或对方账户命中「不计入净资产」账户时整笔排除 */
        fun inStatsAccountScope(t: Txn, excludedIds: Set<Long>): Boolean {
            if (excludedIds.isEmpty()) return true
            if (t.accountId in excludedIds) return false
            return when (t.type) {
                TxnType.TRANSFER, TxnType.LEND_BORROW -> t.transferToAccountId !in excludedIds
                else -> true
            }
        }

        /** 借贷两端：LEND = (资金账户, 借贷账户)；BORROW = (借贷账户, 资金账户) */
        fun lendEndpoints(t: Txn): Pair<Long, Long> =
            if (t.direction == Direction.LEND) t.accountId to t.transferToAccountId
            else t.transferToAccountId to t.accountId

        /** 每笔流水对各账户余额的净影响 */
        fun accountDeltas(txns: List<Txn>): Map<Long, Long> {
            val delta = mutableMapOf<Long, Long>()
            txns.forEach { t ->
                when (t.type) {
                    TxnType.EXPENSE -> delta[t.accountId] = (delta[t.accountId] ?: 0L) - effectiveCents(t)
                    TxnType.INCOME -> delta[t.accountId] = (delta[t.accountId] ?: 0L) + effectiveCents(t)
                    TxnType.TRANSFER -> {
                        delta[t.accountId] = (delta[t.accountId] ?: 0L) - t.amountCents
                        delta[t.transferToAccountId] = (delta[t.transferToAccountId] ?: 0L) + t.amountCents
                    }
                    TxnType.LEND_BORROW -> {
                        val (from, to) = lendEndpoints(t)
                        if (from != 0L) delta[from] = (delta[from] ?: 0L) - t.amountCents
                        if (to != 0L) delta[to] = (delta[to] ?: 0L) + t.amountCents
                    }
                }
            }
            return delta
        }

        /** 这笔借贷是不是还款流水（settledTxnId > 0） */
        fun isSettlement(t: Txn): Boolean = t.type == TxnType.LEND_BORROW && t.settledTxnId > 0L

        /** 借贷汇总：待收回 / 待归还（还款流水冲减，下限 0） */
        fun lendTotals(txns: List<Txn>): Pair<Long, Long> {
            var receivable = 0L
            var payable = 0L
            txns.forEach { t ->
                if (t.type != TxnType.LEND_BORROW) return@forEach
                if (isSettlement(t)) {
                    // 还款流水 direction 与原借贷相反：LEND = 还出（冲减应付），BORROW = 收回（冲减应收）
                    if (t.direction == Direction.LEND) payable -= t.amountCents
                    else receivable -= t.amountCents
                } else if (t.direction == Direction.LEND) {
                    receivable += t.amountCents
                } else {
                    payable += t.amountCents
                }
            }
            return receivable.coerceAtLeast(0L) to payable.coerceAtLeast(0L)
        }

        /** 预算区间含首尾两天（按本地日期比较，跨时区 / 闰月都稳） */
        fun inBudgetRange(budget: Budget, dateMillis: Long): Boolean {
            val d = Instant.ofEpochMilli(dateMillis).atZone(zone).toLocalDate()
            val s = Instant.ofEpochMilli(budget.startMillis).atZone(zone).toLocalDate()
            val e = Instant.ofEpochMilli(budget.endMillis).atZone(zone).toLocalDate()
            return !d.isBefore(s) && !d.isAfter(e)
        }

        /** 关键词命中（忽略大小写，匹配备注或对方）；空 = 不限 */
        fun matchesBudgetKeyword(keyword: String, t: Txn): Boolean =
            keyword.isBlank() ||
                t.note.contains(keyword, ignoreCase = true) ||
                t.counterparty.contains(keyword, ignoreCase = true)

        /** 一笔账单是否算进这个预算：区间 + 绑定条件（分类 / 账户精确 id）+ 口径 + 账户范围 */
        fun matchesBudget(budget: Budget, t: Txn, excludedIds: Set<Long>): Boolean =
            countsInBudget(t, budget.direction) &&
                inBudgetRange(budget, t.dateMillis) &&
                (budget.categoryId == 0L || t.categoryId == budget.categoryId) &&
                (budget.accountId == 0L || t.accountId == budget.accountId) &&
                matchesBudgetKeyword(budget.keyword, t) &&
                inStatsAccountScope(t, excludedIds)

        /** 预算已用 = 匹配账单的实计金额之和 */
        fun budgetUsedCents(budget: Budget, txns: List<Txn>, excludedIds: Set<Long>): Long =
            txns.filter { matchesBudget(budget, it, excludedIds) }.sumOf { effectiveCents(it) }
    }
}
