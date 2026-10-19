package com.qingjizhang.app.data

import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** 账户余额不冗余存储，统一用「初始余额 + 流水净影响」实时算出来。 */
data class AccountBalance(
    val account: Account,
    val balanceCents: Long
)

/** 某个账户某个月的流水汇总（账户详情页用） */
data class AccountMonth(
    val monthKey: String,
    val incomeCents: Long,
    val expenseCents: Long,
    /** 该月余额净变化（含互转与借贷，所以一定与月末余额对得上） */
    val netCents: Long,
    val endBalanceCents: Long,
    val txns: List<Txn>
)

class Repository(private val db: AppDatabase) {

    val ledgers: Flow<List<Ledger>> = db.ledgerDao().observeAll()

    fun accounts(ledgerId: Long): Flow<List<Account>> = db.accountDao().observeByLedger(ledgerId)

    fun categories(ledgerId: Long): Flow<List<Category>> = db.categoryDao().observeByLedger(ledgerId)

    /** 当前账本的账户类型（自定义分组，N19） */
    fun accountTypes(ledgerId: Long): Flow<List<AccountTypeEntity>> =
        db.accountTypeDao().observeByLedger(ledgerId)

    fun txns(ledgerId: Long): Flow<List<Txn>> = db.txnDao().observeByLedger(ledgerId)

    fun rules(ledgerId: Long): Flow<List<RecurringRule>> = db.ruleDao().observeByLedger(ledgerId)

    /** 当前账本的自定义预算（N30） */
    fun budgets(ledgerId: Long): Flow<List<Budget>> = db.budgetDao().observeByLedger(ledgerId)

    /** 当前账本的自定义标签（N38） */
    fun tags(ledgerId: Long): Flow<List<TagEntity>> = db.tagDao().observeByLedger(ledgerId)

    /** 当前账本的应收应付（N44）：不计入余额、不进主页账单流 */
    fun arAps(ledgerId: Long): Flow<List<ArAp>> = db.arApDao().observeByLedger(ledgerId)

    /**
     * 当前账本所有账单的标签名（N38）：列表展示一次性取完，避免逐行查询。
     * 一个账单有多个标签时会有多行，调用方按 txnId 分组即可。
     */
    fun txnTagNames(ledgerId: Long): Flow<List<TxnTagName>> = db.tagDao().observeTagNamesByLedger(ledgerId)

    fun ledgerBundle(ledgerId: Long): Flow<Pair<List<Account>, List<Category>>> =
        combine(accounts(ledgerId), categories(ledgerId)) { a, c -> a to c }

    // ---------- 写入 ----------

    suspend fun addTxn(txn: Txn): Long = db.txnDao().insert(txn)

    /** N55：批量写入多笔账单（借贷批量收回用），同一事务内完成，避免只写进去一半 */
    suspend fun addTxns(list: List<Txn>) {
        if (list.isEmpty()) return
        db.withTransaction { list.forEach { db.txnDao().insert(it) } }
    }

    suspend fun updateTxn(txn: Txn) = db.txnDao().update(txn)

    suspend fun deleteTxn(txn: Txn) = db.txnDao().delete(txn)

    suspend fun txnById(id: Long) = db.txnDao().byId(id)

    suspend fun addAccount(account: Account): Long {
        val id = db.accountDao().insert(account)
        syncAccountLedgers()
        return id
    }

    suspend fun updateAccount(account: Account) {
        db.accountDao().update(account)
        syncAccountLedgers()
    }

    /** 批量更新账户（账户排序用） */
    suspend fun updateAccounts(accounts: List<Account>) {
        db.accountDao().updateAll(accounts)
        syncAccountLedgers()
    }

    /**
     * P2-2：把 account.ledgerIdsCsv 幂等同步进 account_ledgers 关系表。
     * 账户的新建 / 修改都会改 CSV，写完顺手同步一次，查询立刻走新表。
     */
    private suspend fun syncAccountLedgers() {
        AccountLedgerSync.sync(db.openHelper.writableDatabase)
    }

    /**
     * 删除账户：账户与它名下的账单一起进回收站（30 天内可还原），不再直接物理删除。
     */
    suspend fun deleteAccount(account: Account) {
        val at = System.currentTimeMillis()
        db.txnDao().setDeletedAtByAccount(account.id, at)
        db.accountDao().setDeletedAt(account.id, at)
    }

    /**
     * N35：删除账户但保留它名下的账单。
     *
     * 只把账户本身做软删除（进回收站），账单**原样保留**、`accountId` 一个都不改：
     * - 账单仍出现在流水 / 报表 / 统计里，收支合计不受影响；
     * - 该账户从账户页与净资产里消失，保留的账单因为账户没了也就不再计入任何账户余额；
     * - 界面拿不到账户对象时用「已删除账户」占位（见 AppViewModel.accountName）。
     * 之所以不把 accountId 置 0：置 0 会让账单变成「无账户」，按账户过滤的报表会把它
     * 归到「未分类 / 未知账户」，历史归属彻底丢失；保留原 id 至少能还原、能追溯。
     */
    suspend fun deleteAccountKeepTxns(account: Account) {
        db.accountDao().setDeletedAt(account.id, System.currentTimeMillis())
    }

