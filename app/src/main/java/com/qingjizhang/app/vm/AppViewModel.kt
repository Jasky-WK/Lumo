package com.qingjizhang.app.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.autoIconKeyFor
import com.qingjizhang.app.ui.components.DonutSlice
import com.qingjizhang.app.ui.theme.Palette
import com.qingjizhang.app.ui.theme.ThemeColor
import com.qingjizhang.app.ui.theme.ThemeMode
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReportCat
import com.qingjizhang.app.util.ReportMonth
import com.qingjizhang.app.util.ReportStats
import com.qingjizhang.app.util.ReceiptStore
import com.qingjizhang.app.widget.WidgetRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** 总览页的统计结果 */
data class PeriodStats(
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
    val balanceCents: Long = 0L,
    val txnCount: Int = 0,
    val slices: List<DonutSlice> = emptyList(),
    val trendValues: List<Long> = emptyList(),
    val trendLabels: List<String> = emptyList(),
    val trendTitle: String = "支出趋势",
    val legend: String = "",
    /** 图表口径文案（F15）：支出 / 收入 */
    val flowLabel: String = "支出",
    /** 图表口径对应的总金额（饼图中心与图例分母） */
    val flowCents: Long = 0L
)

data class BackupUi(val name: String, val sizeText: String, val timeText: String, val file: File)

/**
 * 自定义预算的进度（N30）：由后台线程算好后直接给界面用。
 * 已用金额的口径见 Repository.matchesBudget（与月度预算同一套）。
 */
data class BudgetProgress(
    val budget: Budget,
    val usedCents: Long,
    /** 0 未开始，1 进行中，2 已结束 */
    val phase: Int
) {
    val remainingCents: Long get() = (budget.amountCents - usedCents).coerceAtLeast(0L)
    val overspentCents: Long get() = (usedCents - budget.amountCents).coerceAtLeast(0L)
    val overspent: Boolean get() = usedCents > budget.amountCents

    /** 进度条比例（超支封顶 100%） */
    val ratio: Float
        get() = if (budget.amountCents <= 0L) 0f
        else (usedCents.toFloat() / budget.amountCents).coerceIn(0f, 1f)

    val phaseLabel: String
        get() = when (phase) {
            0 -> "未开始"
            2 -> "已结束"
            else -> "进行中"
        }
}

/** 净资产 = 总资产 - 总负债（只统计「计入净资产」的账户） */
data class NetWorth(
    val assetsCents: Long = 0L,
    val debtsCents: Long = 0L,
    /** 被排除在净资产之外的账户数 */
    val excludedCount: Int = 0
) {
    val netCents: Long get() = assetsCents - debtsCents
}

/** 搜索结果：命中的账单 + 命中原因（金额 / 备注 / 类型 / 分类 / 账户） */
data class SearchHit(val txn: Txn, val reasons: List<String>)

/** 日历里的一格：某一天的收支情况 */
data class DayStat(
    val day: Int,
    val incomeCents: Long = 0L,
    val expenseCents: Long = 0L,
    /** 互转 / 借贷的笔数（不计入收支，只在格子里提示有几笔） */
    val otherCount: Int = 0,
    /** 当天账单总笔数 */
    val count: Int = 0
) {
    val hasData: Boolean get() = count > 0
}

/** 导入结果：告诉用户到底进来了几笔、哪几行没进来、为什么 */
data class ImportReport(val success: Int, val skipped: Int, val details: List<String>)

/** 报表区间（N9） */

/** 通用账单明细页的筛选规格（首页更多明细 / 总览各栏目下钻都用它） */
data class TxnListSpec(
    /** v2.8.0：分类名兜底过滤用（id 反查失败时按名字匹配，保证下钻一定有结果） */
    val categoryName: String = "",
    val title: String = "本月账单",
    val subtitle: String = "",
    /** 0 全部 1 支出 2 收入 3 互转 */
    val filter: Int = 0,
    val categoryId: Long = 0L,
    /** 按账户过滤（F17）：>0 时只显示该账户的账单（图例「账户」口径下钻用） */
    val accountId: Long = 0L,
    val lendOnly: Boolean = false,
    /** N54：按标签名过滤（标签账单页用）；空 = 不按标签过滤 */
    val tagName: String = "",
    val monthStart: Long = 0L,
    val monthEnd: Long = Long.MAX_VALUE
)

