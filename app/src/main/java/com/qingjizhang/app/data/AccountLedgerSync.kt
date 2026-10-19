package com.qingjizhang.app.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 账户 ↔ 账本关系表的同步（P2-2）。
 *
 * 背景：account.ledgerIdsCsv 用逗号字符串存「这个账户适用于哪些账本」，
 * 查询只能 LIKE、改起来全是字符串操作。v17 起新增 [AccountLedger] 关系表承担查询，
 * CSV 列**保留不动**（备份文件兼容 + 回填的数据源），由本对象把 CSV 幂等同步进表：
 *
 * - 启动时（BookkeepingApp.onCreate）同步一次：老库升级、恢复旧备份后都能补齐；
 * - 账户写入后（Repository.addAccount / updateAccount）同步一次：改完立刻生效；
 * - 同步是「全表重建」（DELETE + INSERT），账户表很小，代价可忽略；
 * - 收尾会清掉指向不存在账本的悬空行。
 */
object AccountLedgerSync {

    /** 全表重建：CSV → account_ledgers。幂等，可反复调用。 */
    fun sync(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM account_ledgers")
        // 没写 CSV 的账户：只适用于自己的 ledgerId
        db.execSQL(
            "INSERT OR IGNORE INTO account_ledgers (accountId, ledgerId) " +
                "SELECT id, ledgerId FROM account WHERE ledgerIdsCsv IS NULL OR TRIM(ledgerIdsCsv) = ''"
        )
        // 写了 CSV 的账户：递归拆串（SQLite 没有 split，用递归 CTE）
        db.execSQL(
            "INSERT OR IGNORE INTO account_ledgers (accountId, ledgerId) " +
                "WITH RECURSIVE split(id, name, rest) AS (" +
                "SELECT id, '', ledgerIdsCsv || ',' FROM account WHERE TRIM(ledgerIdsCsv) != '' " +
                "UNION ALL " +
                "SELECT id, substr(rest, 1, instr(rest, ',') - 1), substr(rest, instr(rest, ',') + 1) " +
                "FROM split WHERE rest != ''" +
                ") " +
                "SELECT id, CAST(name AS INTEGER) FROM split " +
                "WHERE name != '' AND CAST(name AS INTEGER) > 0"
        )
        // 清掉指向不存在账本的悬空行（账本被彻底删除等）
        db.execSQL(
            "DELETE FROM account_ledgers WHERE ledgerId NOT IN (SELECT id FROM ledger)"
        )
    }
}
