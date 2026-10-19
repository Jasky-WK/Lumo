package com.qingjizhang.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Ledger::class, Account::class, Category::class, Txn::class, RecurringRule::class,
        AccountTypeEntity::class, TransferKindEntity::class, Budget::class,
        TagEntity::class, TxnTagEntity::class, ArAp::class, ReceiptPhoto::class,
        AccountLedger::class
    ],
    version = 17,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun txnDao(): TxnDao
    abstract fun ruleDao(): RuleDao
    abstract fun accountTypeDao(): AccountTypeDao
    abstract fun transferKindDao(): TransferKindDao
    abstract fun budgetDao(): BudgetDao
    abstract fun tagDao(): TagDao
    abstract fun arApDao(): ArApDao
    abstract fun receiptPhotoDao(): ReceiptPhotoDao
    companion object {
        const val DB_NAME = "bookkeeping.db"

        /** v1 -> v2：账户支持多账本、计入净资产开关、隐藏、备注 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account ADD COLUMN ledgerIdsCsv TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE account ADD COLUMN countInNetWorth INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE account ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE account ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                // 已有账户默认只适用于自己所属的那个账本
                db.execSQL("UPDATE account SET ledgerIdsCsv = CAST(ledgerId AS TEXT)")
            }
        }

        /**
         * v2 -> v3：借贷改为「资金账户 ↔ 借贷账户」双账户记账。
         * 老数据里一条借贷只落在 accountId 上（没有去向），这里给它补上借贷账户，
         * 使「借出 = 资金账户流出 + 借贷账户应收」「借入 = 资金账户流入 + 借贷账户负债」成立。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE txn SET transferToAccountId = COALESCE((" +
                        "SELECT a.id FROM account a WHERE a.type = 'LEND' AND (" +
                        "(a.ledgerIdsCsv = '' AND a.ledgerId = txn.ledgerId) OR " +
                        "(',' || a.ledgerIdsCsv || ',') LIKE ('%,' || txn.ledgerId || ',%')" +
                        ") ORDER BY a.id LIMIT 1), 0) " +
                        "WHERE type = 'LEND_BORROW' AND transferToAccountId = 0"
                )
            }
        }

        /** v3 -> v4：账单支持「不计入收支 / 不计入预算」，账单与账户支持软删除（回收站） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN excludeFromStats INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE txn ADD COLUMN excludeFromBudget INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE txn ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE account ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 -> v5：分类支持预设图标，账单支持「报销 / 退款金额」 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE category ADD COLUMN iconKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE txn ADD COLUMN refundCents INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 -> v6：借贷支持「收回 / 还出」还款流水（记录这笔还款针对哪笔借贷） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN settledTxnId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6 -> v7：账户类型支持自定义（N19）。
         * - 新增 account_types 表（按账本一份类型列表）；
         * - 给已有账本播种 5 个预设类型，保证老用户升级后账户页仍能按类型分组；
         * - 账户的 type 列保持不变（仍是 TEXT），只是语义从「枚举名」改成「类型名」，
         *   用 CASE 回填老数据，账户与金额都不动。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS account_types (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO account_types (ledgerId, name, sortOrder) " +
                        "SELECT l.id, t.name, t.sortOrder FROM ledger l CROSS JOIN (" +
                        "SELECT '网络账户' AS name, 0 AS sortOrder " +
                        "UNION ALL SELECT '银行卡', 1 " +
                        "UNION ALL SELECT '现金与储值', 2 " +
                        "UNION ALL SELECT '借贷', 3 " +
                        "UNION ALL SELECT '其他', 4) t"
                )
                db.execSQL(
                    "UPDATE account SET type = CASE type " +
                        "WHEN 'ALIPAY' THEN '网络账户' " +
                        "WHEN 'WECHAT' THEN '网络账户' " +
                        "WHEN 'BANK_CARD' THEN '银行卡' " +
                        "WHEN 'CREDIT_CARD' THEN '银行卡' " +
                        "WHEN 'CASH' THEN '现金与储值' " +
                        "WHEN 'STORED_VALUE' THEN '现金与储值' " +
                        "WHEN 'LEND' THEN '借贷' " +
                        "WHEN 'OTHER' THEN '其他' " +
                        "ELSE type END"
                )
            }
        }

        /**
         * v7 -> v8：互转支持子类型（N26）、分类支持隐藏（N27）。
         * - 新增 transfer_kinds 表（按账本一份胶囊列表），给已有账本播种 4 个预设；
         * - txn 增加 transferKind 列，老账单默认「互转」，语义不变、金额不动；
         * - category 增加 hidden 列，默认 false（全部可见）。
         * 纯新增列/表，无重建表、无数据丢失。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN transferKind TEXT NOT NULL DEFAULT '互转'")
                db.execSQL("ALTER TABLE category ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS transfer_kinds (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO transfer_kinds (ledgerId, name, sortOrder) " +
                        "SELECT l.id, t.name, t.sortOrder FROM ledger l CROSS JOIN (" +
                        "SELECT '互转' AS name, 0 AS sortOrder " +
                        "UNION ALL SELECT '还款', 1 " +
                        "UNION ALL SELECT '理财买入', 2 " +
                        "UNION ALL SELECT '理财赎回', 3) t"
                )
            }
        }

        /**
         * v8 -> v9：自定义预算（N30）。
         * 只新增 budgets 表（自定义事项 + 自定义时间段的预算定义），
         * 不重建任何已有表、不改动任何历史数据，升级安装不丢数据。
         * 列结构与 Room 由 [Budget] 实体生成的建表语句严格对齐（无 DEFAULT，布尔存 INTEGER）。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS budgets (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "startMillis INTEGER NOT NULL, " +
                        "endMillis INTEGER NOT NULL, " +
                        "amountCents INTEGER NOT NULL, " +
                        "direction INTEGER NOT NULL, " +
                        "categoryId INTEGER NOT NULL, " +
                        "accountId INTEGER NOT NULL, " +
                        "keyword TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v9 -> v10：标签（N38）。
         * 只新增 tags（按账本的标签列表）与 txn_tags（账单-标签多对多关联）两张表，
         * 不重建任何已有表、不改动任何历史数据，升级安装不丢数据（禁止清库升级）。
         * 列结构与 Room 由 [TagEntity] / [TxnTagEntity] 生成的建表语句严格对齐：
         * 无 DEFAULT、布尔不存在、关联表用复合主键 PRIMARY KEY(txnId, tagId)。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tags (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS txn_tags (" +
                        "txnId INTEGER NOT NULL, " +
                        "tagId INTEGER NOT NULL, " +
                        "PRIMARY KEY(txnId, tagId))"
                )
            }
        }

        /**
         * v10 -> v11：手续费 / 优惠（N40）+ 临时预算规划（N41，v13 已随功能下线删除）。
         * - txn 增加 feeCents / discountCents 两列，老账单默认 0（口径不变、金额不动）；
         * - 新增 temp_budgets 表（该表在 MIGRATION_12_13 里被删除）。
         * 纯新增列 / 表，不重建任何已有表、不改动任何历史数据（禁止清库升级）。
         * 列结构与 Room 由 [Txn] 生成的建表语句严格对齐。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txn ADD COLUMN feeCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE txn ADD COLUMN discountCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS temp_budgets (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "amountCents INTEGER NOT NULL, " +
                        "plannedAtMillis INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v11 -> v12：应收应付（N44）。
         * 只新增 receivables 一张表，不重建任何已有表、不改动任何历史数据（禁止清库升级）。
         * 列结构与 Room 由 [ArAp] 实体生成的建表语句严格对齐：
         * 无 DEFAULT；两个枚举（direction / status）按 Room 的约定存 TEXT（枚举名）。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS receivables (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "direction TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "amountCents INTEGER NOT NULL, " +
                        "categoryId INTEGER NOT NULL, " +
                        "accountId INTEGER NOT NULL, " +
                        "counterparty TEXT NOT NULL, " +
                        "note TEXT NOT NULL, " +
                        "dateMillis INTEGER NOT NULL, " +
                        "settledAt INTEGER NOT NULL, " +
                        "settledTxnId INTEGER NOT NULL, " +
                        "settledCents INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v12 -> v13：临时预算规划功能下线（N41 被应收应付完全替代）。
         * 只删除该功能自有的 temp_budgets 表，**不触碰任何其它表与历史账单数据**（禁止清库升级）。
         * receivables 表结构不变，因此这里不重建它。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS temp_budgets")
            }
        }

        /**
         * v13 -> v14：信用卡还款日与提醒（N52）。
         * account 增加两列，老账户默认 0 / 关闭（等于功能未设置，行为不变）；
         * 纯新增列，不重建任何已有表、不动历史数据（禁止清库升级）。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE account ADD COLUMN creditDueDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE account ADD COLUMN creditRemind INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v14 -> v15：账单小票 / 凭证照片（N56）。
         * 只新增 receipt_photos 一张表，不重建任何已有表、不改动任何历史数据（禁止清库升级）。
         * 照片文件本身在 App 私有目录（files/receipts/<txnId>/），库里只存路径。
         * 列结构与 Room 由 [ReceiptPhoto] 实体生成的建表语句严格对齐（无 DEFAULT）。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS receipt_photos (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "txnId INTEGER NOT NULL, " +
                        "filePath TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v15 -> v16：账本软删除（N59）+ 查询索引（N60）。
         * - ledger 增加 deletedAt 列：删除账本改为进回收站（30 天可还原），
         *   账本下的账单 / 账户 / 分类原样保留；彻底删除才级联清空；
         * - 新建 4 个索引（CREATE INDEX 不重建表、不动数据）：
         *   txn(ledgerId, dateMillis) / txn(ledgerId, deletedAt) / txn(ruleId) /
         *   account(ledgerId, deletedAt) / receipt_photos(txnId)。
         *   索引名必须与 Room 按实体注解生成的完全一致（index_<表>_<列...>），
         *   否则升级后 schema 校验会失败——这一点由迁移测试兜底。
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ledger ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_txn_ledgerId_dateMillis ON txn(ledgerId, dateMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_txn_ledgerId_deletedAt ON txn(ledgerId, deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_txn_ruleId ON txn(ruleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_account_ledgerId_deletedAt ON account(ledgerId, deletedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_photos_txnId ON receipt_photos(txnId)")
            }
        }

        /**
         * v16 -> v17：账户 ↔ 账本关系表（P2-2）。
         * 新增 account_ledgers 表，并把老数据里的 ledgerIdsCsv 回填进去
         * （空 CSV → 只关联自己的 ledgerId；非空 → 递归拆串，过滤空段与非正数）。
         * account.ledgerIdsCsv 列**保留不动**（备份兼容 + 回填源），不清库、不动历史数据。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS account_ledgers (" +
                        "accountId INTEGER NOT NULL, " +
                        "ledgerId INTEGER NOT NULL, " +
                        "PRIMARY KEY(accountId, ledgerId))"
                )
                AccountLedgerSync.sync(db)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 库是否已就绪（S1：启动优化）。
         * 实例创建出来（含升级后的完整迁移链跑完）才为 true；启动屏靠它决定要不要继续展示。
         */
        val isReady: Boolean get() = instance != null

        /**
         * 全部迁移（N60：迁移测试用）。
         * 与下面 [get] 里 addMigrations 的顺序、内容保持一致——测试靠它把库从旧版本
         * 一级级升到最新，任何一条手写 SQL 出错都会在测试里炸出来。
         */
        val allMigrations: List<Migration> = listOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17
        )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                    MIGRATION_15_16, MIGRATION_16_17
                ).build()
                    .also { instance = it }
            }

        /** 恢复备份时需要关闭并重开数据库 */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
