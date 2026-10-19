package com.qingjizhang.app.vm

import android.net.Uri
import com.qingjizhang.app.data.*
import com.qingjizhang.app.ui.autoIconKeyFor
import com.qingjizhang.app.ui.theme.ThemeColor
import com.qingjizhang.app.ui.theme.ThemeMode
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import com.qingjizhang.app.util.ReceiptStore
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
 * 账户 / 账户类型 / 互转类型 / 标签 / 应收应付命令（P2-1 拆出）
 */

fun AppViewModel.saveAccount(account: Account, isNew: Boolean) {
        scope.launch {
            runCatching {
                if (isNew) repo().addAccount(account) else repo().updateAccount(account)
                _snack.emit(if (isNew) "已添加账户" else "已保存账户")
            }.onFailure { _snack.emit("保存失败：${it.message}") }
        }
    }

    /**
     * 账户排序：把某个账户上移 / 下移一位，顺序按当前位置重排后整段写回数据库，
     * 所以重启后顺序保持，账户页与记一笔的账户选择器都按这个顺序展示。
     * 只处理当前账本的账户（accounts 已经按当前账本过滤）。
     */
fun AppViewModel.moveAccount(account: Account, up: Boolean) {
        scope.launch {
            runCatching {
                val list = accounts.value
                val from = list.indexOfFirst { it.id == account.id }
                val to = if (up) from - 1 else from + 1
                if (from < 0 || to !in list.indices) return@runCatching
                val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
                // 只写回顺序真的变了的账户，避免无谓的数据库写入
                val changed = reordered.mapIndexedNotNull { i, a ->
                    if (a.sortOrder == i) null else a.copy(sortOrder = i)
                }
                if (changed.isNotEmpty()) repo().updateAccounts(changed)
            }.onFailure { _snack.emit("排序失败：${it.message}") }
        }
    }

    /**
     * M7：账户拖动排序。按下手柄拖动到任意位置后，把整段新顺序按位置写回 [Account.sortOrder]，
     * 只写回顺序真的变了的账户，重启后顺序保持（账户页与记一笔的账户选择器都按它展示）。
     */
fun AppViewModel.reorderAccounts(ordered: List<Account>) {
        scope.launch {
            runCatching {
                val changed = ordered.mapIndexedNotNull { i, a ->
                    if (a.sortOrder == i) null else a.copy(sortOrder = i)
                }
                if (changed.isNotEmpty()) repo().updateAccounts(changed)
            }.onFailure { _snack.emit("排序失败：${it.message}") }
        }
    }

    /** 该账户在当前账本下的账单笔数（含作为互转 / 借贷对方账户的账单），删除账户前提示用 */
fun AppViewModel.txnCountOfAccount(account: Account): Int =
        repo().txnsOfAccount(account.id, txns.value).size

    /**
     * 删除账户。
     * @param keepTxns true = N35「保留账单」：只把账户软删除（进回收站），账单全部原样保留、
     *                 `accountId` 不改，仍参与流水 / 报表 / 统计；
     *                 false = 「删除账单」：账户与它名下的账单一起进回收站（原行为）。
     */