private data class StatsInput(
    val txns: List<Txn>,
    val cats: List<Category>,
    val accs: List<Account>,
    val kind: Int,
    val anchor: LocalDate,
    val mode: Int,
    val rangeStart: LocalDate = LocalDate.now().withDayOfMonth(1),
    val rangeEnd: LocalDate = LocalDate.now(),
    /** 图表口径（F15）：0 = 支出，1 = 收入 */
    val flow: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(internal val app: Application) : AndroidViewModel(app) {

    /**
     * 数据库实例（S1：启动优化）。
     * 初始为 null——开库（升级首启要跑完整迁移链）放到 IO 线程做，不再占主线程；
     * 启动屏通过 [AppDatabase.isReady] 等它就绪。所有查询 Flow 都挂在 [dbReady] 上，
     * 库就绪前只发初始空值，就绪后自动订阅。
     */
    internal val dbFlow = MutableStateFlow<AppDatabase?>(null)

    /** 库就绪后的实例流（查询 Flow 统一挂这里） */
    private val dbReady: Flow<AppDatabase> = dbFlow.filterNotNull()

    /** 命令类调用（写库 / 一次性查询）取库：已就绪直接用，没就绪就地开（这些路径都在用户操作后） */
    internal fun requireDb(): AppDatabase = dbFlow.value ?: AppDatabase.get(app)

    internal val _currentLedgerId = MutableStateFlow(0L)
    val currentLedgerId: StateFlow<Long> = _currentLedgerId

    internal val _snack = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snack: SharedFlow<String> = _snack

    internal val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    internal val _periodKind = MutableStateFlow(1) // 0 日 1 月 2 年 3 自定义区间
    val periodKind: StateFlow<Int> = _periodKind

    internal val _anchor = MutableStateFlow(LocalDate.now())
    val anchor: StateFlow<LocalDate> = _anchor

    // 自定义区间（periodKind == 3 时生效，含首尾两天）
    internal val _rangeStart = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val rangeStart: StateFlow<LocalDate> = _rangeStart

    internal val _rangeEnd = MutableStateFlow(LocalDate.now())
    val rangeEnd: StateFlow<LocalDate> = _rangeEnd

    internal val _catMode = MutableStateFlow(0) // 0 大类 1 小类 2 账户
    val catMode: StateFlow<Int> = _catMode

    /** 总览图表口径（F15）：0 = 支出（分类饼图 / 趋势折线都按支出），1 = 收入 */
    internal val _flowMode = MutableStateFlow(0)
    val flowMode: StateFlow<Int> = _flowMode

    internal val _backups = MutableStateFlow<List<BackupUi>>(emptyList())
    val backups: StateFlow<List<BackupUi>> = _backups

    internal val _lastBackupText = MutableStateFlow("")
    val lastBackupText: StateFlow<String> = _lastBackupText

    /** v2.9.0：公开目录里的「每月自动备份」列表（Documents/Lumo/自动备份） */
    internal val _autoBackups = MutableStateFlow<List<AutoBackup.Entry>>(emptyList())
    val autoBackups: StateFlow<List<AutoBackup.Entry>> = _autoBackups

    /** 账户页是否临时显示隐藏账户（长按总览卡切换） */
    internal val _showHiddenAccounts = MutableStateFlow(false)
    val showHiddenAccounts: StateFlow<Boolean> = _showHiddenAccounts

    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    /** CSV 导入结果（导入完弹一个明细，说明哪几行没进来） */
    internal val _importReport = MutableStateFlow<ImportReport?>(null)
    val importReport: StateFlow<ImportReport?> = _importReport


    /** 通用账单明细页的规格 */
    internal val _txnListSpec = MutableStateFlow(TxnListSpec())
    val txnListSpec: StateFlow<TxnListSpec> = _txnListSpec


    internal fun repo(): Repository = Repository(requireDb())

    /** 用户是否已经自己选过账本：异步初始化不允许覆盖用户的选择 */
    internal var ledgerPickedByUser = false

    init {
        bootLog("AppViewModel 初始化开始")
        viewModelScope.launch {
            runCatching {
                // S1：开库放 IO 线程（升级后的首次启动要跑完整迁移链，不能占主线程），
                // 启动屏靠 AppDatabase.isReady 等着；就绪前界面展示空态，不卡顿
                val db = withContext(Dispatchers.IO) { AppDatabase.get(app) }
                dbFlow.value = db
                Seed.initIfEmpty(db)
                val first = db.ledgerDao().all().firstOrNull()
                // 初始化跑在协程里（Seed 要读写数据库），期间用户可能已经点了别的账本，
                // 这时不能再把账本改回默认账本，否则看起来就是「点了没反应」
                if (first != null && !ledgerPickedByUser) {
                    _currentLedgerId.value = prefs.getLong("current_ledger_id", 0L)
                        .takeIf { id -> id > 0L && db.ledgerDao().byId(id) != null }
                        ?: first.id
                    prefs.edit().putLong("current_ledger_id", _currentLedgerId.value).apply()
                }
                val n = RecurringEngine.catchUp(db)
                if (n > 0) _snack.emit("已自动补记 $n 笔周期账单")
            }
            refreshBackups()
        }
        bootLog("AppViewModel 初始化完成")
    }

    /**
     * 启动后台监听：周期提醒重排 + 桌面小组件刷新。
     *
     * ⚠️ 必须由界面首帧之后调用（AppRoot 里），**不能放在 init 块里**：
     * viewModelScope 用的是 Main.immediate，在主线程会同步执行协程体，
     * 而此时 rules / txns 这些声明在后面的 StateFlow 还没赋值，会直接 NPE。
     */
    private var observersStarted = false

    fun startBackgroundObservers() {
        if (observersStarted) return
        observersStarted = true
        bootLog("启动后台监听（提醒重排 + 小组件刷新）")
        // 周期提醒（N8）：规则有变动就重排下一次本地提醒
        viewModelScope.launch {
            rules.collect { list -> runCatching { RecurringReminder.schedule(app, list) } }
        }
        // 信用卡还款提醒（N52）：账户（还款日 / 提醒开关）有变动就重排下一次本地提醒。
        // N59：账本软删除后，它名下账户的还款提醒也停（过滤到存活账本）
        viewModelScope.launch {
            combine(
                dbReady.flatMapLatest { it.accountDao().observeAll() },
                ledgers
            ) { accs, ls ->
                val live = ls.map { it.id }.toSet()
                accs.filter { it.ledgerIds.any { id -> id in live } }
            }.collect { list -> runCatching { CreditReminder.schedule(app, list) } }
        }
        // 预算超支提醒（N57）：每次启动排一次每日检查（开关关着时不排）
        runCatching { BudgetReminder.scheduleNext(app) }
        // v2.9.0：每月自动备份检查（距上次 ≥30 天才真正执行，静默、失败不打扰）
        viewModelScope.launch { runCatching { maybeAutoBackup() } }
        // 桌面小组件（N10）：账单一变就顺手刷新今日数据
        viewModelScope.launch {
            txns.collect { runCatching { WidgetRefresh.refresh(app) } }
        }
    }

    /** 自检版打点（正式发版时 BootLog 内部会直接跳过，不写文件） */
    internal fun bootLog(step: String) = com.qingjizhang.app.util.BootLog.log(app, "VM: " + step)

    /**
     * 统计聚合统一放到后台线程算（N16）。
     *
     * 账单量大时，全量遍历 + 分组 + 排序如果留在主线程（viewModelScope 用的是 Main.immediate），
     * 一进报表/总览或切一次周期就会明显卡顿。StateFlow 的值可以从任意线程发出，
     * Compose 侧照常在主线程收集，口径与结果完全不变。
     */
    private fun <T> Flow<T>.stateInShared(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, initial)

    /**
     * S3：非首屏 Flow 用「有人看才算」——5 秒宽限，切 Tab / 弹面板都不会触发重算，
     * 但进了回收站 / 报表 / 搜索这些页面之前不占启动时的 IO 与 CPU。
     */
    private fun <T> Flow<T>.stateInLazy(initial: T): StateFlow<T> =
        flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    // ---------------- 周期提醒（N8） ----------------

    internal val _reminderEnabled = MutableStateFlow(RecurringReminder.isEnabled(app))
    val reminderEnabled: StateFlow<Boolean> = _reminderEnabled


    // ---------------- 预算超支提醒（N57） ----------------

    internal val _budgetRemindEnabled = MutableStateFlow(BudgetReminder.isEnabled(app))
    val budgetRemindEnabled: StateFlow<Boolean> = _budgetRemindEnabled

    /** 预算预警线（百分比，默认 80）：到达它就先提醒一次，超支再提醒一次（各自每月一条） */
    private val _warnPercent = MutableStateFlow(BudgetReminder.warnPercent(app))
    val warnPercent: StateFlow<Int> = _warnPercent

    fun setWarnPercent(percent: Int) {
        BudgetReminder.setWarnPercent(app, percent)
        _warnPercent.value = percent.coerceIn(50, 100)
        viewModelScope.launch { _snack.emit("预算预警线已设为 ${_warnPercent.value}%") }
    }


    // ---------------- 基础数据 ----------------

    val ledgers: StateFlow<List<Ledger>> =
        dbReady.flatMapLatest { it.ledgerDao().observeAll() }
            .stateInShared(emptyList())

    private val key = combine(dbReady, _currentLedgerId) { db, id -> db to id }

    /** 当前账本能用到的账户（账户可跨账本共用，P2-2 起走 account_ledgers 关系表） */
    val accounts: StateFlow<List<Account>> =
        key.flatMapLatest { (db, id) -> db.accountDao().observeForLedger(id) }
            .stateInShared(emptyList())

    /** 账户页显示用：默认过滤掉隐藏账户 */
    val visibleAccounts: StateFlow<List<Account>> =
        combine(accounts, _showHiddenAccounts) { list, show ->
            if (show) list else list.filter { !it.hidden }
        }.stateInLazy(emptyList())

    val categories: StateFlow<List<Category>> =
        key.flatMapLatest { (db, id) -> db.categoryDao().observeByLedger(id) }
            .stateInShared(emptyList())

    /** 当前账本的账户类型（N19 自定义分组）：切换账本时随之切换 */
    val accountTypes: StateFlow<List<AccountTypeEntity>> =
        key.flatMapLatest { (db, id) ->
            db.accountTypeDao().observeByLedger(id).map { list ->
                // 兜底：一个类型都没有（老库升级异常 / 从旧备份恢复）时补上预设类型，保证界面不空
                if (list.isEmpty() && id > 0L) {
                    db.accountTypeDao().insertAll(Seed.presetAccountTypes(id))
                }
                list
            }
        }.stateInLazy(emptyList())

    /** 当前账本的互转类型（N26 自定义互转胶囊）：切换账本时随之切换 */
    val transferKinds: StateFlow<List<TransferKindEntity>> =
        key.flatMapLatest { (db, id) ->
            db.transferKindDao().observeByLedger(id).map { list ->
                // 兜底：一个都没有（老库升级异常 / 从旧备份恢复）时补上预设，保证胶囊不空
                if (list.isEmpty() && id > 0L) {
                    db.transferKindDao().insertAll(Seed.presetTransferKinds(id))
                }
                list
            }
        }.stateInLazy(emptyList())

    /** 当前账本的自定义标签（N38）：不作预设播种，用户用到才新建 */
    val tags: StateFlow<List<TagEntity>> =
        key.flatMapLatest { (db, id) -> db.tagDao().observeByLedger(id) }
            .stateInLazy(emptyList())

    /**
     * 当前账本的应收应付（N44）：**不计入余额、不进主页账单流**（余额只统计 txn 表），
     * 只在上方「记账本」入口内的应收应付模块里展示。
     */
    val arAps: StateFlow<List<ArAp>> =
        key.flatMapLatest { (db, id) -> db.arApDao().observeByLedger(id) }
            .stateInLazy(emptyList())

    /**
     * 当前账本「账单 id -> 标签名」映射（N38）：列表 / 详情展示用。
     * 由数据库一次 JOIN 取回后按 txnId 分组，避免列表逐行查询（与借贷未结金额同一思路）。
     */
    val txnTagMap: StateFlow<Map<Long, List<String>>> =
        key.flatMapLatest { (db, id) -> db.tagDao().observeTagNamesByLedger(id) }
            .map { list -> list.groupBy({ it.txnId }, { it.name }) }
            .stateInShared(emptyMap())

    /**
     * 当前查看的账单的小票照片（N56）：详情页进入时用 [openReceipts] 设好 txnId，
     * 增删照片后列表实时刷新；没有小票时自然为空列表。
     */
    internal val _receiptTxnId = MutableStateFlow(0L)
    val receiptPhotos: StateFlow<List<ReceiptPhoto>> =
        _receiptTxnId.flatMapLatest { id ->
            if (id <= 0L) flowOf(emptyList())
            else dbReady.flatMapLatest { it.receiptPhotoDao().observeByTxn(id) }
        }.stateInLazy(emptyList())


    val txns: StateFlow<List<Txn>> =
        key.flatMapLatest { (db, id) -> db.txnDao().observeByLedger(id) }
            // 内容没变就不往下走，避免整页跟着重算（Room 失效回调可能重复触发）
            .distinctUntilChanged()
            .stateInShared(emptyList())

    /**
     * 备注记忆（N22）：当前账本里用过的备注，去重后按「最近使用 → 使用次数」排序。
     * 直接由已观测的账单列表聚合（账单表备注列去重），不额外建表，也就没有跨账本串数据的可能。
     */
    val noteHistory: StateFlow<List<String>> =
        txns.map { list ->
            list.asSequence()
                .filter { it.note.isNotBlank() }
                .groupBy({ it.note }, { it.dateMillis })
                .map { (note, dates) -> Triple(note, dates.max(), dates.size) }
                .sortedWith(
                    compareByDescending<Triple<String, Long, Int>> { it.second }
                        .thenByDescending { it.third }
                )
                .map { it.first }
                .toList()
        }.stateInLazy(emptyList())

    val rules: StateFlow<List<RecurringRule>> =
        key.flatMapLatest { (db, id) -> db.ruleDao().observeByLedger(id) }
            .stateInShared(emptyList())

    val currentLedger: StateFlow<Ledger?> =
        combine(ledgers, _currentLedgerId) { list, id -> list.firstOrNull { it.id == id } }
            .stateInShared(null)

    val balances: StateFlow<List<AccountBalance>> =
        combine(accounts, txns) { a, t -> repo().balancesOf(a, t) }
            .stateInLazy(emptyList())

    val netWorth: StateFlow<NetWorth> =
        combine(balances, accounts) { list, accs ->
            // N20：净资产 / 总资产 / 总负债只统计「计入净资产」的账户（余额展示不受影响）
            val counted = accs.filter { it.countInNetWorth }.map { it.id }.toSet()
            val included = list.filter { counted.contains(it.account.id) }
            NetWorth(
                assetsCents = included.filter { it.balanceCents > 0 }.sumOf { it.balanceCents },
                debtsCents = included.filter { it.balanceCents < 0 }.sumOf { -it.balanceCents },
                excludedCount = list.size - included.size
            )
        }.stateInLazy(NetWorth())

    // ---------------- 统计 ----------------

    /**
     * N20：当前账本下「不计入净资产」的账户 id 集合。
     * 这些账户的余额与流水照常展示、可以记账、可以看流水，但不参与任何统计口径。
     * 界面层自己做的汇总（首页每日表头、明细页摘要、小票、桌面小组件）也用它过滤。
     */
    val statsExcludedAccountIds: StateFlow<Set<Long>> =
        accounts.map { Repository.statsExcludedAccountIds(it) }
            .stateInShared(emptySet())

    /**
     * N60：统计页的取数窗口。
     * 趋势图最多回看 5 年（逐年）/ 6 个月（逐月）/ 7 天（逐日），所以窗口 =
     * 「趋势需要的最早一天」到「当前周期的结束（不含）」；窗口外的账单统计页用不到，
     * 不必每次变动都全量重算。区间与 [buildStats] 里各周期用的 dayRange /
     * monthRange / yearRange 严格对齐。
     */
    private val statsWindow: Flow<Pair<Long, Long>> =
        combine(_periodKind, _anchor, _rangeStart, _rangeEnd) { kind, anchor, rs, re ->
            val start: LocalDate = when (kind) {
                0 -> anchor.minusDays(6)
                1 -> anchor.minusMonths(5).withDayOfMonth(1)
                2 -> LocalDate.of(anchor.year - 4, 1, 1)
                else -> rs
            }
            val endExclusive: LocalDate = when (kind) {
                0 -> anchor.plusDays(1)
                1 -> anchor.withDayOfMonth(1).plusMonths(1)
                2 -> LocalDate.of(anchor.year + 1, 1, 1)
                else -> re.plusDays(1)
            }
            DateFmt.toMillis(start) to DateFmt.toMillis(endExclusive)
        }

    /**
     * N20 + N60：统计页用的账单——按窗口从库里取（不再全量读内存），
     * 再按「不计入净资产」账户过滤（口径定义见 [statsExcludedAccountIds] 的注释）。
     * 账户余额（balances）、账单列表、编辑删除等仍用完整的 [txns]，不受影响。
     */
    private val statsTxns: StateFlow<List<Txn>> =
        combine(statsWindow, _currentLedgerId, statsExcludedAccountIds) { window, id, excluded ->
            Triple(window.first, window.second, id) to excluded
        }.flatMapLatest { (window, excluded) ->
            dbReady.flatMapLatest { db ->
                db.txnDao().observeByLedgerBetween(window.third, window.first, window.second)
                    .map { list ->
                        if (excluded.isEmpty()) list
                        else list.filter { Repository.inStatsAccountScope(it, excluded) }
                    }
            }
        }.stateInLazy(emptyList())

    /**
     * 当月收支（首页用）。N60：改 SQL 聚合（TxnDao.observeMonthIncomeExpense），
     * 口径与原来的 Kotlin 实现逐条对齐：只算收入 / 支出、排除「不计入收支」、
     * 按实计金额（金额−退款−优惠+手续费，下限 0）、排除「不计入净资产」账户的流水。
     * 勾了「不计入收支」的账单不计入，但它仍会改变账户余额。
     */
    val monthStats: StateFlow<Pair<Long, Long>> =
        combine(accounts, _anchor, _currentLedgerId) { accs, a, id -> Triple(accs, a, id) }
            .flatMapLatest { (accs, a, id) ->
                val (s, e) = DateFmt.monthRange(a)
                // 空集合传哨兵 -1：NOT IN (-1) 恒真，等价于「没有排除账户」
                val excluded = Repository.statsExcludedAccountIds(accs)
                    .let { if (it.isEmpty()) setOf(-1L) else it }
                dbReady.flatMapLatest {
                    it.txnDao().observeMonthIncomeExpense(id, s, e, excluded)
                        .map { sum -> sum.incomeCents to sum.expenseCents }
                }
            }.stateInShared(0L to 0L)

    /**
     * 当月预算口径的支出（不含「不计入预算」的账单）。
     * 预算不含这类账单，但收入 / 支出统计仍然算它。N60：改 SQL 聚合。
     */
    val budgetUsedCents: StateFlow<Long> =
        combine(accounts, _anchor, _currentLedgerId) { accs, a, id -> Triple(accs, a, id) }
            .flatMapLatest { (accs, a, id) ->
                val (s, e) = DateFmt.monthRange(a)
                val excluded = Repository.statsExcludedAccountIds(accs)
                    .let { if (it.isEmpty()) setOf(-1L) else it }
                dbReady.flatMapLatest { it.txnDao().observeBudgetUsedCents(id, s, e, excluded) }
            }.stateInShared(0L)

    // ---------------- 自定义预算（N30） ----------------

    /** 当前账本的自定义预算定义（含已暂停的） */
    val budgets: StateFlow<List<Budget>> =
        key.flatMapLatest { (db, id) -> db.budgetDao().observeByLedger(id) }
            .stateInShared(emptyList())

    /**
     * 自定义预算的进度：已用金额走后台线程算（账单量大时也不会卡主线程）。
     *
     * 已用口径与月度预算完全一致：区间内 + 匹配绑定条件（分类 / 账户 / 关键词）
     * + 口径（支出用 countsInBudget，收入用对称判定）+ 实计金额 effectiveCents
     * + N20「不计入净资产」账户的流水不计入（inStatsAccountScope）。
     */
    val budgetProgress: StateFlow<List<BudgetProgress>> =
        combine(budgets, txns, statsExcludedAccountIds) { list, all, excluded ->
            val today = LocalDate.now()
            list.map { b ->
                BudgetProgress(
                    budget = b,
                    usedCents = Repository.budgetUsedCents(b, all, excluded),
                    phase = Repository.budgetPhase(b, today)
                )
            }
        }.stateInShared(emptyList())


    private val statsInput =
        combine(
            combine(statsTxns, categories, accounts) { t, c, a -> Triple(t, c, a) },
            combine(_periodKind, _anchor, _catMode) { k, a, m -> Triple(k, a, m) },
            combine(_rangeStart, _rangeEnd, _flowMode) { s, e, f -> Triple(s, e, f) }
        ) { (t, c, a), (k, an, m), (rs, re, fl) -> StatsInput(t, c, a, k, an, m, rs, re, fl) }

    val periodStats: StateFlow<PeriodStats> =
        statsInput.map { i ->
            buildStats(i.txns, i.cats, i.accs, i.kind, i.anchor, i.mode, i.rangeStart, i.rangeEnd, i.flow)
        }.stateInLazy(PeriodStats())

    private fun buildStats(
        list: List<Txn>,
        cats: List<Category>,
        accs: List<Account>,
        kind: Int,
        anchor: LocalDate,
        catMode: Int,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
        flow: Int
    ): PeriodStats {
        // F15：分类饼图与趋势折线都按当前口径（支出 / 收入）取数，其它口径（结余卡、日均）不受影响
        val flowType = if (flow == 1) TxnType.INCOME else TxnType.EXPENSE
        val flowLabel = if (flow == 1) "收入" else "支出"
        val range: Pair<Long, Long> = when (kind) {
            0 -> DateFmt.dayRange(anchor)
            1 -> DateFmt.monthRange(anchor)
            3 -> DateFmt.toMillis(rangeStart) to DateFmt.toMillis(rangeEnd.plusDays(1))
            else -> DateFmt.yearRange(anchor)
        }
        val inRange = list.filter { it.dateMillis in range.first until range.second }
        // 会计口径：互转 / 借贷本来不计，「不计入收支」的账单也排除
        val stats = inRange.filter { Repository.countsInStats(it) }
        val income = stats.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) }
        val expense = stats.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) }
        val count = stats.size

        // 分类维度
        val parentOf = { id: Long -> cats.firstOrNull { it.id == id }?.parentId ?: 0L }
        val nameOf = { id: Long -> cats.firstOrNull { it.id == id }?.name ?: "未分类" }
        val grouped = mutableMapOf<String, Long>()
        // 每个桶记住它的真实 id（分类 id 或账户 id），供图例下钻精确过滤（F17）
        val idOfBucket = mutableMapOf<String, Long>()
        stats.filter { it.type == flowType }.forEach { t ->
            val key = when (catMode) {
                0 -> parentOf(t.categoryId).takeIf { it != 0L }?.let { p -> nameOf(p) } ?: "未分类"
                1 -> if (t.categoryId != 0L) nameOf(t.categoryId) else "未分类"
                else -> accs.firstOrNull { it.id == t.accountId }?.name ?: "未知账户"
            }
            val bucketId = when (catMode) {
                0 -> parentOf(t.categoryId)
                1 -> t.categoryId
                else -> t.accountId
            }
            // 同一名称只记第一个 id（「未分类」这类 id = 0 的桶本来就不可下钻）
            if (bucketId != 0L && !idOfBucket.containsKey(key)) idOfBucket[key] = bucketId
            grouped[key] = (grouped[key] ?: 0L) + Repository.effectiveCents(t)
        }
        val entries = grouped.entries.sortedByDescending { it.value }
        val shown: List<Pair<String, Long>> = if (entries.size <= 6) {
            entries.map { it.key to it.value }
        } else {
            entries.take(5).map { it.key to it.value } +
                ("其他（${entries.size - 5} 类）" to entries.drop(5).sumOf { it.value })
        }
        val slices = shown.mapIndexed { i, p ->
            DonutSlice(
                label = p.first,
                valueCents = p.second,
                color = Palette[i % Palette.size],
                id = idOfBucket[p.first] ?: 0L,
                isAccount = catMode == 2
            )
        }

        // 趋势
        val trendValues: List<Long>
        val trendLabels: List<String>
        val trendTitle: String
        when (kind) {
            0 -> {
                trendTitle = "近 7 日$flowLabel"
                val days = (6 downTo 0).map { anchor.minusDays(it.toLong()) }
                trendLabels = days.map { it.dayOfMonth.toString() }
                trendValues = days.map { d ->
                    val r = DateFmt.dayRange(d)
                    list.filter { Repository.countsInStats(it) && it.type == flowType && it.dateMillis in r.first until r.second }
                        .sumOf { Repository.effectiveCents(it) }
                }
            }
            1 -> {
                trendTitle = "逐月$flowLabel"
                val months = (5 downTo 0).map { anchor.minusMonths(it.toLong()) }
                trendLabels = months.map { "${it.monthValue}月" }
                trendValues = months.map { m ->
                    val r = DateFmt.monthRange(m)
                    list.filter { Repository.countsInStats(it) && it.type == flowType && it.dateMillis in r.first until r.second }
                        .sumOf { Repository.effectiveCents(it) }
                }
            }
            2 -> {
                trendTitle = "逐年$flowLabel"
                val years = (4 downTo 0).map { anchor.year - it }
                trendLabels = years.map { it.toString() }
                trendValues = years.map { y ->
                    val r = DateFmt.yearRange(LocalDate.of(y, 1, 1))
                    list.filter { Repository.countsInStats(it) && it.type == flowType && it.dateMillis in r.first until r.second }
                        .sumOf { Repository.effectiveCents(it) }
                }
            }
            else -> {
                // 自定义区间：同一个月内按天看，跨月按当月逐月看
                val sameMonth = rangeStart.year == rangeEnd.year && rangeStart.monthValue == rangeEnd.monthValue
                if (sameMonth) {
                    trendTitle = "逐日$flowLabel"
                    val days = (0..java.time.temporal.ChronoUnit.DAYS.between(rangeStart, rangeEnd).toInt())
                        .map { rangeStart.plusDays(it.toLong()) }
                    trendLabels = days.map { it.dayOfMonth.toString() }
                    trendValues = days.map { d ->
                        val r = DateFmt.dayRange(d)
                        list.filter { Repository.countsInStats(it) && it.type == flowType && it.dateMillis in r.first until r.second }
                            .sumOf { Repository.effectiveCents(it) }
                    }
                } else {
                    trendTitle = "逐月$flowLabel"
                    val firstMonth = rangeStart.withDayOfMonth(1)
                    val lastMonth = rangeEnd.withDayOfMonth(1)
                    val months = (0..java.time.temporal.ChronoUnit.MONTHS.between(firstMonth, lastMonth).toInt())
                        .map { firstMonth.plusMonths(it.toLong()) }
                        .takeLast(12)
                    trendLabels = months.map { "${it.monthValue}月" }
                    trendValues = months.map { m ->
                        val r = DateFmt.monthRange(m)
                        list.filter { Repository.countsInStats(it) && it.type == flowType && it.dateMillis in r.first until r.second }
                            .sumOf { Repository.effectiveCents(it) }
                    }
                }
            }
        }
        val daysInPeriod = when (kind) {
            0 -> 1
            1 -> anchor.lengthOfMonth()
            3 -> (java.time.temporal.ChronoUnit.DAYS.between(rangeStart, rangeEnd).toInt() + 1).coerceAtLeast(1)
            else -> anchor.lengthOfYear()
        }
        val avg = if (count == 0) 0L else (expense.toDouble() / daysInPeriod).roundToLong()
        // 图表口径对应的金额（F15）：饼图与图例都看它，与上面恒定的收支字段区分开
        val flowCents = if (flow == 1) income else expense
        val flowCount = stats.count { it.type == flowType }

        return PeriodStats(
            incomeCents = income,
            expenseCents = expense,
            balanceCents = income - expense,
            txnCount = count,
            slices = slices,
            trendValues = trendValues,
            trendLabels = trendLabels,
            trendTitle = trendTitle,
            legend = "$flowLabel ${Money.text(flowCents)}，日均 ${Money.text(avg)}，共计 $flowCount 笔",
            flowLabel = flowLabel,
            flowCents = flowCents
        )
    }

    // ---------------- 收支日历 ----------------

    internal val _calAnchor = MutableStateFlow(LocalDate.now().withDayOfMonth(1))

    /** 日历当前显示的月份（每月 1 号） */
    val calAnchor: StateFlow<LocalDate> = _calAnchor

    /** 当月每天的收支（按日序排列，索引 0 = 1 号） */
    val calDays: StateFlow<List<DayStat>> =
        combine(statsTxns, _calAnchor) { list, anchor ->
            val (start, end) = DateFmt.monthRange(anchor)
            val arr = MutableList(anchor.lengthOfMonth()) { i ->
                DayStat(day = i + 1)
            }
            list.forEach { t ->
                if (t.dateMillis in start until end) {
                    val idx = DateFmt.dateTimeOf(t.dateMillis).dayOfMonth - 1
                    if (idx in arr.indices) {
                        val cur = arr[idx]
                        val counts = Repository.countsInStats(t)
                        arr[idx] = when {
                            counts && t.type == TxnType.INCOME -> cur.copy(
                                incomeCents = cur.incomeCents + Repository.effectiveCents(t),
                                count = cur.count + 1
                            )
                            counts && t.type == TxnType.EXPENSE -> cur.copy(
                                expenseCents = cur.expenseCents + Repository.effectiveCents(t),
                                count = cur.count + 1
                            )
                            // 互转 / 借贷 / 不计入收支的账单：只算「几笔」，不进收入支出
                            else -> cur.copy(otherCount = cur.otherCount + 1, count = cur.count + 1)
                        }
                    }
                }
            }
            arr
        }.stateInShared(emptyList())


    /**
     * 借贷汇总：待收回 / 待归还（已扣除收回 / 还出的部分）。
     *
     * F16：这里刻意用**完整的 [txns]**，不走 [statsTxns] —— 借贷汇总独立于「是否计入净资产」口径。
     * 否则只要把借贷账户（或资金账户）勾成「不计入净资产」，整笔借贷就会被排除，汇总恒为 0。
     * 也与借贷下钻列表（走完整 txns）保持一致。
     */
    val lendSummary: StateFlow<Pair<Long, Long>> =
        txns.map { list -> Repository.lendTotals(list.filter { it.type == TxnType.LEND_BORROW }) }
            .stateInLazy(0L to 0L)

    val pendingRecurring: StateFlow<Int> =
        rules.map { list ->
            val now = System.currentTimeMillis()
            list.filter { it.enabled }.sumOf { RecurringEngine.pendingCount(it, now) }
        }.stateInShared(0)

    // ---------------- 报表（N9） ----------------

    /**
     * v2.7.1：报表与总览**共用同一份时间范围**（periodKind / anchor / rangeStart / rangeEnd），
     * 所以两页切期是同步的，导出的图片也跟随这个范围。
     */
    val reportStats: StateFlow<ReportStats> =
        combine(
            statsTxns,
            categories,
            combine(_periodKind, _anchor, _rangeStart, _rangeEnd) { kind, a, rs, re ->
                PeriodKey(kind, a, rs, re)
            },
            currentLedger
        ) { list, cats, period, ledger ->
            buildReport(list, cats, period, ledger)
        }.stateInLazy(ReportStats("", "", emptyList(), 0, 0, 0, emptyList(), emptyList()))

    /** 报表用的周期快照（避免 combine 参数过多） */
    internal data class PeriodKey(
        val kind: Int,
        val anchor: java.time.LocalDate,
        val rangeStart: java.time.LocalDate,
        val rangeEnd: java.time.LocalDate
    )

    private fun buildReport(
        list: List<Txn>,
        cats: List<Category>,
        period: PeriodKey,
        ledger: Ledger?
    ): ReportStats {
        val (startMs, endMs) = when (period.kind) {
            0 -> DateFmt.dayRange(period.anchor)
            1 -> DateFmt.monthRange(period.anchor)
            3 -> DateFmt.toMillis(period.rangeStart) to DateFmt.toMillis(period.rangeEnd.plusDays(1))
            else -> DateFmt.yearRange(period.anchor)
        }
        val inRange = list.filter { it.dateMillis in startMs until endMs }
        val counted = inRange.filter { Repository.countsInStats(it) }
        val expenseOf = { ts: List<Txn> -> ts.filter { it.type == TxnType.EXPENSE }.sumOf { Repository.effectiveCents(it) } }
        val incomeOf = { ts: List<Txn> -> ts.filter { it.type == TxnType.INCOME }.sumOf { Repository.effectiveCents(it) } }

        // 逐月桶：只有「按年」需要 12 个月；其余范围合成一个桶（报表页已改为表格，这里只为兼容旧导出图）
        val months: List<ReportMonth> = if (period.kind == 2) {
            (1..12).map { m ->
                val s = DateFmt.toMillis(java.time.LocalDate.of(period.anchor.year, m, 1))
                val e = DateFmt.toMillis(java.time.LocalDate.of(period.anchor.year, m, 1).plusMonths(1))
                val sub = counted.filter { it.dateMillis in s until e }
                ReportMonth("${m}月", incomeOf(sub), expenseOf(sub), sub.size)
            }
        } else {
            listOf(ReportMonth(DateFmt.periodLabel(period.kind, period.anchor), incomeOf(counted), expenseOf(counted), counted.size))
        }

        val parentName = { id: Long ->
            val c = cats.firstOrNull { it.id == id }
            val pid = c?.parentId ?: 0L
            if (pid != 0L) cats.firstOrNull { it.id == pid }?.name ?: "未分类" else c?.name ?: "未分类"
        }
        val parentIdOf = { id: Long ->
            val c = cats.firstOrNull { it.id == id }
            val pid = c?.parentId ?: 0L
            if (pid != 0L) pid else c?.id ?: 0L
        }
        fun rank(type: TxnType): List<ReportCat> =
            counted.filter { it.type == type }
                .groupBy { parentName(it.categoryId) }
                .map { (name, ts) ->
                    ReportCat(
                        name = name,
                        cents = ts.sumOf { Repository.effectiveCents(it) },
                        count = ts.size,
                        id = ts.firstOrNull()?.let { parentIdOf(it.categoryId) } ?: 0L
                    )
                }
                .sortedByDescending { it.cents }

        // 等长的上一期（用于环比）
        val span = endMs - startMs
        val prevCounted = list.filter { it.dateMillis in (startMs - span) until startMs }
            .filter { Repository.countsInStats(it) }

        val maxExpense = counted.filter { it.type == TxnType.EXPENSE }
            .maxByOrNull { Repository.effectiveCents(it) }
        val activeDays = counted.map { DateFmt.dateOf(it.dateMillis) }.distinct().size

        // 预算只在「单个自然月」范围内有意义
        val oneMonth = period.kind == 1
        val budgetCents = if (oneMonth) (ledger?.monthlyBudgetCents ?: 0L) else 0L
        val budgetUsed = if (oneMonth) {
            counted.filter { it.type == TxnType.EXPENSE && Repository.countsInBudget(it) }
                .sumOf { Repository.effectiveCents(it) }
        } else 0L

        val title = if (period.kind == 3) {
            DateFmt.isoRange(period.rangeStart, period.rangeEnd)
        } else {
            DateFmt.periodLabel(period.kind, period.anchor)
        }

        // v2.9.0 报表三图：月内累计（只画到今天）、上月同期、分类逐月趋势
        val cum = if (period.kind == 1) {
            buildCumulativeSeries(list, startMs, endMs, System.currentTimeMillis())
        } else {
            CumulativeSeries(emptyList(), emptyList(), 0)
        }
        val prevSame: Pair<Long, Long> = if (period.kind == 1 && cum.dayCount > 0) {
            val prevFirst = period.anchor.withDayOfMonth(1).minusMonths(1)
            val prevStart = DateFmt.toMillis(prevFirst)
            val days = minOf(cum.dayCount, prevFirst.lengthOfMonth())
            val prevEnd = DateFmt.toMillis(prevFirst.withDayOfMonth(days).plusDays(1))
            val sub = counted.filter { it.dateMillis in prevStart until prevEnd }
            incomeOf(sub) to expenseOf(sub)
        } else {
            0L to 0L
        }
        val (trendLabels, trendMap) = buildCatTrend(list, cats, endMs)

        return ReportStats(
            ledgerName = ledger?.name ?: "账本",
            title = title,
            months = months,
            incomeCents = incomeOf(counted),
            expenseCents = expenseOf(counted),
            count = counted.size,
            expenseCats = rank(TxnType.EXPENSE),
            incomeCats = rank(TxnType.INCOME),
            startMs = startMs,
            endMs = endMs,
            prevIncomeCents = incomeOf(prevCounted),
            prevExpenseCents = expenseOf(prevCounted),
            maxExpenseCents = maxExpense?.let { Repository.effectiveCents(it) } ?: 0L,
            maxExpenseNote = maxExpense?.note.orEmpty(),
            activeDays = activeDays,
            budgetCents = budgetCents,
            budgetUsedCents = budgetUsed,
            cumIncome = cum.income,
            cumExpense = cum.expense,
            cumDayCount = cum.dayCount,
            prevSameIncomeCents = prevSame.first,
            prevSameExpenseCents = prevSame.second,
            trendMonths = trendLabels,
            catTrend = trendMap
        )
    }

    // ---------------- 本地设置（主题、账户记忆） ----------------

    // ---------------- 高级功能开关（v2.8.0） ----------------
    // 默认全开（不改变现有用法）；一句话记帐是 Beta，默认关，需在「设置 → 高级功能」手动打开。
    //
    // ⚠️ 声明顺序：_prefsTick / prefs 必须在下面 boolFlow 的调用之前完成初始化。
    // Kotlin 属性按声明顺序赋值，放在后面会让 combine(dbReady, _prefsTick) 收到 null，
    // 启动时 combine 内部为每个源 flow 起的收集协程对 null 调 collect，直接 NPE 闪退
    // （v2.8.1 修复：日志 DefaultDispatcher-worker-7 · CombineKt$combineInternal$2$1）。
    // InitOrderTest 会静态守住这个顺序，改动这里时注意别把它挪到后面。
    internal val _prefsTick = MutableStateFlow(0L)
    internal val prefs by lazy {
        app.getSharedPreferences("bookkeeping_prefs", android.content.Context.MODE_PRIVATE)
    }

    /**
     * v2.9.0：首启引导是否已完成。
     *
     * 区分新老用户的方式：全新安装的 prefs 是空的且没有 `onboarded` 键 → 显示引导；
     * 老安装已经有一堆设置但没有这个键 → 视为已完成，**不补看引导**（升级用户不该被打扰）。
     */
    internal val _onboarded = MutableStateFlow(prefs.getBoolean("onboarded", prefs.all.isNotEmpty()))
    val onboarded: StateFlow<Boolean> = _onboarded

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarded", true).apply()
        _onboarded.value = true
    }

    private fun boolFlow(key: String, default: Boolean): StateFlow<Boolean> =
        combine(dbReady, _prefsTick) { _, _ ->
            prefs.getBoolean(key, default)
        }.stateInLazy(default)

    val advArAp: StateFlow<Boolean> = boolFlow("adv_arap", true)
    val advLend: StateFlow<Boolean> = boolFlow("adv_lend", true)
    val advCustomBudget: StateFlow<Boolean> = boolFlow("adv_custom_budget", true)
    val advTags: StateFlow<Boolean> = boolFlow("adv_tags", true)
    val advReceipt: StateFlow<Boolean> = boolFlow("adv_receipt", true)
    val advQuickEntry: StateFlow<Boolean> = boolFlow("adv_quick_entry", false)

    /** v2.9.0：每月自动备份开关（默认开；数据安全是核心卖点，默认替用户兜住） */
    val autoBackupOn: StateFlow<Boolean> = boolFlow("auto_backup_on", true)

    fun setAdv(key: String, on: Boolean) {
        prefs.edit().putBoolean(key, on).apply()
        _prefsTick.value++
    }

    // ---------------- 记账建议（P0-1） / 减弱视觉效果（P1） / 异常检测（P1-3） ----------------
    // 这三个都直接读 prefs，所以声明放在 prefs 之后（Kotlin 属性按声明顺序初始化）

    /** 「减小动效 / 玻璃效果」开关（P1 液态玻璃试点） */
    private val _reduceEffects = MutableStateFlow(prefs.getBoolean("reduce_effects", false))
    val reduceEffects: StateFlow<Boolean> = _reduceEffects

    fun setReduceEffects(on: Boolean) {
        prefs.edit().putBoolean("reduce_effects", on).apply()
        _reduceEffects.value = on
        viewModelScope.launch {
            _snack.emit(if (on) "已减弱视觉效果（毛玻璃换成纯色卡片）" else "已恢复完整视觉效果")
        }
    }

    /** 记账建议开关（默认开启） */
    private val _suggestEnabled = MutableStateFlow(prefs.getBoolean("suggest_enabled", true))
    val suggestEnabled: StateFlow<Boolean> = _suggestEnabled

    fun setSuggestEnabled(on: Boolean) {
        prefs.edit().putBoolean("suggest_enabled", on).apply()
        _suggestEnabled.value = on
        viewModelScope.launch {
            _snack.emit(if (on) "已开启记账建议（纯本地，按你的历史习惯推荐）" else "已关闭记账建议")
        }
    }

    private val _ignoredAnomalies = MutableStateFlow(
        prefs.getStringSet("ignored_anomalies", emptySet())?.toSet() ?: emptySet()
    )

    /** 首页横幅用：疑似重复扣款 / 金额偏高（用户忽略过的不再出现） */
    val anomalies: StateFlow<List<AnomalyDetector.Flag>> =
        combine(txns, _ignoredAnomalies) { list, ignored ->
            AnomalyDetector.detect(list).filter { it.key !in ignored }
        }.stateInShared(emptyList())

    fun ignoreAnomaly(key: String) {
        val next = _ignoredAnomalies.value + key
        prefs.edit().putStringSet("ignored_anomalies", next).apply()
        _ignoredAnomalies.value = next
        viewModelScope.launch { _snack.emit("已忽略这条提示") }
    }

    // ---------------- 外观：主题色 + 深浅模式（N11） ----------------

    internal fun indexOfInt(value: Int, size: Int): Int = if (value in 0 until size) value else 0

    internal val _themeMode = MutableStateFlow(
        ThemeMode.values()[indexOfInt(prefs.getInt("theme_mode", 0), ThemeMode.values().size)]
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    internal val _themeColor = MutableStateFlow(
        ThemeColor.values()[indexOfInt(prefs.getInt("theme_color", 0), ThemeColor.values().size)]
    )
    val themeColor: StateFlow<ThemeColor> = _themeColor


    // ---------------- 备注记忆开关（N22，默认开启） ----------------

    internal val _noteMemoryEnabled = MutableStateFlow(prefs.getBoolean("note_memory", true))
    val noteMemoryEnabled: StateFlow<Boolean> = _noteMemoryEnabled


    // ---------------- 振动反馈开关（N37，默认开启） ----------------

    internal val _vibrateFeedback = MutableStateFlow(prefs.getBoolean("vibrate_feedback", true))
    val vibrateFeedback: StateFlow<Boolean> = _vibrateFeedback


    // ---------------- 按键音开关（N39，默认开启） ----------------

    internal val _keySound = MutableStateFlow(prefs.getBoolean("key_sound", true))
    val keySound: StateFlow<Boolean> = _keySound


    // ---------------- 回收站 ----------------

    /** 回收站里的账单（当前账本） */
    val trashTxns: StateFlow<List<Txn>> =
        key.flatMapLatest { (db, id) -> db.txnDao().observeTrash(id) }
            .stateInLazy(emptyList())

    /** 回收站里的账户 */
    val trashAccounts: StateFlow<List<Account>> =
        dbReady.flatMapLatest { it.accountDao().observeTrash() }
            .stateInLazy(emptyList())

    /** N59：回收站里的账本（软删除的，数据都还在，可还原） */
    val trashLedgers: StateFlow<List<Ledger>> =
        dbReady.flatMapLatest { it.ledgerDao().observeTrash() }
            .stateInLazy(emptyList())


    /**
     * 首页搜索（N60：SQL 下推）。
     * 关键词变化时按维度收窄：备注 / 对方 LIKE、分类 / 账户按 id、类型按枚举名、
     * 金额同时按「分的整数串」与「元的实数串」匹配（覆盖旧版 Money.text contains 的语义）；
     * 命中的「命中原因」再由 [buildHits] 在结果集上算一遍（纯内存，集合已被 SQL 收窄）。
     */
    val searchResults: StateFlow<List<Txn>> =
        combine(_searchQuery, categories, accounts, _currentLedgerId) { q, cats, accs, id ->
            SearchInput(q.trim(), cats, accs, id)
        }.flatMapLatest { input ->
            if (input.query.isEmpty()) {
                flowOf(emptyList())
            } else {
                val q = input.query
                val matchedCats = input.cats.filter { it.name.contains(q, ignoreCase = true) }
                val matchedIds = matchedCats.map { it.id }.toSet()
                val catIds = buildList {
                    addAll(matchedCats.map { it.id })
                    // 父分类名命中时，它下面的二级分类账单也要算命中（与旧版语义一致）
                    addAll(input.cats.filter { it.parentId in matchedIds }.map { it.id })
                }
                val accIds = input.accs.filter { it.name.contains(q, ignoreCase = true) }.map { it.id }
                val types = searchTypes(q)
                val escaped = escapeLike(q)
                dbReady.flatMapLatest { db ->
                    db.txnDao().observeSearch(
                        ledgerId = input.ledgerId,
                        q = escaped,
                        categoryIds = catIds.ifEmpty { listOf(NO_ID) },
                        accountIds = accIds.ifEmpty { listOf(NO_ID) },
                        types = types.ifEmpty { listOf(NO_TYPE) }
                    )
                }
            }
        }.stateInLazy(emptyList())


    internal data class SearchInput(
        val query: String,
        val cats: List<Category>,
        val accs: List<Account>,
        val ledgerId: Long
    )


    /** 合并分类：把 A 的账单全部归入 B，随后删除 A（统计随 txns 变化自动重算） */
    fun mergeCategory(from: Category, to: Category) {
        viewModelScope.launch {
            runCatching {
                repo().mergeCategory(from, to)
                _snack.emit("已把「${from.name}」并入「${to.name}」")
            }.onFailure { _snack.emit("合并失败：${it.message}") }
        }
    }

    /** 某个分类直接挂着的账单数（合并确认文案用） */
    fun categoryUsage(id: Long): Int = txns.value.count { it.categoryId == id }

    /** 某个一级分类下的二级分类数（合并确认文案用） */
    fun categoryChildCount(id: Long): Int = categories.value.count { it.parentId == id }

    fun setBudget(cents: Long) {
        viewModelScope.launch {
            runCatching {
                repo().setBudget(_currentLedgerId.value, cents)
                _snack.emit("预算已更新")
            }.onFailure { _snack.emit("保存失败：${it.message}") }
        }
    }


    // ---------------- CSV 导入（M2：先预览 + 手动映射，确认后再入库） ----------------

    internal val _csvDraft = MutableStateFlow<CsvManager.Draft?>(null)
    val csvDraft: StateFlow<CsvManager.Draft?> = _csvDraft


}

internal const val NO_ID = -1L
internal const val NO_TYPE = "__no_match__"

/** P2-1：拆分后的命令函数在扩展文件里，通过它拿 viewModelScope */
internal val AppViewModel.scope: CoroutineScope
    get() = viewModelScope