    // ---------- 回收站 ----------

    fun trashTxns(ledgerId: Long): Flow<List<Txn>> = db.txnDao().observeTrash(ledgerId)

    val trashAccounts: Flow<List<Account>> = db.accountDao().observeTrash()

    /** 回收站记录默认保留 30 天 */
    val trashRetentionDays = 30

    suspend fun trashTxn(txn: Txn, at: Long = System.currentTimeMillis()) {
        db.txnDao().setDeletedAt(txn.id, at)
    }

    suspend fun restoreTxn(txn: Txn) {
        db.txnDao().clearDeletedAt(txn.id)
    }

    suspend fun restoreAccount(account: Account) {
        db.accountDao().setDeletedAt(account.id, 0L)
        // 只还原当初跟着这个账户一起进回收站的那批账单
        db.txnDao().clearDeletedAtByAccount(account.id, account.deletedAt)
    }

    /** 彻底删除一笔账单（N38：连同它的标签关联行一起清掉） */
    suspend fun purgeTxn(txn: Txn) {
        db.tagDao().clearTxnTags(txn.id)
        db.receiptPhotoDao().deleteByTxn(txn.id)
        db.txnDao().purgeById(txn.id)
    }

    /** 彻底删除账户：它名下已进回收站的账单一起物理删除 */
    suspend fun purgeAccount(account: Account) {
        db.txnDao().purgeDeletedByAccount(account.id)
        db.accountDao().purgeById(account.id)
    }

    /** 清空回收站 */
    suspend fun purgeAllTrash(ledgerId: Long) {
        db.txnDao().expiredTrash(Long.MAX_VALUE).forEach { db.txnDao().purgeById(it.id) }
        db.accountDao().expiredTrash(Long.MAX_VALUE).forEach { acct ->
            db.txnDao().purgeDeletedByAccount(acct.id)
            db.accountDao().purgeById(acct.id)
        }
        // N59：回收站里的账本级联彻底删除（它名下的数据一起清）
        db.ledgerDao().trash().forEach { l -> purgeLedger(l) }
    }