fun AppViewModel.deleteAccount(account: Account, keepTxns: Boolean = false) {
        scope.launch {
            runCatching {
                if (keepTxns) {
                    repo().deleteAccountKeepTxns(account)
                    _snack.emit("账户已删除，账单已保留")
                } else {
                    repo().deleteAccount(account)
                    _snack.emit("账户与它的账单已移入回收站")
                }
            }.onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    /** 借贷记账必须先有借贷账户：当前账本没有就自动建一个 */
internal suspend fun AppViewModel.ensureLoanAccount(): Long {
        val exist = accounts.value.firstOrNull { it.type == AccountType.LEND }
        if (exist != null) return exist.id
        return repo().addAccount(
            Account(
                ledgerId = _currentLedgerId.value,
                name = "借贷账户",
                type = AccountType.LEND
            )
        )
    }

    /**
     * N31 设置账户余额。
     * @param generateTxn 是否生成账单记录（界面上默认关闭）：
     *   - false：只把余额字段改到目标值（差额落回 [Account.initialBalanceCents]），
     *            不生成任何账单流水，报表 / 统计 / 预算 / 借贷汇总完全不受影响；
     *   - true ：按新旧余额差额生成一条「漏记款 · 余额调整」账单，正数按收入、负数按支出，
     *            备注可自定义，计入当前账本。
     */
fun AppViewModel.adjustAccountBalance(
        account: Account,
        newBalanceCents: Long,
        note: String,
        generateTxn: Boolean = false
    ) {
        scope.launch {
            runCatching {
                val ledgerId = _currentLedgerId.value
                val current = repo().balanceOf(account, txns.value)
                val diff = newBalanceCents - current
                if (diff == 0L) {
                    _snack.emit("余额没有变化")
                    return@runCatching
                }
                if (!generateTxn) {
                    // 不生成账单：直接把差额写进初始余额，余额 = 初始余额 + 流水净影响 立即变为目标值
                    repo().updateAccount(
                        account.copy(initialBalanceCents = account.initialBalanceCents + diff)
                    )
                    _snack.emit("余额已调整为 " + Money.withSymbol(newBalanceCents) + "，未生成账单")
                    return@runCatching
                }
                val isIncome = Repository.balanceAdjustType(diff) == TxnType.INCOME
                val catId = ensureCategory(
                    ledgerId,
                    if (isIncome) CategoryType.INCOME else CategoryType.EXPENSE,
                    "漏记款",
                    "余额调整"
                )
                repo().addTxn(
                    Txn(
                        ledgerId = ledgerId,
                        accountId = account.id,
                        categoryId = catId,
                        type = if (isIncome) TxnType.INCOME else TxnType.EXPENSE,
                        amountCents = Math.abs(diff),
                        note = note.ifBlank { "余额调整" },
                        dateMillis = System.currentTimeMillis()
                    )
                )
                _snack.emit(
                    "已按差额记一笔" + (if (isIncome) "收入 " else "支出 ") +
                        Money.withSymbol(Math.abs(diff))
                )
            }.onFailure { _snack.emit("调整失败：${it.message}") }
        }
    }

    /** 账户的逐月流水汇总 */
fun AppViewModel.accountMonths(account: Account): List<AccountMonth> =
        repo().accountMonths(account, txns.value, DateFmt::monthKey)

    /** 某个账户当前余额 */
fun AppViewModel.balanceOfAccount(account: Account): Long = repo().balanceOf(account, txns.value)

    /**
     * 账户名。N35：账户被软删除但账单被保留时，账单的 accountId 依然指向这个已删账户，
     * 这里统一给出「已删除账户」占位，避免界面拿到空串看不出原因（id = 0 是「无账户」，返回空）。
     */
fun AppViewModel.accountName(id: Long): String = when (id) {
        0L -> ""
        else -> accounts.value.firstOrNull { it.id == id }?.name ?: "已删除账户"
    }

    // ---------------- 账户类型（N19：按账本自定义分组） ----------------

    /** 新建自定义类型，排在当前账本类型列表末尾 */
fun AppViewModel.addAccountType(name: String) {
        val n = name.trim()
        if (n.isEmpty()) {
            toast("请填写类型名称")
            return
        }
        if (accountTypes.value.any { it.name == n }) {
            toast("已经有「$n」了")
            return
        }
        scope.launch {
            runCatching { repo().addAccountType(_currentLedgerId.value, n) }
                .onSuccess { _snack.emit("已新建类型「$n」") }
                .onFailure { _snack.emit("新建失败：${it.message}") }
        }
    }

    /** 重命名类型（预设类型同样可改）：它名下的账户会自动归入新名称 */
fun AppViewModel.renameAccountType(type: AccountTypeEntity, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) {
            toast("请填写类型名称")
            return
        }
        if (n == type.name) return
        if (accountTypes.value.any { it.name == n }) {
            toast("已经有「$n」了")
            return
        }
        scope.launch {
            runCatching { repo().renameAccountType(type, n) }
                .onSuccess { _snack.emit("已重命名为「$n」") }
                .onFailure { _snack.emit("重命名失败：${it.message}") }
        }
    }

    /**
     * 删除类型：先把它名下的账户迁到 [migrateToName] 再删类型，账户不会丢。
     * 至少保留一个类型，否则兜底播种会立刻把预设类型补回来。
     */
fun AppViewModel.deleteAccountType(type: AccountTypeEntity, migrateToName: String) {
        if (accountTypes.value.size <= 1) {
            toast("至少保留一个账户类型")
            return
        }
        scope.launch {
            runCatching { repo().deleteAccountType(type, migrateToName) }
                .onSuccess { _snack.emit("已删除类型「${type.name}」") }
                .onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    /** 某个类型名下的账户（含隐藏账户）：删除类型前用来判断要不要让用户选迁移目标 */
fun AppViewModel.accountsOfType(typeName: String): List<Account> = accounts.value.filter { it.type == typeName }

    // ---------------- 互转类型（N26：按账本自定义互转胶囊） ----------------

    /** 新建自定义互转类型，排在当前账本列表末尾 */
fun AppViewModel.addTransferKind(name: String) {
        val n = name.trim()
        if (n.isEmpty()) {
            toast("请填写互转类型名称")
            return
        }
        if (transferKinds.value.any { it.name == n }) {
            toast("已经有「$n」了")
            return
        }
        scope.launch {
            runCatching { repo().addTransferKind(_currentLedgerId.value, n) }
                .onSuccess { _snack.emit("已新建「$n」") }
                .onFailure { _snack.emit("新建失败：${it.message}") }
        }
    }

    /** 重命名互转类型：历史账单的标记会一起改到新名称 */
fun AppViewModel.renameTransferKind(kind: TransferKindEntity, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) {
            toast("请填写互转类型名称")
            return
        }
        if (n == kind.name) return
        if (transferKinds.value.any { it.name == n }) {
            toast("已经有「$n」了")
            return
        }
        scope.launch {
            runCatching { repo().renameTransferKind(kind, n) }
                .onSuccess { _snack.emit("已重命名为「$n」") }
                .onFailure { _snack.emit("重命名失败：${it.message}") }
        }
    }

    /**
     * 删除互转类型：先把它名下的历史账单迁到 [migrateToName] 再删，标记不会丢。
     * 至少保留一个类型，否则兜底播种会立刻把预设补回来。
     */
fun AppViewModel.deleteTransferKind(kind: TransferKindEntity, migrateToName: String) {
        if (transferKinds.value.size <= 1) {
            toast("至少保留一个互转类型")
            return
        }
        scope.launch {
            runCatching { repo().deleteTransferKind(kind, migrateToName) }
                .onSuccess { _snack.emit("已删除「${kind.name}」") }
                .onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    /** 某个互转类型下已有的账单笔数：删除类型前用来判断要不要让用户选迁移目标 */
fun AppViewModel.transferKindUsage(name: String): Int = txns.value.count { it.transferKind == name }

    // ---------------- 标签（N38：按账本自定义标签 + 账单多标签） ----------------

    /**
     * 新建标签（快捷新建与设置页共用）：返回新标签 id（失败返回 0）。
     * 已存在同名标签时直接返回它的 id，不回滚、不重复建。
     */
internal suspend fun AppViewModel.saveTag(name: String): Long {
        val n = Repository.normalizedTagName(name)
        if (n.isEmpty()) {
            toast("请填写标签名称")
            return 0L
        }
        tags.value.firstOrNull { it.name == n }?.let { return it.id }
        return runCatching { repo().addTag(_currentLedgerId.value, n) }
            .onSuccess { _snack.emit("已新建标签「$n」") }
            .onFailure { _snack.emit("新建失败：${it.message}") }
            .getOrDefault(0L)
    }

    /** 重命名标签：只改名字，已打过它的账单关联不受影响 */
fun AppViewModel.renameTag(tag: TagEntity, newName: String) {
        val n = Repository.normalizedTagName(newName)
        if (n.isEmpty()) {
            toast("请填写标签名称")
            return
        }
        if (n == tag.name) return
        if (tags.value.any { it.name == n }) {
            toast("已经有「$n」了")
            return
        }
        scope.launch {
            runCatching { repo().renameTag(tag, n) }
                .onSuccess { _snack.emit("已重命名为「$n」") }
                .onFailure { _snack.emit("重命名失败：${it.message}") }
        }
    }

    /**
     * 删除标签：连同所有账单与它的关联一起清掉，账单本身不受影响。
     * 由调用方先弹确认框（见 TagManagerSheet）。
     */
fun AppViewModel.deleteTag(tag: TagEntity) {
        scope.launch {
            runCatching { repo().deleteTag(tag) }
                .onSuccess { _snack.emit("已删除标签「${tag.name}」") }
                .onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    /** 某个标签下已有的账单笔数（未进回收站）：标签管理里展示，删除前提示影响范围 */
fun AppViewModel.tagUsage(tag: TagEntity): Int =
        txnTagMap.value.count { (_, names) -> names.contains(tag.name) }

    /** 编辑账单时回显它已有的标签 id */
internal suspend fun AppViewModel.tagIdsOfTxn(txnId: Long): List<Long> = repo().tagIdsOf(txnId)

    // ---------------- 应收应付（N44 / N45 / 修正 2 / 修正 3） ----------------

    /**
     * 保存一条应收 / 应付备忘（F47 / N47）：**等写库结果**，成功返回 true，失败给提示并返回 false。
     * 只写 receivables 表 → 不进主页账单流、不计入余额；收款 / 付款时才生成真实账单。
     */
internal suspend fun AppViewModel.saveArAp(item: ArAp): Boolean =
        runCatching { repo().addArAp(item.copy(ledgerId = currentLedgerId.value)) }
            .onSuccess {
                _snack.emit(if (item.direction == ArApDirection.RECEIVABLE) "已记应收款" else "已记应付款")
            }
            .onFailure { _snack.emit("保存失败：${it.message}") }
            .isSuccess

fun AppViewModel.deleteArAp(item: ArAp) {
        scope.launch {
            runCatching { repo().deleteArAp(item) }
                .onSuccess { _snack.emit("已删除") }
                .onFailure { _snack.emit("删除失败：${it.message}") }
        }
    }

    /** F53：编辑已有应收 / 应付记录；等写库结果，成功返回 true */
internal suspend fun AppViewModel.updateArAp(item: ArAp): Boolean =
        runCatching { repo().updateArAp(item) }
            .onSuccess { _snack.emit("已保存修改") }
            .onFailure { _snack.emit("保存失败：${it.message}") }
            .isSuccess

    /**
     * N50 / F53 销账：放弃剩余未收 / 未付的金额，记录从列表移除且不生成账单。
     * 交给 scope 执行，调用方可以立即关闭界面（列表会随数据源自动刷新）。
     */
fun AppViewModel.writeOffArAps(items: List<ArAp>) {
        if (items.isEmpty()) return
        scope.launch {
            runCatching { repo().writeOffArAps(items) }
                .onSuccess {
                    val abandoned = items.sumOf { (it.amountCents - it.settledCents).coerceAtLeast(0L) }
                    _snack.emit("已销账，放弃剩余 " + Money.withSymbol(abandoned))
                }
                .onFailure { _snack.emit("销账失败：${it.message}") }
        }
    }

    /**
     * 结算（收款 / 付款）——N45 / 修正 2 / 修正 3。
     *
     * 生成真实账单（进入主页账单流并计入余额）并把已结清的应收应付记录从列表移除，都在
     * [Repository.settleArAps] 的同一个事务里完成：
     * - [merge] = true：所选多笔合并成一笔账单，金额 = [actualCents]（可调整），按记录时间先后分摊；
     * - [merge] = false：每笔各自生成一笔账单（金额 = 该笔剩余）；
     * - 单据点击即结算时传单条记录、[merge] = true，等价于「合并一笔」；
     * - 账单日期时间用 [atMillis]（结算界面里选的收款 / 付款日期时间）。
     * 等写库结果，成功返回 true（调用方据此决定是否继续询问「放弃剩余余额」）。
     */
internal suspend fun AppViewModel.settleArAps(
        items: List<ArAp>,
        accountId: Long,
        actualCents: Long,
        atMillis: Long,
        reason: String,
        merge: Boolean
    ): Boolean {
        if (items.isEmpty() || accountId == 0L || actualCents <= 0L) return false
        val ledgerId = currentLedgerId.value
        val receivable = items.first().direction == ArApDirection.RECEIVABLE
        val verb = if (receivable) "收款" else "付款"
        val total = items.sumOf { (it.amountCents - it.settledCents).coerceAtLeast(0L) }
        return runCatching {
            repo().settleArAps(ledgerId, items, accountId, actualCents, atMillis, reason, merge)
        }.onSuccess {
            _snack.emit(
                "已$verb " + Money.withSymbol(actualCents) +
                    if (merge && actualCents < total) {
                        "，剩余部分仍" + (if (receivable) "待收" else "待付")
                    } else ""
            )
        }.onFailure { _snack.emit("操作失败：${it.message}") }
            .isSuccess
    }
