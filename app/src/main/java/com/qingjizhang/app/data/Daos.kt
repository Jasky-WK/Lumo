package com.qingjizhang.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger WHERE deletedAt = 0 ORDER BY isDefault DESC, id ASC")
    fun observeAll(): Flow<List<Ledger>>

    @Query("SELECT * FROM ledger WHERE deletedAt = 0 ORDER BY isDefault DESC, id ASC")
    suspend fun all(): List<Ledger>

    @Query("SELECT * FROM ledger WHERE id = :id")
    suspend fun byId(id: Long): Ledger?

    @Query("SELECT COUNT(*) FROM ledger WHERE deletedAt = 0")
    suspend fun count(): Int

    @Insert
    suspend fun insert(ledger: Ledger): Long

    @Update
    suspend fun update(ledger: Ledger)

    @Delete
    suspend fun delete(ledger: Ledger)

    @Query("UPDATE ledger SET monthlyBudgetCents = :cents WHERE id = :id")
    suspend fun setBudget(id: Long, cents: Long)

    // ---------- 回收站（N59：删除账本改软删除） ----------

    /** 回收站里的账本（软删除过的），数据都还在，可还原 */
    @Query("SELECT * FROM ledger WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Ledger>>

    @Query("SELECT * FROM ledger WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun trash(): List<Ledger>

    @Query("UPDATE ledger SET deletedAt = :at WHERE id = :id")
    suspend fun setDeletedAt(id: Long, at: Long)

    @Query("SELECT * FROM ledger WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expiredTrash(before: Long): List<Ledger>

    @Query("DELETE FROM ledger WHERE id = :id")
    suspend fun purgeById(id: Long)
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE ledgerId = :ledgerId AND deletedAt = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<Account>>

    /** 账户可跨账本使用，所以先全部取出，再按 ledgerIdsCsv 在内存里过滤 */
    @Query("SELECT * FROM account WHERE deletedAt = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<Account>>

    /**
     * P2-2：某个账本可用的账户（含跨账本共用的）。
     * 走 account_ledgers 关系表（IN 子查询），不再把整表读回来在内存里按 CSV 过滤。
     * 关系表由 [AccountLedgerSync] 从 ledgerIdsCsv 幂等同步维护。
     */
    @Query(
        "SELECT * FROM account WHERE deletedAt = 0 AND " +
            "id IN (SELECT accountId FROM account_ledgers WHERE ledgerId = :ledgerId) " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    fun observeForLedger(ledgerId: Long): Flow<List<Account>>

    /** [observeForLedger] 的一次性版本（小组件刷新用） */
    @Query(
        "SELECT * FROM account WHERE deletedAt = 0 AND " +
            "id IN (SELECT accountId FROM account_ledgers WHERE ledgerId = :ledgerId) " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun forLedger(ledgerId: Long): List<Account>

    @Query("SELECT * FROM account WHERE deletedAt = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun all(): List<Account>

    @Query("SELECT * FROM account WHERE ledgerId = :ledgerId AND deletedAt = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<Account>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun byId(id: Long): Account?

    @Query("SELECT COUNT(*) FROM account WHERE ledgerId = :ledgerId AND deletedAt = 0")
    suspend fun count(ledgerId: Long): Int

    // ---------- 回收站 ----------

    /** 回收站里的账户（软删除过的） */
    @Query("SELECT * FROM account WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun trash(): List<Account>

    @Query("UPDATE account SET deletedAt = :at WHERE id = :id")
    suspend fun setDeletedAt(id: Long, at: Long)

    /** 超过保留期的账户：这里只是查出来，具体删除交给 Repository 处理 */
    @Query("SELECT * FROM account WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expiredTrash(before: Long): List<Account>

    @Query("DELETE FROM account WHERE id = :id")
    suspend fun purgeById(id: Long)

    @Insert
    suspend fun insert(account: Account): Long

    @Insert
    suspend fun insertAll(accounts: List<Account>)

    @Update
    suspend fun update(account: Account)

    /** 批量更新（账户排序时一次写回顺序，避免逐个提交） */
    @Update
    suspend fun updateAll(accounts: List<Account>)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM account WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)

    /**
     * 账户类型改名 / 删除时，把该类型下的账户一次性归到新的类型名。
     * 账户可跨账本共用，所以这里按「ledgerIdsCsv 命中当前账本」来判断，
     * 与 Account.appliesTo 的口径保持一致（csv 为空表示只属于 ledgerId 那个账本）。
     */
    @Query(
        "UPDATE account SET type = :newName WHERE type = :oldName AND (" +
            "(ledgerIdsCsv = '' AND ledgerId = :ledgerId) OR " +
            "(',' || ledgerIdsCsv || ',') LIKE ('%,' || :ledgerId || ',%'))"
    )
    suspend fun renameTypeByLedger(ledgerId: Long, oldName: String, newName: String)
}

@Dao
interface AccountTypeDao {
    @Query("SELECT * FROM account_types WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<AccountTypeEntity>>

    @Query("SELECT * FROM account_types WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<AccountTypeEntity>

    @Query("SELECT COUNT(*) FROM account_types WHERE ledgerId = :ledgerId")
    suspend fun count(ledgerId: Long): Int

    @Insert
    suspend fun insert(type: AccountTypeEntity): Long

    @Insert
    suspend fun insertAll(types: List<AccountTypeEntity>)

    @Query("UPDATE account_types SET name = :newName WHERE ledgerId = :ledgerId AND name = :oldName")
    suspend fun rename(ledgerId: Long, oldName: String, newName: String)

    @Delete
    suspend fun delete(type: AccountTypeEntity)

    @Query("DELETE FROM account_types WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

@Dao
interface TransferKindDao {
    @Query("SELECT * FROM transfer_kinds WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<TransferKindEntity>>

    @Query("SELECT * FROM transfer_kinds WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<TransferKindEntity>

    @Query("SELECT COUNT(*) FROM transfer_kinds WHERE ledgerId = :ledgerId")
    suspend fun count(ledgerId: Long): Int

    @Insert
    suspend fun insert(kind: TransferKindEntity): Long

    @Insert
    suspend fun insertAll(kinds: List<TransferKindEntity>)

    @Query("UPDATE transfer_kinds SET name = :newName WHERE ledgerId = :ledgerId AND name = :oldName")
    suspend fun rename(ledgerId: Long, oldName: String, newName: String)

    @Delete
    suspend fun delete(kind: TransferKindEntity)

    @Query("DELETE FROM transfer_kinds WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

/**
 * 标签（N38）：按账本自定义标签 + 账单-标签多对多关联。
 * 标签与关联表都按本工程惯例不建外键，删除时由 Repository 负责清理关联行。
 */
@Dao
interface TagDao {
    @Query("SELECT * FROM tags WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE ledgerId = :ledgerId ORDER BY sortOrder ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<TagEntity>

    @Query("SELECT COUNT(*) FROM tags WHERE ledgerId = :ledgerId")
    suspend fun count(ledgerId: Long): Int

    @Insert
    suspend fun insert(tag: TagEntity): Long

    @Insert
    suspend fun insertAll(tags: List<TagEntity>)

    @Query("UPDATE tags SET name = :newName WHERE ledgerId = :ledgerId AND name = :oldName")
    suspend fun rename(ledgerId: Long, oldName: String, newName: String)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tags WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)

    // ---------- 账单-标签关联 ----------

    /** 某笔账单已选的标签 id */
    @Query("SELECT tagId FROM txn_tags WHERE txnId = :txnId")
    suspend fun tagIdsOf(txnId: Long): List<Long>

    /** 重写某笔账单的标签前先清空旧关联（与批量插入同一事务） */
    @Query("DELETE FROM txn_tags WHERE txnId = :txnId")
    suspend fun clearTxnTags(txnId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<TxnTagEntity>)

    @Query("DELETE FROM txn_tags WHERE tagId = :tagId")
    suspend fun deleteLinksByTag(tagId: Long)

    @Query("DELETE FROM txn_tags WHERE tagId IN (SELECT id FROM tags WHERE ledgerId = :ledgerId)")
    suspend fun deleteLinksByLedger(ledgerId: Long)

    /**
     * 当前账本全部账单的「账单 id + 标签名」：列表展示一次性取完，
     * 避免逐行查询（与借贷未结金额 outstandingMap 同一思路）。
     * 已进回收站的账单不参与（列表里也看不到它们）。
     */
    @Query(
        "SELECT tt.txnId AS txnId, t.name AS name FROM txn_tags tt " +
            "INNER JOIN tags t ON t.id = tt.tagId " +
            "INNER JOIN txn x ON x.id = tt.txnId " +
            "WHERE x.ledgerId = :ledgerId AND x.deletedAt = 0 " +
            "ORDER BY t.sortOrder ASC, t.id ASC"
    )
    fun observeTagNamesByLedger(ledgerId: Long): Flow<List<TxnTagName>>
}

@Dao
interface BudgetDao {
    /** 当前账本的自定义预算（N30）：启用的在前，再按创建顺序 */
    @Query("SELECT * FROM budgets WHERE ledgerId = :ledgerId ORDER BY enabled DESC, createdAt ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE ledgerId = :ledgerId ORDER BY enabled DESC, createdAt ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<Budget>

    @Insert
    suspend fun insert(budget: Budget): Long

    @Update
    suspend fun update(budget: Budget)

    @Query("UPDATE budgets SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("DELETE FROM budgets WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

/**
 * 应收应付（N44 / N45 修正）。
 *
 * 结清（收款 / 付款）时在同一事务里生成真实账单，并把记录**删除**（修正 3）；
 * 只结清一部分的（分批收款 / 付款）保留本行，用 `settledCents` 累计已收 / 已付金额。
 * SQL 都带 `status = 'PENDING'` 条件，避免对同一条记录重复处理。
 */
@Dao
interface ArApDao {
    @Query(
        "SELECT * FROM receivables WHERE ledgerId = :ledgerId AND status = 'PENDING' " +
            "ORDER BY dateMillis DESC, id DESC"
    )
    fun observeByLedger(ledgerId: Long): Flow<List<ArAp>>

    @Insert
    suspend fun insert(item: ArAp): Long

    /** F53：编辑已有应收 / 应付记录 */
    @Update
    suspend fun update(item: ArAp)

    @Delete
    suspend fun delete(item: ArAp)

    /** 整条结清：直接从应收应付列表移除（修正 3），只在主页账单流保留生成的账单 */
    @Query("DELETE FROM receivables WHERE id = :id AND status = 'PENDING'")
    suspend fun deleteById(id: Long)

    /** 部分结清（分批收款 / 付款）：累计已收 / 已付金额，状态保持待收 / 待付 */
    @Query(
        "UPDATE receivables SET settledCents = settledCents + :cents, settledAt = :at, " +
            "settledTxnId = :txnId WHERE id = :id AND status = 'PENDING'"
    )
    suspend fun settlePartial(id: Long, cents: Long, at: Long, txnId: Long)

    @Query("DELETE FROM receivables WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE ledgerId = :ledgerId ORDER BY type ASC, sortOrder ASC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE ledgerId = :ledgerId ORDER BY type ASC, sortOrder ASC, id ASC")
    suspend fun byLedger(ledgerId: Long): List<Category>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: Long): Category?

    @Query("SELECT COUNT(*) FROM category WHERE ledgerId = :ledgerId")
    suspend fun count(ledgerId: Long): Int

    @Query("SELECT * FROM category WHERE ledgerId = :ledgerId AND type = :type AND name = :name AND parentId = :parentId LIMIT 1")
    suspend fun findByName(ledgerId: Long, type: CategoryType, name: String, parentId: Long): Category?

    @Insert
    suspend fun insert(category: Category): Long

    @Insert
    suspend fun insertAll(categories: List<Category>)

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("DELETE FROM category WHERE parentId = :parentId")
    suspend fun deleteChildren(parentId: Long)

    @Query("UPDATE txn SET categoryId = 0 WHERE categoryId = :categoryId")
    suspend fun detachTransactions(categoryId: Long)

    /** 分类合并（N27）：把 A 分类下的账单批量归入 B 分类，之后 A 才能安全删除 */
    @Query("UPDATE txn SET categoryId = :toId WHERE categoryId = :fromId")
    suspend fun reassignTransactions(fromId: Long, toId: Long)

    @Query("UPDATE category SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("UPDATE category SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE category SET parentId = :toId WHERE parentId = :fromId")
    suspend fun reassignChildren(fromId: Long, toId: Long)

    @Query("DELETE FROM category WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 ORDER BY dateMillis DESC, id DESC")
    fun observeByLedger(ledgerId: Long): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 ORDER BY dateMillis DESC, id DESC")
    suspend fun byLedger(ledgerId: Long): List<Txn>

    /** v2.9.0：空库判断（自动备份不该给一笔账都没有的库产生备份） */
    @Query("SELECT COUNT(*) FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0")
    suspend fun countLive(ledgerId: Long): Int

    // ---------- 统计聚合（N60：SQL 下推，账单量大时不再全量读内存重算） ----------

    /**
     * 当月收入 / 支出合计（首页用）。
     * 口径与 Repository.countsInStats / effectiveCents 严格一致：
     * 只算收入 / 支出、排除「不计入收支」、按实计金额（金额−退款−优惠+手续费，下限 0）、
     * 排除「不计入净资产」账户名下的流水。excludedAccountIds 为空时调用方传哨兵 -1
     * （NOT IN (-1) 恒真），避免空集合 IN 的兼容性问题。
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN type = 'INCOME' THEN MAX(amountCents - refundCents - discountCents + feeCents, 0) ELSE 0 END), 0) AS incomeCents, " +
            "COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN MAX(amountCents - refundCents - discountCents + feeCents, 0) ELSE 0 END), 0) AS expenseCents " +
            "FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 " +
            "AND dateMillis >= :start AND dateMillis < :end " +
            "AND excludeFromStats = 0 AND type IN ('EXPENSE', 'INCOME') " +
            "AND accountId NOT IN (:excludedAccountIds)"
    )
    fun observeMonthIncomeExpense(
        ledgerId: Long,
        start: Long,
        end: Long,
        excludedAccountIds: Set<Long>
    ): Flow<MonthSum>

    /**
     * 当月预算已用（月度预算口径）：只算支出、排除「不计入预算」、按实计金额、
     * 排除「不计入净资产」账户名下的流水（口径与 Repository.countsInBudget 一致）。
     */
    @Query(
        "SELECT COALESCE(SUM(MAX(amountCents - refundCents - discountCents + feeCents, 0)), 0) " +
            "FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 " +
            "AND dateMillis >= :start AND dateMillis < :end " +
            "AND type = 'EXPENSE' AND excludeFromBudget = 0 " +
            "AND accountId NOT IN (:excludedAccountIds)"
    )
    fun observeBudgetUsedCents(
        ledgerId: Long,
        start: Long,
        end: Long,
        excludedAccountIds: Set<Long>
    ): Flow<Long>

    /**
     * 按时间窗取账单（统计页用，N60）。
     * 趋势图最多回看 5 年（逐年）/ 6 个月（逐月）/ 7 天（逐日），窗口由调用方按当前
     * 周期算好；窗口外的账单统计页用不到，不再每次变动都全量重算。
     */
    @Query(
        "SELECT * FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 " +
            "AND dateMillis >= :start AND dateMillis < :end ORDER BY dateMillis DESC, id DESC"
    )
    fun observeByLedgerBetween(ledgerId: Long, start: Long, end: Long): Flow<List<Txn>>

    // ---------- 搜索（N60：文本 / 分类 / 账户 / 类型 / 金额下推 SQL） ----------

    /**
     * 搜索账单：备注 / 对方 LIKE 下推，分类 / 账户按 id，类型按枚举名，
     * 金额同时按「分的整数串」和「元的实数串」匹配（与旧版 Money.text contains 等价）。
     * q 已由调用方做好 LIKE 转义（% _ \ 都转掉），SQL 里配 ESCAPE '\'。
     */
    @Query(
        "SELECT * FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0 AND (" +
            "note LIKE '%' || :q || '%' ESCAPE '\\' OR " +
            "counterparty LIKE '%' || :q || '%' ESCAPE '\\' OR " +
            "categoryId IN (:categoryIds) OR " +
            "accountId IN (:accountIds) OR " +
            "transferToAccountId IN (:accountIds) OR " +
            "type IN (:types) OR " +
            "CAST(amountCents AS TEXT) LIKE '%' || :q || '%' OR " +
            "(amountCents / 100.0) LIKE '%' || :q || '%'" +
            ") ORDER BY dateMillis DESC, id DESC"
    )
    fun observeSearch(
        ledgerId: Long,
        q: String,
        categoryIds: List<Long>,
        accountIds: List<Long>,
        types: List<String>
    ): Flow<List<Txn>>

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: Long): Txn?

    @Query("SELECT COUNT(*) FROM txn WHERE ledgerId = :ledgerId AND deletedAt = 0")
    suspend fun count(ledgerId: Long): Int

    // ---------- 回收站 ----------

    /** 回收站里的账单（软删除过的） */
    @Query("SELECT * FROM txn WHERE ledgerId = :ledgerId AND deletedAt > 0 ORDER BY deletedAt DESC, dateMillis DESC")
    fun observeTrash(ledgerId: Long): Flow<List<Txn>>

    @Query("UPDATE txn SET deletedAt = :at WHERE id = :id")
    suspend fun setDeletedAt(id: Long, at: Long)

    /** 账户进回收站时，把它名下（含作为转入方）的账单一起标记 */
    @Query("UPDATE txn SET deletedAt = :at WHERE deletedAt = 0 AND (accountId = :accountId OR transferToAccountId = :accountId)")
    suspend fun setDeletedAtByAccount(accountId: Long, at: Long)

    /** N33：把某个账本下的全部账单一次性放进回收站（软删除，30 天内可还原） */
    @Query("UPDATE txn SET deletedAt = :at WHERE ledgerId = :ledgerId AND deletedAt = 0")
    suspend fun setDeletedAtByLedger(ledgerId: Long, at: Long)

    @Query("UPDATE txn SET deletedAt = 0 WHERE id = :id")
    suspend fun clearDeletedAt(id: Long)

    /** 还原账户时，把它名下的账单一起还原（只还原跟着账户一起删的那批） */
    @Query("UPDATE txn SET deletedAt = 0 WHERE deletedAt = :at AND (accountId = :accountId OR transferToAccountId = :accountId)")
    suspend fun clearDeletedAtByAccount(accountId: Long, at: Long)

    /** 超过保留期的账单 */
    @Query("SELECT * FROM txn WHERE deletedAt > 0 AND deletedAt < :before")
    suspend fun expiredTrash(before: Long): List<Txn>

    /** 彻底删除账户时，把已经进回收站的账单一起物理删掉 */
    @Query("DELETE FROM txn WHERE deletedAt > 0 AND (accountId = :accountId OR transferToAccountId = :accountId)")
    suspend fun purgeDeletedByAccount(accountId: Long)

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun purgeById(id: Long)

    @Insert
    suspend fun insert(txn: Txn): Long

    @Insert
    suspend fun insertAll(txns: List<Txn>)

    @Update
    suspend fun update(txn: Txn)

    @Delete
    suspend fun delete(txn: Txn)

    @Query("DELETE FROM txn WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)

    // ---------- 批量操作（N28） ----------

    /** 批量进回收站 */
    @Query("UPDATE txn SET deletedAt = :at WHERE id IN (:ids)")
    suspend fun setDeletedAtByIds(ids: List<Long>, at: Long)

    /** 批量还原 */
    @Query("UPDATE txn SET deletedAt = 0 WHERE id IN (:ids)")
    suspend fun clearDeletedAtByIds(ids: List<Long>)

    /** 批量移动账本 */
    @Query("UPDATE txn SET ledgerId = :ledgerId WHERE id IN (:ids)")
    suspend fun setLedgerByIds(ids: List<Long>, ledgerId: Long)

    /** 批量改分类 */
    @Query("UPDATE txn SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun setCategoryByIds(ids: List<Long>, categoryId: Long)

    /** 批量改账户 */
    @Query("UPDATE txn SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun setAccountByIds(ids: List<Long>, accountId: Long)

    /** 批量改统计口径 */
    @Query("UPDATE txn SET excludeFromStats = :on WHERE id IN (:ids)")
    suspend fun setExcludeFromStatsByIds(ids: List<Long>, on: Boolean)

    @Query("UPDATE txn SET excludeFromBudget = :on WHERE id IN (:ids)")
    suspend fun setExcludeFromBudgetByIds(ids: List<Long>, on: Boolean)

    /** 批量撤销报销 / 退款（只在金额范围内，和单笔保存同一套口径） */
    @Query("UPDATE txn SET refundCents = :cents WHERE id IN (:ids)")
    suspend fun setRefundByIds(ids: List<Long>, cents: Long)

    /** 互转类型改名 / 删除时，把该账本下这个类型的历史账单一起换到新名称（N26） */
    @Query("UPDATE txn SET transferKind = :newName WHERE ledgerId = :ledgerId AND transferKind = :oldName")
    suspend fun renameTransferKindByLedger(ledgerId: Long, oldName: String, newName: String)

    @Query("SELECT * FROM txn WHERE ruleId = :ruleId ORDER BY dateMillis ASC")
    suspend fun byRule(ruleId: Long): List<Txn>

    @Query("DELETE FROM txn WHERE ruleId = :ruleId")
    suspend fun deleteByRule(ruleId: Long)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM recurring_rule WHERE ledgerId = :ledgerId ORDER BY enabled DESC, id ASC")
    fun observeByLedger(ledgerId: Long): Flow<List<RecurringRule>>

    @Query("SELECT * FROM recurring_rule WHERE enabled = 1")
    suspend fun enabledRules(): List<RecurringRule>

    /**
     * N59：只取「账本还活着」的启用规则——账本软删除后它的周期规则不再补生成账单，
     * 还原账本后照旧继续。
     */
    @Query(
        "SELECT * FROM recurring_rule WHERE enabled = 1 AND " +
            "ledgerId IN (SELECT id FROM ledger WHERE deletedAt = 0)"
    )
    suspend fun enabledRulesOfLiveLedgers(): List<RecurringRule>

    @Query("SELECT * FROM recurring_rule WHERE id = :id")
    suspend fun byId(id: Long): RecurringRule?

    @Query("SELECT COUNT(*) FROM recurring_rule WHERE ledgerId = :ledgerId")
    suspend fun count(ledgerId: Long): Int

    @Insert
    suspend fun insert(rule: RecurringRule): Long

    @Update
    suspend fun update(rule: RecurringRule)

    @Delete
    suspend fun delete(rule: RecurringRule)

    @Query("DELETE FROM recurring_rule WHERE ledgerId = :ledgerId")
    suspend fun deleteByLedger(ledgerId: Long)
}

/**
 * 账单小票 / 凭证照片（N56）。
 *
 * 一笔账单多张照片，按 txnId 取；彻底删除账单时调用方负责连文件一起清
 * （文件在 App 私有目录，见 util/ReceiptStore.kt）。
 */
@Dao
interface ReceiptPhotoDao {
    @Query("SELECT * FROM receipt_photos WHERE txnId = :txnId ORDER BY createdAt ASC, id ASC")
    fun observeByTxn(txnId: Long): Flow<List<ReceiptPhoto>>

    @Query("SELECT * FROM receipt_photos WHERE txnId = :txnId ORDER BY createdAt ASC, id ASC")
    suspend fun byTxn(txnId: Long): List<ReceiptPhoto>

    @Insert
    suspend fun insert(photo: ReceiptPhoto): Long

    @Query("DELETE FROM receipt_photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM receipt_photos WHERE txnId = :txnId")
    suspend fun deleteByTxn(txnId: Long)

    /** 删账本用：清掉该账本全部账单名下的照片记录（文件由调用方按账本维度清理） */
    @Query("DELETE FROM receipt_photos WHERE txnId IN (SELECT id FROM txn WHERE ledgerId = :ledgerId)")
    suspend fun deleteByLedger(ledgerId: Long)
}