    /** 清理超过保留期的回收站记录（打开 App 时调用） */
    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()) {
        val before = now - trashRetentionDays * 24L * 60 * 60 * 1000
        db.txnDao().expiredTrash(before).forEach { db.txnDao().purgeById(it.id) }
        db.accountDao().expiredTrash(before).forEach { acct ->
            db.txnDao().purgeDeletedByAccount(acct.id)
            db.accountDao().purgeById(acct.id)
        }
        // N59：过期账本级联彻底删除
        db.ledgerDao().expiredTrash(before).forEach { l -> purgeLedger(l) }
    }

    suspend fun addCategory(category: Category): Long = db.categoryDao().insert(category)

    suspend fun updateCategory(category: Category) = db.categoryDao().update(category)

    suspend fun deleteCategory(category: Category) {
        // 一级分类连带删除其二级分类，并解除流水上的分类引用
        db.categoryDao().deleteChildren(category.id)
        db.categoryDao().detachTransactions(category.id)
        db.categoryDao().delete(category)
    }

    // ---------- 分类管理（N27：排序 / 隐藏 / 合并） ----------

    /** 排序：把某个分类的展示顺序写回数据库（sortOrder 越小越靠前） */
    suspend fun setCategorySortOrder(id: Long, order: Int) = db.categoryDao().setSortOrder(id, order)

    /** 隐藏 / 取消隐藏：隐藏的分类不出现在记一笔等选择入口，历史账单保留 */
    suspend fun setCategoryHidden(id: Long, hidden: Boolean) = db.categoryDao().setHidden(id, hidden)

    /**
     * 分类合并（N27）：把 A 分类下的账单全部归入 B 分类，随后删除 A。
     * - A 是二级分类：账单改挂 B，直接删 A（B 可以是同级或一级分类）。
     * - A 是一级分类：账单改挂 B，A 下面的二级分类「整体挂到 B 下」，再删 A。
     *   选择「整体挂到 B 下」而不是「逐个并入 B」，是为了保留用户已经分好的二级结构：
     *   A 的二级分类语义属于 A，直接换父级即可；若逐个并入 B，会与 B 下同名二级分类混在一起，
     *   相当于凭空丢掉一层分类信息。
     */
    suspend fun mergeCategory(from: Category, to: Category) {
        db.categoryDao().reassignTransactions(from.id, to.id)
        if (from.parentId == 0L) {
            db.categoryDao().reassignChildren(from.id, to.id)
        }
        db.categoryDao().delete(from)
    }

    suspend fun setBudget(ledgerId: Long, cents: Long) = db.ledgerDao().setBudget(ledgerId, cents)

    // ---------- 自定义预算（N30：自定义事项 + 自定义时间段） ----------

    suspend fun addBudget(budget: Budget): Long = db.budgetDao().insert(budget)

    suspend fun updateBudget(budget: Budget) = db.budgetDao().update(budget)

    /** 暂停 / 启用：只改开关，定义与历史统计都保留 */
    suspend fun setBudgetEnabled(id: Long, enabled: Boolean) = db.budgetDao().setEnabled(id, enabled)

    /** 删除预算定义：只删这一条预算，历史账单与收支统计完全不受影响 */
    suspend fun deleteBudget(budget: Budget) = db.budgetDao().delete(budget)

    // ---------- 应收应付（N44 / N45 修正：不计入余额、不进主页账单流） ----------

    suspend fun addArAp(item: ArAp): Long = db.arApDao().insert(item)

    /** F53：编辑已有应收 / 应付记录（金额 / 分类 / 账户 / 备注 / 类型等） */
    suspend fun updateArAp(item: ArAp) = db.arApDao().update(item)

    suspend fun deleteArAp(item: ArAp) = db.arApDao().delete(item)

    /**
     * 结算（收款 / 付款）——N45 / 修正 2 / 修正 3。
     *
     * 在**同一个事务**里完成两件事：生成真实账单（进入主页账单流并计入余额）、处理原记录。
     * - [merge] = true：多笔合并成一笔账单，账单金额 = [actualCents]，按记录时间先后分摊到各条；
     * - [merge] = false：每笔各自生成一笔账单，金额 = 该笔剩余金额；
     * - 完全结清的记录直接**删除**（修正 3：列表不再显示，只在主页账单流保留账单）；
     * - 只结清一部分的保留本行并累计 [ArAp.settledCents]（分批收款 / 付款），
     *   剩余金额下次可再结算，不会被重复生成账单。
     */
    suspend fun settleArAps(
        ledgerId: Long,
        items: List<ArAp>,
        accountId: Long,
        actualCents: Long,
        at: Long,
        reason: String,
        merge: Boolean
    ) {
        if (items.isEmpty() || accountId == 0L || actualCents <= 0L) return
        db.withTransaction {
            val sorted = items.sortedWith(compareBy({ it.dateMillis }, { it.id }))
            val receivable = sorted.first().direction == ArApDirection.RECEIVABLE
            val type = if (receivable) TxnType.INCOME else TxnType.EXPENSE
            val verb = if (receivable) "收款" else "付款"
            val head = if (receivable) "应收" else "应付"
            val counterparty = sorted.mapNotNull { it.counterparty.ifBlank { null } }
                .distinct().joinToString("、")
            val remainingTotal = sorted.sumOf { (it.amountCents - it.settledCents).coerceAtLeast(0L) }

            // F49：结算生成的账单**继承原应收应付记录的分类**，不再一律落到「其他」。
            // 只要分类还在（未被删除）就照搬，不按方向过滤——「餐饮」的应收款结算后账单分类仍是「餐饮」；
            // 分类确实已删掉时才退回该方向的默认分类（原先的行为）。
            val cats = db.categoryDao().byLedger(ledgerId)
            fun ownCategory(id: Long): Long =
                cats.firstOrNull { it.id == id }?.id ?: 0L
            val fallbackCategory = defaultCategoryFor(ledgerId, type)
            // 合并多笔时：只有所有记录的分类一致才整体沿用，否则退回默认分类
            val sharedCategory = sorted.map { ownCategory(it.categoryId) }
                .distinct()
                .singleOrNull()
                ?.takeIf { it != 0L }
                ?: fallbackCategory

            if (merge) {
                val note = buildString {
                    append(verb)
                    if (sorted.size > 1) append("（合并 ").append(sorted.size).append(" 笔）")
                    if (actualCents != remainingTotal) {
                        val d = actualCents - remainingTotal
                        append("，实").append(verb).append(" ").append(Money.withSymbol(actualCents))
                            .append("（与").append(head).append("差额 ")
                            .append(if (d > 0) "+" else "-")
                            .append(Money.text(if (d < 0) -d else d)).append("）")
                    }
                    if (reason.isNotBlank()) append("：").append(reason.trim())
                }
                val txnId = db.txnDao().insert(
                    Txn(
                        ledgerId = ledgerId, accountId = accountId, categoryId = sharedCategory,
                        type = type, amountCents = actualCents, counterparty = counterparty,
                        note = note, dateMillis = at, createdAt = at
                    )
                )
                var left = actualCents
                for (item in sorted) {
                    if (left <= 0L) break
                    val remain = (item.amountCents - item.settledCents).coerceAtLeast(0L)
                    when {
                        remain <= 0L -> db.arApDao().deleteById(item.id)
                        left >= remain -> {
                            db.arApDao().deleteById(item.id)
                            left -= remain
                        }
                        else -> {
                            db.arApDao().settlePartial(item.id, left, at, txnId)
                            left = 0L
                        }
                    }
                }
            } else {
                for (item in sorted) {
                    val remain = (item.amountCents - item.settledCents).coerceAtLeast(0L)
                    db.txnDao().insert(
                        Txn(
                            ledgerId = ledgerId, accountId = accountId,
                            // F49：不合并时每笔账单各用自己原来的分类
                            categoryId = ownCategory(item.categoryId).takeIf { it != 0L }
                                ?: fallbackCategory,
                            type = type, amountCents = remain, counterparty = item.counterparty,
                            note = verb + item.note.let { if (it.isBlank()) "" else "：" + it },
                            dateMillis = at, createdAt = at
                        )
                    )
                    db.arApDao().deleteById(item.id)
                }
            }
        }
    }

    /**
     * N50 销账：放弃剩余未收 / 未付的金额（不再收 / 不再付这笔差额）。
     * 与「结算完成即删除」共用同一条清理链路——记录直接从应收应付列表移除，
     * **不生成任何账单**，因此主页账单与余额都不受影响；应收应付总额（剩余口径）随之扣减。
     */
    suspend fun writeOffArAps(items: List<ArAp>) {
        if (items.isEmpty()) return
        db.withTransaction { items.forEach { db.arApDao().deleteById(it.id) } }
    }

    /**
     * 合并收款 / 付款生成的账单用一个「像样」的分类：
     * 优先该类型下名字含「收款」/「付款」的分类，其次含「其他」的，再否则用该类型第一个一级分类。
     * 找不到就返回 0（未分类），不会凭空新建分类。
     */
    suspend fun defaultCategoryFor(ledgerId: Long, type: TxnType): Long {
        val want = if (type == TxnType.INCOME) "收款" else "付款"
        val cats = db.categoryDao().byLedger(ledgerId)
            .filter { it.parentId == 0L && it.type == toCategoryType(type) }
        return cats.firstOrNull { it.name.contains(want) }?.id
            ?: cats.firstOrNull { it.name.contains("其他") }?.id
            ?: cats.firstOrNull()?.id
            ?: 0L
    }

    private fun toCategoryType(type: TxnType): CategoryType =
        if (type == TxnType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE

    // ---------- 账户类型（N19：按账本自定义分组） ----------

    /** 新建自定义类型：排在该账本类型列表末尾 */
    suspend fun addAccountType(ledgerId: Long, name: String): Long {
        val order = db.accountTypeDao().byLedger(ledgerId).size
        return db.accountTypeDao().insert(
            AccountTypeEntity(ledgerId = ledgerId, name = name, sortOrder = order)
        )
    }

    /**
     * 重命名类型：先把该账本下这个类型名下的账户改成新名字，再改类型名，
     * 所以账户数据本身不受影响，「改名后账户自动归入新名称」。
     */
    suspend fun renameAccountType(type: AccountTypeEntity, newName: String) {
        db.accountDao().renameTypeByLedger(type.ledgerId, type.name, newName)
        db.accountTypeDao().rename(type.ledgerId, type.name, newName)
    }

    /**
     * 删除类型：先把该类型下的账户一次性迁到 [migrateToName]，再删类型，
     * 禁止静默丢账户（调用方必须先让用户选好迁移目标）。
     */
    suspend fun deleteAccountType(type: AccountTypeEntity, migrateToName: String) {
        db.accountDao().renameTypeByLedger(type.ledgerId, type.name, migrateToName)
        db.accountTypeDao().delete(type)
    }

    /** 兜底：账本一个类型都没有时补上预设类型（老库升级后 / 从旧备份恢复） */
    suspend fun seedAccountTypesIfEmpty(ledgerId: Long) {
        if (db.accountTypeDao().count(ledgerId) == 0) {
            db.accountTypeDao().insertAll(Seed.presetAccountTypes(ledgerId))
        }
    }

    // ---------------- 互转类型（N26：按账本自定义互转胶囊） ----------------

    /** 新建自定义互转类型，排在当前账本列表末尾 */
    suspend fun addTransferKind(ledgerId: Long, name: String): Long {
        val order = db.transferKindDao().byLedger(ledgerId).size
        return db.transferKindDao().insert(
            TransferKindEntity(ledgerId = ledgerId, name = name, sortOrder = order)
        )
    }

    /**
     * 重命名互转类型：先把该账本下这个类型的历史账单改成新名字，再改类型名，
     * 所以已有流水不会丢标记（老账单不会被「遗弃」在类型列表之外）。
     */
    suspend fun renameTransferKind(kind: TransferKindEntity, newName: String) {
        db.txnDao().renameTransferKindByLedger(kind.ledgerId, kind.name, newName)
        db.transferKindDao().rename(kind.ledgerId, kind.name, newName)
    }

    /**
     * 删除互转类型：先把该类型下的历史账单整体迁到 [migrateToName] 再删类型，
     * 禁止静默丢标记（调用方必须先让用户选好迁移目标）。
     */
    suspend fun deleteTransferKind(kind: TransferKindEntity, migrateToName: String) {
        db.txnDao().renameTransferKindByLedger(kind.ledgerId, kind.name, migrateToName)
        db.transferKindDao().delete(kind)
    }

    /** 兜底：账本一个互转类型都没有时补上预设 */
    suspend fun seedTransferKindsIfEmpty(ledgerId: Long) {
        if (db.transferKindDao().count(ledgerId) == 0) {
            db.transferKindDao().insertAll(Seed.presetTransferKinds(ledgerId))
        }
    }

    // ---------------- 标签（N38：按账本自定义标签 + 账单多标签） ----------------

    /** 新建标签：排在当前账本标签列表末尾，返回新标签 id */
    suspend fun addTag(ledgerId: Long, name: String): Long {
        val order = db.tagDao().byLedger(ledgerId).size
        return db.tagDao().insert(
            TagEntity(ledgerId = ledgerId, name = name, sortOrder = order)
        )
    }

    /** 重命名标签：只改标签名，账单与它已打的标签关联都不受影响 */
    suspend fun renameTag(tag: TagEntity, newName: String) =
        db.tagDao().rename(tag.ledgerId, tag.name, newName)

    /**
     * 删除标签：先清掉所有账单与它的关联行，再删标签本身。
     * 已用过它的账单只是少一个标签，账单、金额与统计完全不受影响。
     */
    suspend fun deleteTag(tag: TagEntity) {
        db.tagDao().deleteLinksByTag(tag.id)
        db.tagDao().delete(tag)
    }

    /**
     * 给账单写标签（N38）：事务内先清空旧关联再批量插入，保证「多标签」一次写对。
     * 已去重；空列表表示清空这笔账单的全部标签。
     */
    suspend fun setTxnTags(txnId: Long, tagIds: List<Long>) {
        if (txnId <= 0L) return
        db.withTransaction {
            db.tagDao().clearTxnTags(txnId)
            val ids = tagIds.distinct()
            if (ids.isNotEmpty()) {
                db.tagDao().insertLinks(ids.map { TxnTagEntity(txnId, it) })
            }
        }
    }

    /** 某笔账单已选的标签 id（编辑账单时回显） */
    suspend fun tagIdsOf(txnId: Long): List<Long> = db.tagDao().tagIdsOf(txnId)

    // ---------- 小票照片（N56） ----------

    /** 某笔账单的小票照片（按拍摄顺序） */
    fun receiptPhotos(txnId: Long): Flow<List<ReceiptPhoto>> =
        db.receiptPhotoDao().observeByTxn(txnId)

    /** 记一张小票：文件已由 util/ReceiptStore 落盘，这里只写路径 */
    suspend fun addReceiptPhoto(txnId: Long, filePath: String): Long =
        db.receiptPhotoDao().insert(ReceiptPhoto(txnId = txnId, filePath = filePath))

    /** 删一张小票：只删库记录，文件由调用方删（失败不影响记录删除） */
    suspend fun deleteReceiptPhoto(photo: ReceiptPhoto) =
        db.receiptPhotoDao().deleteById(photo.id)

    /** 清掉某笔账单的全部小票记录（彻底删除账单时调用） */
    suspend fun clearReceiptPhotos(txnId: Long) =
        db.receiptPhotoDao().deleteByTxn(txnId)

    suspend fun addLedger(name: String): Long =
        db.ledgerDao().insert(Ledger(name = name, isDefault = false, monthlyBudgetCents = 0L))

    suspend fun renameLedger(ledger: Ledger, name: String) =
        db.ledgerDao().update(ledger.copy(name = name))

    /**
     * N59：删除账本改**软删除**——账本本身进回收站（30 天内可还原），
     * 它下面的账单 / 账户 / 分类 / 周期规则全部原样保留，还原账本时一切照旧。
     * 彻底删除（回收站里操作）才级联清空，见 [purgeLedger]。
     */
    suspend fun deleteLedger(ledger: Ledger) {
        db.ledgerDao().setDeletedAt(ledger.id, System.currentTimeMillis())
    }

    /** N59：还原账本（连同它名下的全部数据） */
    suspend fun restoreLedger(ledger: Ledger) {
        db.ledgerDao().setDeletedAt(ledger.id, 0L)
    }

    /** N59：彻底删除账本（回收站里操作）：级联清空该账本的所有数据 */
    suspend fun purgeLedger(ledger: Ledger) {
        db.txnDao().deleteByLedger(ledger.id)
        db.accountDao().deleteByLedger(ledger.id)
        db.categoryDao().deleteByLedger(ledger.id)
        db.ruleDao().deleteByLedger(ledger.id)
        db.accountTypeDao().deleteByLedger(ledger.id)
        db.budgetDao().deleteByLedger(ledger.id)
        // N38：先清关联行再删标签，避免留下悬空的 txn_tags
        db.tagDao().deleteLinksByLedger(ledger.id)
        db.tagDao().deleteByLedger(ledger.id)
        db.arApDao().deleteByLedger(ledger.id)
        // N56：小票照片记录跟着账本一起清（文件由调用方按目录清理）
        db.receiptPhotoDao().deleteByLedger(ledger.id)
        db.ledgerDao().purgeById(ledger.id)
        // P2-2：账户没了，关系表里的行交给同步清掉（悬空行无害，但顺手清干净）
        syncAccountLedgers()
    }

    suspend fun clearTransactions(ledgerId: Long) = db.txnDao().deleteByLedger(ledgerId)

    // ---------- 批量账单操作（N28） ----------

    /** 批量进回收站（与单笔删除同一套语义，保留期由回收站统一处理） */
    suspend fun trashTxnsByIds(ids: List<Long>, at: Long = System.currentTimeMillis()) =
        db.txnDao().setDeletedAtByIds(ids, at)

    /** N33：把某个账本下的全部账单一次性放进回收站（软删除，30 天内可还原） */
    suspend fun trashAllTxns(ledgerId: Long, at: Long = System.currentTimeMillis()) =
        db.txnDao().setDeletedAtByLedger(ledgerId, at)

    /** 批量移动账本 */
    suspend fun moveTxnsToLedger(ids: List<Long>, ledgerId: Long) =
        db.txnDao().setLedgerByIds(ids, ledgerId)

    /** 批量修改分类 */
    suspend fun setTxnsCategory(ids: List<Long>, categoryId: Long) =
        db.txnDao().setCategoryByIds(ids, categoryId)

    /** 批量修改账户 */
    suspend fun setTxnsAccount(ids: List<Long>, accountId: Long) =
        db.txnDao().setAccountByIds(ids, accountId)

    /** 批量设置统计口径 */
    suspend fun setTxnsExcludeFromStats(ids: List<Long>, on: Boolean) =
        db.txnDao().setExcludeFromStatsByIds(ids, on)

    suspend fun setTxnsExcludeFromBudget(ids: List<Long>, on: Boolean) =
        db.txnDao().setExcludeFromBudgetByIds(ids, on)

    /** 批量设置报销 / 退款额（同一批退款额相同的一次写回；金额上限由调用方逐笔 coerce） */
    suspend fun setTxnsRefund(ids: List<Long>, cents: Long) =
        db.txnDao().setRefundByIds(ids, cents)

    // ---------- 周期规则 ----------

    suspend fun addRule(rule: RecurringRule): Long = db.ruleDao().insert(rule)

    suspend fun updateRule(rule: RecurringRule) = db.ruleDao().update(rule)

    suspend fun deleteRule(rule: RecurringRule) {
        db.txnDao().deleteByRule(rule.id)
        db.ruleDao().delete(rule)
    }

    suspend fun ruleById(id: Long) = db.ruleDao().byId(id)

    // ---------- 统计 ----------

    /** 账户余额 = 初始余额 + 流水净影响 */
    fun balancesOf(accounts: List<Account>, txns: List<Txn>): List<AccountBalance> {
        val delta = accountDeltas(txns)
        return accounts.map { AccountBalance(it, it.initialBalanceCents + (delta[it.id] ?: 0L)) }
    }

    /** 单个账户的余额 */
    fun balanceOf(account: Account, txns: List<Txn>): Long =
        account.initialBalanceCents + (accountDeltas(txns)[account.id] ?: 0L)

    /** 某笔流水对某个账户的影响（正负） */
    private fun deltaFor(accountId: Long, t: Txn): Long = when (t.type) {
        TxnType.EXPENSE -> if (t.accountId == accountId) -effectiveCents(t) else 0L
        TxnType.INCOME -> if (t.accountId == accountId) effectiveCents(t) else 0L
        TxnType.TRANSFER ->
            (if (t.accountId == accountId) -t.amountCents else 0L) +
                (if (t.transferToAccountId == accountId) t.amountCents else 0L)
        TxnType.LEND_BORROW -> {
            val (from, to) = lendEndpoints(t)
            (if (from == accountId) -t.amountCents else 0L) + (if (to == accountId) t.amountCents else 0L)
        }
    }

    /** 这个账户相关的所有流水（含作为转入方的互转） */
    fun txnsOfAccount(accountId: Long, txns: List<Txn>): List<Txn> =
        txns.filter { it.accountId == accountId || it.transferToAccountId == accountId }
            .sortedByDescending { it.dateMillis }

    /**
     * 账户的逐月流水：先按月汇总（收入 / 支出 / 净变化 / 月末余额），再带上该月每一笔明细。
     * 返回结果是按月份倒序（最新的在前）。
     */
    fun accountMonths(account: Account, txns: List<Txn>, monthKeyOf: (Long) -> String): List<AccountMonth> {
        val mine = txnsOfAccount(account.id, txns)
        if (mine.isEmpty()) return emptyList()

        val groups = mine.groupBy { monthKeyOf(it.dateMillis) }.toSortedMap()
        var running = account.initialBalanceCents
        val result = mutableListOf<AccountMonth>()

        // 先算出第一个月之前的余额（该账户在更早月份的影响）
        val before = txns.filter { monthKeyOf(it.dateMillis) < groups.firstKey() }
            .sumOf { deltaFor(account.id, it) }
        running += before

        groups.forEach { (key, list) ->
            val income = list.filter { it.type == TxnType.INCOME && it.accountId == account.id }
                .sumOf { effectiveCents(it) }
            val expense = list.filter { it.type == TxnType.EXPENSE && it.accountId == account.id }
                .sumOf { effectiveCents(it) }
            val net = list.sumOf { deltaFor(account.id, it) }
            running += net
            result += AccountMonth(
                monthKey = key,
                incomeCents = income,
                expenseCents = expense,
                netCents = net,
                endBalanceCents = running,
                txns = list.sortedByDescending { it.dateMillis }
            )
        }
        return result.reversed()
    }

    companion object {
        /**
         * 一笔借贷的两端：返回 (转出账户 id, 转入账户 id)。
         * 借出 = 资金账户 → 借贷账户（借贷账户记应收）；借入 = 借贷账户 → 资金账户（借贷账户记负债）。
         */
        fun lendEndpoints(t: Txn): Pair<Long, Long> =
            if (t.direction == Direction.LEND) t.accountId to t.transferToAccountId
            else t.transferToAccountId to t.accountId

        /** 每笔流水对各个账户余额的净影响 */
        fun accountDeltas(txns: List<Txn>): Map<Long, Long> {
            val delta = mutableMapOf<Long, Long>()
            txns.forEach { t ->
                when (t.type) {
                    TxnType.EXPENSE -> delta[t.accountId] =
                        (delta[t.accountId] ?: 0L) - effectiveCents(t)
                    TxnType.INCOME -> delta[t.accountId] =
                        (delta[t.accountId] ?: 0L) + effectiveCents(t)
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

        /**
         * N31：余额调整的账单方向。
         * 差额 = 新余额 − 旧余额：正数说明余额变多 → 收入方向；负数说明余额变少 → 支出方向；
         * 差额为 0 表示没有变化，不需要生成账单，返回 null。
         */
        fun balanceAdjustType(diffCents: Long): TxnType? = when {
            diffCents > 0L -> TxnType.INCOME
            diffCents < 0L -> TxnType.EXPENSE
            else -> null
        }

        /** 这笔流水是否计入「收入 / 支出」统计（互转与借贷不计） */
        fun affectsIncomeExpense(t: Txn): Boolean =
            t.type == TxnType.EXPENSE || t.type == TxnType.INCOME

        /** 会计口径：计入收入 / 支出统计的账单（互转与借贷本来就不计，「不计入收支」的也排除） */
        fun countsInStats(t: Txn): Boolean = affectsIncomeExpense(t) && !t.excludeFromStats

        /** 是否计入当月预算：只统计支出，且没有勾「不计入预算」 */
        fun countsInBudget(t: Txn): Boolean = t.type == TxnType.EXPENSE && !t.excludeFromBudget

        // ---------------- 自定义预算（N30） ----------------

        /**
         * 自定义预算的口径：支出口径沿用现有月度预算的 [countsInBudget]；
         * 收入口径与之对称（收入且没有勾「不计入预算」）。
         * 互转与借贷本来就不进入预算统计，天然被排除。
         */
        fun countsInBudget(t: Txn, direction: Int): Boolean =
            if (direction == BudgetDirection.INCOME) {
                t.type == TxnType.INCOME && !t.excludeFromBudget
            } else {
                countsInBudget(t)
            }

        /** 账单日期是否落在预算区间内（含首尾两天，按本地日期比较，跨时区 / 闰月都稳） */
        fun inBudgetRange(budget: Budget, dateMillis: Long): Boolean {
            val d = DateFmt.dateOf(dateMillis)
            return !d.isBefore(DateFmt.dateOf(budget.startMillis)) &&
                !d.isAfter(DateFmt.dateOf(budget.endMillis))
        }

        /** 关键词是否命中（忽略大小写，匹配备注或对方）；关键词为空表示不限 */
        fun matchesBudgetKeyword(keyword: String, t: Txn): Boolean =
            keyword.isBlank() ||
                t.note.contains(keyword, ignoreCase = true) ||
                t.counterparty.contains(keyword, ignoreCase = true)

        /** 预算当前状态：0 未开始，1 进行中，2 已结束 */
        fun budgetPhase(budget: Budget, today: LocalDate): Int {
            if (today.isBefore(DateFmt.dateOf(budget.startMillis))) return 0
            if (today.isAfter(DateFmt.dateOf(budget.endMillis))) return 2
            return 1
        }

        /**
         * 一笔账单是否算进这个预算：区间 + 绑定条件（分类 / 账户 / 关键词）+ 口径 + N20 账户范围。
         * 与月度预算的已用口径一致：不计入收支 / 不计入预算的账单不算，
         * 「不计入净资产」账户的流水也不算（[inStatsAccountScope]）。
         * 分类 / 账户绑定按精确 id 匹配（二级分类只匹配它自己，不含其一级下的其它二级分类）。
         */
        fun matchesBudget(budget: Budget, t: Txn, excludedAccountIds: Set<Long> = emptySet()): Boolean =
            countsInBudget(t, budget.direction) &&
                inBudgetRange(budget, t.dateMillis) &&
                (budget.categoryId == 0L || t.categoryId == budget.categoryId) &&
                (budget.accountId == 0L || t.accountId == budget.accountId) &&
                matchesBudgetKeyword(budget.keyword, t) &&
                inStatsAccountScope(t, excludedAccountIds)

        /** 预算区间内的已用金额（分）：匹配账单的实计金额之和（报销 / 退款后口径） */
        fun budgetUsedCents(
            budget: Budget,
            txns: List<Txn>,
            excludedAccountIds: Set<Long> = emptySet()
        ): Long = txns.filter { matchesBudget(budget, it, excludedAccountIds) }
            .sumOf { effectiveCents(it) }

        // ---------------- 不计入净资产的账户（N20） ----------------

        /**
         * 「不计入净资产」的账户 id 集合（N20）。
         * 这些账户的余额、流水、账单列表照常展示与编辑，只是不参与任何统计口径。
         */
        fun statsExcludedAccountIds(accounts: List<Account>): Set<Long> =
            accounts.filter { !it.countInNetWorth }.mapTo(mutableSetOf()) { it.id }

        /**
         * 统计口径的账户范围（N20）：一笔流水的主账户或对方账户
         * （互转的转入账户、借贷的资金 / 借贷账户）命中被排除账户时，整笔不参与统计。
         * 只过滤统计聚合，余额与流水列表不受影响。
         */
        fun inStatsAccountScope(t: Txn, excludedIds: Set<Long>): Boolean {
            if (excludedIds.isEmpty()) return true
            if (t.accountId in excludedIds) return false
            return when (t.type) {
                TxnType.TRANSFER, TxnType.LEND_BORROW -> t.transferToAccountId !in excludedIds
                else -> true
            }
        }

        /**
         * 实计金额（N40 起包含手续费与优惠）：
         * `amountCents - refundCents - discountCents + feeCents`（不小于 0）。
         * 支出与收入的账户余额变动、收支统计、预算都按它算；互转与借贷不走这套口径
         * （手续费 / 优惠只针对收支账单）。
         */
        fun effectiveCents(t: Txn): Long = when (t.type) {
            TxnType.EXPENSE, TxnType.INCOME ->
                (t.amountCents - t.refundCents - t.discountCents + t.feeCents).coerceAtLeast(0L)
            else -> t.amountCents
        }

        /** 有报销 / 退款时，列表与详情用它做提示 */
        fun hasRefund(t: Txn): Boolean = t.refundCents > 0L

        /** 有手续费 / 优惠（N40）时，列表与详情用它做提示 */
        fun hasFeeOrDiscount(t: Txn): Boolean = t.feeCents > 0L || t.discountCents > 0L

        // ---------------- 标签（N38） ----------------

        /** 标签名规范化：去掉首尾空白，便于判重与存储 */
        fun normalizedTagName(raw: String): String = raw.trim()

        /**
         * 列表副标题里的标签文案（N38）：多个标签用空格分隔、每个前缀 `#`，
         * 最多显示 [max] 个，超出显示「等 N 个」；没有标签返回空串。
         */
        fun tagTrail(tags: List<String>, max: Int = 3): String {
            if (tags.isEmpty()) return ""
            val shown = tags.take(max).joinToString(" ") { "#" + it }
            return if (tags.size > max) "$shown 等 ${tags.size} 个" else shown
        }

        // ---------------- 借贷还款（N7） ----------------

        /** 这笔借贷是不是「收回 / 还出」的还款流水 */
        fun isSettlement(t: Txn): Boolean = t.type == TxnType.LEND_BORROW && t.settledTxnId > 0L

        /** 某笔借贷已经收回 / 已还的金额（分） */
        fun settledCentsOf(txns: List<Txn>, lendTxnId: Long): Long =
            txns.filter { isSettlement(it) && it.settledTxnId == lendTxnId }.sumOf { it.amountCents }

        /** 某笔借贷还没结清的金额（分），最低 0 */
        fun outstandingCents(txns: List<Txn>, t: Txn): Long =
            if (t.type != TxnType.LEND_BORROW || isSettlement(t)) 0L
            else (t.amountCents - settledCentsOf(txns, t.id)).coerceAtLeast(0L)

        /**
         * 一次性算出所有借贷的未结金额（借贷 id -> 未结分数）。
         * 列表里逐行算会退化成 O(n²)，所以列表页统一用这个。
         */
        fun outstandingMap(txns: List<Txn>): Map<Long, Long> {
            val settled = HashMap<Long, Long>()
            txns.forEach { t ->
                if (isSettlement(t)) {
                    settled[t.settledTxnId] = (settled[t.settledTxnId] ?: 0L) + t.amountCents
                }
            }
            val result = HashMap<Long, Long>()
            txns.forEach { t ->
                if (t.type == TxnType.LEND_BORROW && !isSettlement(t)) {
                    result[t.id] = (t.amountCents - (settled[t.id] ?: 0L)).coerceAtLeast(0L)
                }
            }
            return result
        }

        /** 某笔借贷的还款流水（按时间倒序） */
        fun settlementsOf(txns: List<Txn>, lendTxnId: Long): List<Txn> =
            txns.filter { isSettlement(it) && it.settledTxnId == lendTxnId }
                .sortedByDescending { it.dateMillis }

        /**
         * 借贷汇总：待收回 / 待归还（还款流水本身不重复计入）。
         * 借出的应收 = 借出金额 − 已收回；借入的应付 = 借入金额 − 已还出。
         */
        fun lendTotals(txns: List<Txn>): Pair<Long, Long> {
            var receivable = 0L
            var payable = 0L
            txns.forEach { t ->
                if (t.type != TxnType.LEND_BORROW) return@forEach
                if (isSettlement(t)) {
                    // 还款流水的 direction 与原借贷相反：LEND 表示「还出」，BORROW 表示「收回」
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

        /** 金额符号：支出为负、收入为正、互转与借贷为 0（按实计金额） */
        fun signedAmount(t: Txn): Long = when (t.type) {
            TxnType.EXPENSE -> -effectiveCents(t)
            TxnType.INCOME -> effectiveCents(t)
            TxnType.TRANSFER -> 0L
            TxnType.LEND_BORROW -> if (t.direction == Direction.LEND) -t.amountCents else t.amountCents
        }
    }
}
