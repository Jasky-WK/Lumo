package com.qingjizhang.app.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.qingjizhang.app.BuildConfig
import com.qingjizhang.app.util.DateFmt
import com.qingjizhang.app.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupFile(
    val file: File,
    val name: String,
    val sizeText: String,
    val timeText: String,
    val dbBytes: Long
)

/**
 * 备份 = 数据库文件打包成 zip，存在 App 私有外部目录（无需任何权限），
 * 另存一份到手机「下载/Lumo备份」，方便自己拷到电脑或网盘。
 * 全程不联网、不上传。
 */
object BackupManager {

    const val FOLDER = "Lumo备份"

    private fun backupDir(context: Context): File {
        // 外部私有目录可能不可用（存储未挂载），回退到内部目录，保证备份一定能落盘
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun databasesDir(context: Context): File = context.getDatabasePath(AppDatabase.DB_NAME).parentFile!!

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format("%d KB", bytes / 1024)
        else -> "$bytes B"
    }

    fun fileName(now: LocalDateTime = LocalDateTime.now()): String =
        "Lumo备份-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".zip"

    /** 列出现有备份（App 私有目录内），按时间倒序 */
    fun list(context: Context): List<BackupFile> =
        backupDir(context).listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                BackupFile(
                    file = f,
                    name = f.name,
                    sizeText = sizeText(f.length()),
                    timeText = DateFmt.isoDateTime(f.lastModified()),
                    dbBytes = f.length()
                )
            } ?: emptyList()

    /**
     * 打包当前数据库到 zip。
     * @return 生成的备份文件
     */
    suspend fun createBackup(context: Context, toDownloads: Boolean = true): File =
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            val target = File(backupDir(context), fileName())
            ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("meta.txt"))
                zos.write(
                    ("Lumo备份\n时间: " + DateFmt.isoDateTime(System.currentTimeMillis()) +
                        "\n数据库: " + AppDatabase.DB_NAME + "\n版本: 1\n").toByteArray()
                )
                zos.closeEntry()
                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry(AppDatabase.DB_NAME))
                    dbFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            if (toDownloads) {
                runCatching { copyToDownloads(context, target) }
            }
            target
        }

    /** 恢复前自动给当前数据存一份快照，误操作可回退 */
    suspend fun snapshotBeforeRestore(context: Context): File =
        withContext(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val target = File(backupDir(context), "恢复前快照-$stamp.zip")
            ZipOutputStream(FileOutputStream(target).buffered()).use { zos ->
                zos.putNextEntry(ZipEntry("meta.txt"))
                zos.write("恢复前自动快照\n时间: ${DateFmt.isoDateTime(System.currentTimeMillis())}\n".toByteArray())
                zos.closeEntry()
                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry(AppDatabase.DB_NAME))
                    dbFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            target
        }

    /**
     * 用备份覆盖当前数据库。调用方需要在成功后重新打开数据库。
     */
    suspend fun restore(context: Context, zipFile: File) = withContext(Dispatchers.IO) {
        snapshotBeforeRestore(context)
        AppDatabase.closeInstance()
        val dbDir = databasesDir(context)
        val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
        // 清掉主库与 WAL
        File(dbDir, AppDatabase.DB_NAME).delete()
        File(dbDir, AppDatabase.DB_NAME + "-wal").delete()
        File(dbDir, AppDatabase.DB_NAME + "-shm").delete()
        File(dbDir, AppDatabase.DB_NAME + "-journal").delete()

        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == AppDatabase.DB_NAME) {
                    FileOutputStream(dbFile).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 从 SAF 选中的文件恢复 */
    suspend fun restoreFromUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "restore-import.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { out -> input.copyTo(out) }
        } ?: error("无法读取所选文件")
        restore(context, temp)
        temp.delete()
    }

    suspend fun delete(file: File) = withContext(Dispatchers.IO) { file.delete() }

    /** 备份目录（用于 FileProvider 分享） */
    fun dir(context: Context): File = backupDir(context)

    /** 复制一份到手机「下载/Lumo备份」，方便用户自己取用 */
    private fun copyToDownloads(context: Context, source: File, mime: String = "application/zip") {
        val bytes = source.readBytes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, source.name).writeBytes(bytes)
        }
    }

    // ==================================================================================
    // N23 完整备份与恢复（.bak = JSON：全库数据 + 全部设置）
    //
    // 与「导出账单 CSV」（数据交换格式）不同：完整备份保留账户配置（类型 / 计入净资产 /
    // 隐藏 / 排序 / 备注 / 初始余额）、借贷双端语义、还款关联、报销退款、回收站数据与全部设置，
    // 换新手机 / 重装后导入即可完美复原，不会出现「未知账户」。
    //
    // 表清单一律动态：导出时从 sqlite_master 枚举用户表，导入时同样动态写回；
    // 列级按「当前库的列」对齐（备份没有的列填默认值，备份多出来的列忽略），
    // 所以以后新增表 / 新增列都不用改这里。
    //
    // 导入的写入全部在一个 SQLite 事务里完成，事务内做完整性校验，任何一步失败都整体回滚；
    // 导入前还会先把当前库留档到日志目录（DbGuard.snapshotBeforeImport）。
    //
    // 备份里出现了当前库没有的表时：**跳过并在结果里明确提示**（不静默、也不整体失败）。
    // 理由：备份可能来自更新的版本（例如以后新增 transfer_kinds 表），跳过未知表仍能让用户
    // 先把核心账目恢复回来，同时把「哪些表被跳过」如实告诉用户，避免无声丢数据。
    // ==================================================================================

    const val FULL_SUFFIX = ".bak"
    const val FULL_KIND = "full-backup"
    const val FULL_FORMAT_VERSION = 1

    private const val PREFS_NAME = "bookkeeping_prefs"
    private const val BLOB_PREFIX = "b64:"

    /** 顺序与 ui/theme/Theme.kt 里的 ThemeColor / ThemeMode 一致（只为预览文案，不参与数据） */
    private val THEME_COLOR_LABELS = listOf("蓝", "绿", "白")
    private val THEME_MODE_LABELS = listOf("跟随系统", "浅色", "深色")

    private enum class Kind { INT, REAL, TEXT, BLOB }

    /** 完整备份文件（.bak） */
    data class FullBackupFile(
        val file: File,
        val name: String,
        val sizeText: String,
        val timeText: String
    )

    /** 导入前预览：覆盖之前先让用户看清楚这份备份里有什么 */
    data class FullBackupPreview(
        val fileName: String,
        val formatVersion: Int,
        val schemaVersion: Int,
        val appVersion: String,
        val createdAtText: String,
        val tableRows: List<Pair<String, Int>>,
        val ledgers: Int,
        val accounts: Int,
        val categories: Int,
        val txns: Int,
        val lends: Int,
        val settlements: Int,
        val rules: Int,
        val accountTypes: Int,
        val trashTxns: Int,
        val trashAccounts: Int,
        val settings: List<String>,
        val warnings: List<String>
    )

    /** 导入结果：成功 / 失败都带明细，失败时说明为什么回滚了 */
    data class FullImportResult(
        val success: Boolean,
        val error: String? = null,
        val tableRows: List<Pair<String, Int>> = emptyList(),
        val skippedTables: List<String> = emptyList(),
        val filledColumns: List<String> = emptyList(),
        val droppedColumns: List<String> = emptyList(),
        val safetyFiles: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    /** 备份里的单张表 */
    private class TableData(
        val columns: List<String>,
        val types: Map<String, String>,
        val rows: List<JSONArray>
    ) {
        fun text(row: JSONArray, col: String): String {
            val i = columns.indexOf(col)
            return if (i < 0) "" else row.optString(i, "")
        }

        fun long(row: JSONArray, col: String): Long = text(row, col).toLongOrNull() ?: 0L

        fun countWhere(predicate: (JSONArray) -> Boolean): Int = rows.count(predicate)

        fun idSet(): Set<Long> {
            val i = columns.indexOf("id")
            if (i < 0) return emptySet()
            return rows.mapNotNull { it.optString(i, "").toLongOrNull() }.toSet()
        }
    }

    /** 写入计划：目标表 + 当前库的列 + 「这一列在备份里的下标（-1 = 备份没有）」+ 待写入的行 */
    private class WritePlan(
        val table: String,
        val cols: List<ColumnInfo>,
        val srcIndex: IntArray,
        val backupTypes: Map<String, String>,
        val rows: List<JSONArray>
    )

    /**
     * 引用完整性规则。
     *
     * [fatal] = true：账单 / 周期规则引用的账户。这是 N23 明确要求「账户引用完整随包携带、
     *   导入后不出现未知账户」的部分，而且 App 的任何正常操作都不会产生这种悬空引用，
     *   所以一旦悬空就说明备份是坏的 → 直接拒绝导入（还没动数据）或事务内回滚。
     * [fatal] = false：其余引用只提示、不阻断。因为 App 自身的一些操作会留下这种历史数据：
     *   删掉一级分类后，挂在被删子分类下的老账单分类 id 仍是原样；把某笔借贷彻底删除后，
     *   它的还款流水 settledTxnId 仍指向那个 id；删账本时跨账本共用的老账户 ledgerId 也可能悬空。
     *   备份的语义是「原样复原」，源数据里既有的历史问题不该让用户连自己的账都导不回来，
     *   所以这类只如实提示。
     */
    private class RefRule(
        val table: String,
        val col: String,
        val ref: String,
        val fatal: Boolean,
        val label: String
    )

    private val REF_RULES = listOf(
        RefRule("txn", "accountId", "account", true, "账单的资金账户"),
        RefRule("txn", "transferToAccountId", "account", true, "账单的对方账户"),
        RefRule("recurring_rule", "accountId", "account", true, "周期规则的账户"),
        RefRule("recurring_rule", "transferToAccountId", "account", true, "周期规则的对方账户"),
        RefRule("txn", "ledgerId", "ledger", false, "账单的账本"),
        RefRule("txn", "settledTxnId", "txn", false, "还款关联的借贷账单"),
        RefRule("txn", "categoryId", "category", false, "账单的分类"),
        RefRule("txn", "ruleId", "recurring_rule", false, "账单的周期规则"),
        RefRule("account", "ledgerId", "ledger", false, "账户的账本"),
        RefRule("account_ledgers", "accountId", "account", false, "账户的账本关系"),
        RefRule("account_ledgers", "ledgerId", "ledger", false, "账户的账本关系"),
        RefRule("category", "ledgerId", "ledger", false, "分类的账本"),
        RefRule("category", "parentId", "category", false, "分类的上级分类"),
        RefRule("account_types", "ledgerId", "ledger", false, "账户类型的账本"),
        RefRule("recurring_rule", "ledgerId", "ledger", false, "周期规则的账本"),
        RefRule("recurring_rule", "categoryId", "category", false, "周期规则的分类")
    )

    // ---------------------------------------------------------------- 导出

    fun fullFileName(now: LocalDateTime = LocalDateTime.now()): String =
        "Lumo完整备份-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + FULL_SUFFIX

    /** 已有的完整备份（App 私有备份目录内），按时间倒序 */
    fun listFull(context: Context): List<FullBackupFile> =
        backupDir(context).listFiles { f -> f.isFile && f.name.endsWith(FULL_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f -> FullBackupFile(f, f.name, sizeText(f.length()), DateFmt.isoDateTime(f.lastModified())) }
            ?: emptyList()

    suspend fun createFullBackup(context: Context, toDownloads: Boolean = true): File =
        withContext(Dispatchers.IO) {
            val json = buildFullJson(context)
            val target = File(backupDir(context), fullFileName())
            target.writeText(json, Charsets.UTF_8)
            // 同时丢一份到「下载/Lumo备份」，换手机时可以直接拷走
            if (toDownloads) runCatching { copyToDownloads(context, target, "application/json") }
            target
        }

    /**
     * v2.9.0：只生成完整备份的 JSON 内容（不落盘），供「每月自动备份」写到公开目录。
     * 与手动完整备份同一份格式，恢复走同一条预览 → 校验 → 回滚路径。
     */
    suspend fun fullBackupJson(context: Context): String =
        withContext(Dispatchers.IO) { buildFullJson(context) }

    @SuppressLint("RestrictedApi")
    private fun buildFullJson(context: Context): String {
        // 走 Room 自己的连接读，看到的一定是当前最新数据（含还在 WAL 里的那些）
        val raw = AppDatabase.get(context.applicationContext).openHelper.writableDatabase
        val root = JSONObject()
        root.put("app", "qingjizhang")
        root.put("kind", FULL_KIND)
        root.put("formatVersion", FULL_FORMAT_VERSION)
        root.put("createdAt", System.currentTimeMillis())
        root.put("createdAtText", DateFmt.isoDateTime(System.currentTimeMillis()))
        root.put("appVersionName", BuildConfig.VERSION_NAME)
        // Room 的 user_version：导入时用来告诉用户备份来自哪个库结构版本
        root.put("schemaVersion", raw.version)
        root.put("prefs", dumpPrefs(context))
        val tables = JSONObject()
        DbSql.userTables(raw).forEach { name -> tables.put(name, dumpTable(raw, name)) }
        root.put("tables", tables)
        return root.toString()
    }

    /** 整表导出（含回收站里 deletedAt>0 的行）：列名、列类型、全部行 */
    private fun dumpTable(raw: SupportSQLiteDatabase, table: String): JSONObject {
        val typeOf = DbSql.tableInfo(raw, table).associate { it.name to it.type }
        val obj = JSONObject()
        val cols = JSONArray()
        val types = JSONObject()
        val rows = JSONArray()
        raw.query("SELECT * FROM `$table`").use { c ->
            // 列名直接用游标给的（与每一行的下标严格对齐），类型按列名取
            val names = c.columnNames
            val kinds = names.map { kindOf(typeOf[it] ?: "") }
            names.forEach { n ->
                cols.put(n)
                types.put(n, typeOf[n] ?: "")
            }
            while (c.moveToNext()) {
                val row = JSONArray()
                for (i in names.indices) {
                    // 一律按字符串存（类型写在 types 里），避免 JSON 数字精度问题
                    val v: Any = when {
                        c.isNull(i) -> JSONObject.NULL
                        kinds[i] == Kind.BLOB ->
                            BLOB_PREFIX + Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
                        else -> c.getString(i) ?: ""
                    }
                    row.put(v)
                }
                rows.put(row)
            }
        }
        obj.put("columns", cols)
        obj.put("types", types)
        obj.put("rows", rows)
        return obj
    }

    /** 设置项全量导出（不逐 key 硬编码，以后加设置项也不用改）：key -> {t:类型, v:值} */
    private fun dumpPrefs(context: Context): JSONObject {
        val values = JSONObject()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val entry = JSONObject()
            when (v) {
                is Boolean -> { entry.put("t", "bool"); entry.put("v", v.toString()) }
                is Int -> { entry.put("t", "int"); entry.put("v", v.toString()) }
                is Long -> { entry.put("t", "long"); entry.put("v", v.toString()) }
                is Float -> { entry.put("t", "float"); entry.put("v", v.toString()) }
                is String -> { entry.put("t", "string"); entry.put("v", v) }
                is Set<*> -> {
                    entry.put("t", "set")
                    val arr = JSONArray()
                    v.forEach { s -> arr.put(s?.toString() ?: "") }
                    entry.put("v", arr)
                }
                else -> return@forEach
            }
            values.put(k, entry)
        }
        val files = JSONObject()
        files.put(PREFS_NAME, values)
        val root = JSONObject()
        root.put("files", files)
        return root
    }

    // ---------------------------------------------------------------- 预览

    suspend fun inspectFullBackup(file: File): FullBackupPreview =
        withContext(Dispatchers.IO) { buildPreview(file.name, JSONObject(file.readText())) }

    /** 从 SAF 选中的文件预览（先把文件复制到缓存目录，导入时直接复用这份副本） */
    suspend fun inspectFullBackupFromUri(context: Context, uri: Uri): Pair<FullBackupPreview, File> =
        withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "import-full$FULL_SUFFIX")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out) }
            } ?: error("无法读取所选文件")
            buildPreview(temp.name, JSONObject(temp.readText())) to temp
        }

    private fun buildPreview(fileName: String, root: JSONObject): FullBackupPreview {
        if (root.optString("app") != "qingjizhang" || root.optString("kind") != FULL_KIND) {
            error("这不是Lumo的「完整备份」文件（旧版 .zip 快照请用「数据库快照」恢复）")
        }
        val tables = parseTables(root.optJSONObject("tables") ?: JSONObject())
        val warnings = mutableListOf<String>()
        val formatVersion = root.optInt("formatVersion", 0)
        if (formatVersion > FULL_FORMAT_VERSION) {
            warnings += "备份文件由更新的版本生成（formatVersion=$formatVersion），可能有当前版本读不出的内容"
        }
        val ledger = tables["ledger"]
        val account = tables["account"]
        val category = tables["category"]
        val txn = tables["txn"]
        val rule = tables["recurring_rule"]
        val accountTypes = tables["account_types"]
        val lendCount = txn?.let { t -> t.countWhere { t.text(it, "type") == "LEND_BORROW" } } ?: 0
        val settlementCount = txn?.let { t -> t.countWhere { t.long(it, "settledTxnId") > 0L } } ?: 0
        val trashTxns = txn?.let { t -> t.countWhere { t.long(it, "deletedAt") > 0L } } ?: 0
        val trashAccounts = account?.let { a -> a.countWhere { a.long(it, "deletedAt") > 0L } } ?: 0
        return FullBackupPreview(
            fileName = fileName,
            formatVersion = formatVersion,
            schemaVersion = root.optInt("schemaVersion", 0),
            appVersion = root.optString("appVersionName", "未知"),
            createdAtText = root.optString("createdAtText", DateFmt.isoDateTime(root.optLong("createdAt", 0L))),
            tableRows = tables.map { it.key to it.value.rows.size },
            ledgers = ledger?.rows?.size ?: 0,
            accounts = account?.rows?.size ?: 0,
            categories = category?.rows?.size ?: 0,
            txns = txn?.rows?.size ?: 0,
            lends = lendCount,
            settlements = settlementCount,
            rules = rule?.rows?.size ?: 0,
            accountTypes = accountTypes?.rows?.size ?: 0,
            trashTxns = trashTxns,
            trashAccounts = trashAccounts,
            settings = settingsSummary(root, ledger),
            warnings = warnings
        )
    }

    private fun settingsSummary(root: JSONObject, ledger: TableData?): List<String> {
        val prefFile = root.optJSONObject("prefs")
            ?.optJSONObject("files")?.optJSONObject(PREFS_NAME)
        fun pref(key: String): String? = prefFile?.optJSONObject(key)?.optString("v")
        val out = mutableListOf<String>()
        pref("theme_color")?.toIntOrNull()?.let {
            out += "主题色：" + THEME_COLOR_LABELS.getOrElse(it) { "未知" }
        }
        pref("theme_mode")?.toIntOrNull()?.let {
            out += "深浅模式：" + THEME_MODE_LABELS.getOrElse(it) { "未知" }
        }
        pref("note_memory")?.let { out += "备注记忆：" + if (it == "true") "开" else "关" }
        pref("recurring_reminder_on")?.let { out += "周期提醒：" + if (it == "true") "开" else "关" }
        pref("current_ledger_id")?.let { out += "当前账本 id：$it" }
        if (ledger != null && ledger.rows.isNotEmpty()) {
            val budget = ledger.rows.maxOf { ledger.long(it, "monthlyBudgetCents") }
            out += "月度预算：" + if (budget > 0L) Money.withSymbol(budget) else "未设置"
        }
        out += "设置项共 " + (prefFile?.length() ?: 0) + " 项"
        return out
    }

    // ---------------------------------------------------------------- 导入（覆盖式，全在一个事务里）

    @SuppressLint("RestrictedApi")
    suspend fun importFullBackup(context: Context, file: File): FullImportResult =
        withContext(Dispatchers.IO) {
            // ---- 1. 解析 + 格式校验 ----
            val root = runCatching { JSONObject(file.readText()) }.getOrElse {
                return@withContext FullImportResult(false, error = "备份文件读不出来：" + (it.message ?: "格式错误"))
            }
            if (root.optString("app") != "qingjizhang" || root.optString("kind") != FULL_KIND) {
                return@withContext FullImportResult(
                    false,
                    error = "这不是Lumo的「完整备份」文件；旧版 .zip 快照请用「数据库快照」恢复"
                )
            }
            val tablesObj = root.optJSONObject("tables")
                ?: return@withContext FullImportResult(false, error = "备份文件里没有数据表")
            val tables = runCatching { parseTables(tablesObj) }.getOrElse {
                return@withContext FullImportResult(false, error = "备份内容已损坏：" + (it.message ?: ""))
            }
            if (!tables.containsKey("ledger")) {
                return@withContext FullImportResult(false, error = "备份里没有账本数据，无法导入")
            }
            // 备份自身的引用完整性先查一遍：关键引用（账单 / 周期规则的账户）悬空就直接拒绝，绝不动当前数据
            val dangling = backupDangling(tables)
            val fatalIssues = REF_RULES.indices
                .filter { REF_RULES[it].fatal && (dangling[it] ?: 0) > 0 }
                .map { "${REF_RULES[it].label}不存在（${dangling[it]} 条）" }
            if (fatalIssues.isNotEmpty()) {
                return@withContext FullImportResult(
                    false, error = "备份内容校验未通过，已中止导入：" + fatalIssues.joinToString("；")
                )
            }
            // 备份里本来就悬空的非关键引用：原样导入，但如实提示
            val preWarnings = REF_RULES.indices
                .filter { !REF_RULES[it].fatal && (dangling[it] ?: 0) > 0 }
                .map { "${REF_RULES[it].label}在备份里就已经不存在（${dangling[it]} 条，已按原样导入）" }

            // ---- 2. 导入前先把当前库留档（N25，回滚 / 事后排查的前提）----
            val safety = runCatching { DbGuard.snapshotBeforeImport(context) }.getOrDefault(emptyList())

            // ---- 3. 按当前库的结构对齐列（动态，不硬编码表名与列名）----
            val raw = AppDatabase.get(context.applicationContext).openHelper.writableDatabase
            val live = DbSql.userTables(raw)
            val plans = mutableListOf<WritePlan>()
            val skipped = mutableListOf<String>()
            val filled = mutableListOf<String>()
            val dropped = mutableListOf<String>()
            tables.forEach { (name, data) ->
                if (!live.contains(name)) {
                    skipped += name
                    return@forEach
                }
                val info = DbSql.tableInfo(raw, name)
                val src = IntArray(info.size) { -1 }
                data.columns.forEachIndexed { bi, col ->
                    val li = info.indexOfFirst { it.name == col }
                    if (li >= 0) src[li] = bi else dropped += "$name.$col"
                }
                info.forEachIndexed { li, c -> if (src[li] < 0) filled += "$name.${c.name}" }
                plans += WritePlan(name, info, src, data.types, data.rows)
            }
            if (plans.isEmpty()) {
                return@withContext FullImportResult(
                    false, error = "备份里的数据表在当前版本都不存在，已中止导入", safetyFiles = safety
                )
            }

            // ---- 4. 一个事务写回 + 事务内校验，失败整体回滚 ----
            try {
                raw.beginTransaction()
                try {
                    plans.forEach { writeTable(raw, it) }
                    resetSequences(raw, plans.map { it.table })
                    verifyAfterWrite(raw, plans, dangling)?.let { throw IllegalStateException(it) }
                    raw.setTransactionSuccessful()
                } finally {
                    raw.endTransaction()
                }
            } catch (e: Exception) {
                return@withContext FullImportResult(
                    false,
                    error = "导入失败，已整体回滚（当前数据没有变化）：" + (e.message ?: e.javaClass.simpleName),
                    skippedTables = skipped,
                    filledColumns = filled,
                    droppedColumns = dropped,
                    safetyFiles = safety
                )
            }

            // ---- 5. 设置写回（SharedPreferences 不在 SQLite 事务里）----
            val warnings = mutableListOf<String>()
            warnings += preWarnings
            runCatching { applyPrefs(context, root.optJSONObject("prefs")) }
                .onFailure { warnings += "设置项写回失败：" + (it.message ?: "") }
            runCatching { fixCurrentLedger(context, raw) }
                .onFailure { warnings += "当前账本修正失败：" + (it.message ?: "") }
            if (skipped.isNotEmpty()) {
                warnings += "备份里有当前版本不支持的表，已跳过：" + skipped.joinToString("、")
            }
            keyFieldWarnings(raw).forEach { warnings += it }
            lendWarnings(raw).forEach { warnings += it }

            FullImportResult(
                success = true,
                tableRows = plans.map { it.table to it.rows.size },
                skippedTables = skipped,
                filledColumns = filled,
                droppedColumns = dropped,
                safetyFiles = safety,
                warnings = warnings
            )
        }

    private fun parseTables(obj: JSONObject): Map<String, TableData> {
        val out = LinkedHashMap<String, TableData>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val t = obj.optJSONObject(name) ?: continue
            val colsArr = t.optJSONArray("columns") ?: continue
            val columns = (0 until colsArr.length()).map { colsArr.getString(it) }
            val typesObj = t.optJSONObject("types")
            val types = columns.associateWith { typesObj?.optString(it, "") ?: "" }
            val rowsArr = t.optJSONArray("rows") ?: JSONArray()
            val rows = (0 until rowsArr.length()).map { rowsArr.getJSONArray(it) }
            out[name] = TableData(columns, types, rows)
        }
        return out
    }

    /**
     * 备份内部的悬空引用统计（规则下标 -> 条数）。
     * 导入前先查一遍：关键引用（账户 / 账本）悬空直接拒绝，绝不动当前数据。
     */
    private fun backupDangling(tables: Map<String, TableData>): Map<Int, Int> {
        val ids = HashMap<String, Set<Long>>()
        tables.forEach { (name, d) -> ids[name] = d.idSet() }
        val out = HashMap<Int, Int>()
        REF_RULES.forEachIndexed { i, rule ->
            val d = tables[rule.table] ?: return@forEachIndexed
            if (d.columns.indexOf(rule.col) < 0) return@forEachIndexed
            val refIds = ids[rule.ref] ?: return@forEachIndexed
            var bad = 0
            d.rows.forEach { r ->
                val id = d.long(r, rule.col)
                if (id != 0L && !refIds.contains(id)) bad++
            }
            if (bad > 0) out[i] = bad
        }
        return out
    }

    /** 整表覆盖写入：先清空该表，再按列绑定批量插入 */
    private fun writeTable(raw: SupportSQLiteDatabase, plan: WritePlan) {
        raw.execSQL("DELETE FROM `${plan.table}`")
        if (plan.rows.isEmpty()) return
        val cols = plan.cols.joinToString(",") { "`${it.name}`" }
        val marks = plan.cols.joinToString(",") { "?" }
        val stmt = raw.compileStatement("INSERT INTO `${plan.table}` ($cols) VALUES ($marks)")
        try {
            plan.rows.forEach { row ->
                stmt.clearBindings()
                plan.cols.forEachIndexed { i, c ->
                    val from = plan.srcIndex[i]
                    if (from < 0) bindDefault(stmt, i + 1, c.type)
                    else bindValue(stmt, i + 1, row.opt(from), plan.backupTypes[c.name] ?: c.type)
                }
                stmt.executeInsert()
            }
        } finally {
            stmt.close()
        }
    }

    /** 备份缺列时用「类型默认值」补齐（不会写入 NULL，避免撞上 NOT NULL 约束） */
    private fun bindDefault(stmt: SupportSQLiteStatement, index: Int, type: String) {
        when (kindOf(type)) {
            Kind.INT -> stmt.bindLong(index, 0L)
            Kind.REAL -> stmt.bindDouble(index, 0.0)
            Kind.BLOB -> stmt.bindBlob(index, ByteArray(0))
            Kind.TEXT -> stmt.bindString(index, "")
        }
    }

    private fun bindValue(stmt: SupportSQLiteStatement, index: Int, value: Any?, type: String) {
        if (value == null || value == JSONObject.NULL) {
            bindDefault(stmt, index, type)
            return
        }
        val text = value.toString()
        when (kindOf(type)) {
            Kind.INT -> stmt.bindLong(index, text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong() ?: 0L)
            Kind.REAL -> stmt.bindDouble(index, text.toDoubleOrNull() ?: 0.0)
            Kind.BLOB -> {
                val b64 = if (text.startsWith(BLOB_PREFIX)) text.substring(BLOB_PREFIX.length) else text
                stmt.bindBlob(index, runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrDefault(ByteArray(0)))
            }
            Kind.TEXT -> stmt.bindString(index, text)
        }
    }

    /** 自增序号对齐（Room 的 id 是 AUTOINCREMENT，写回后把 sqlite_sequence 校正到 max(id)） */
    private fun resetSequences(raw: SupportSQLiteDatabase, tables: List<String>) {
        if (!DbSql.tableExists(raw, "sqlite_sequence")) return
        tables.forEach { t ->
            runCatching {
                if (DbSql.tableInfo(raw, t).none { it.name == "id" }) return@runCatching
                val maxId = DbSql.scalarLong(raw, "SELECT MAX(id) FROM `$t`")
                raw.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf<Any?>(t))
                if (maxId != null) {
                    raw.execSQL("INSERT INTO sqlite_sequence(name, seq) VALUES (?, ?)", arrayOf<Any?>(t, maxId))
                }
            }
        }
    }

    /**
     * 导入后完整性校验（**在事务内**跑，返回错误即抛异常回滚）：
     * 1. 关键表行数与备份完全一致（少一行多一行都算写坏）；
     * 2. 借贷关联完整性：accountId / transferToAccountId / settledTxnId 等引用在导入后**不能比备份里更差**
     *    （备份里本来就有的悬空引用原样保留，超出说明写入出问题 → 回滚）。
     */
    private fun verifyAfterWrite(
        raw: SupportSQLiteDatabase,
        plans: List<WritePlan>,
        expectedDangling: Map<Int, Int>
    ): String? {
        plans.forEach { p ->
            val n = DbSql.count(raw, "SELECT COUNT(*) FROM `${p.table}`")
            if (n != p.rows.size.toLong()) {
                return "表 ${p.table} 行数不一致（期望 ${p.rows.size}，实际 $n）"
            }
        }
        REF_RULES.forEachIndexed { i, rule ->
            if (!DbSql.tableExists(raw, rule.table) || !DbSql.tableExists(raw, rule.ref)) return@forEachIndexed
            if (DbSql.tableInfo(raw, rule.table).none { it.name == rule.col }) return@forEachIndexed
            val n = DbSql.count(
                raw,
                "SELECT COUNT(*) FROM `${rule.table}` x LEFT JOIN `${rule.ref}` y ON x.`${rule.col}` = y.`id` " +
                    "WHERE x.`${rule.col}` <> 0 AND y.`id` IS NULL"
            )
            val expected = (expectedDangling[i] ?: 0).toLong()
            if (n > expected) return "${rule.label}在导入后丢失了关联（${n} 条，导入前 ${expected} 条）"
        }
        return null
    }

    /** 关键字段缺失（空名称之类）：不阻断导入，但如实提示 */
    private fun keyFieldWarnings(raw: SupportSQLiteDatabase): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val blankLedgers = DbSql.count(raw, "SELECT COUNT(*) FROM ledger WHERE name IS NULL OR TRIM(name) = ''")
            if (blankLedgers > 0L) out += "有 $blankLedgers 个账本没有名称"
            if (DbSql.tableExists(raw, "account") && DbSql.tableInfo(raw, "account").any { it.name == "name" }) {
                val blankAccounts =
                    DbSql.count(raw, "SELECT COUNT(*) FROM account WHERE name IS NULL OR TRIM(name) = ''")
                if (blankAccounts > 0L) out += "有 $blankAccounts 个账户没有名称"
            }
        }
        return out
    }

    /** 老数据里可能存在的「借贷只有一端」情况：不阻断导入，但如实提示 */
    private fun lendWarnings(raw: SupportSQLiteDatabase): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val noAccount = DbSql.count(raw, "SELECT COUNT(*) FROM txn WHERE accountId = 0")
            if (noAccount > 0L) out += "有 $noAccount 笔账单没有资金账户（老数据遗留）"
            val lendOneSide = DbSql.count(
                raw,
                "SELECT COUNT(*) FROM txn WHERE type = 'LEND_BORROW' AND transferToAccountId = 0"
            )
            if (lendOneSide > 0L) out += "有 $lendOneSide 笔借贷缺少对方账户（老数据遗留）"
        }
        return out
    }

    /** 设置写回（覆盖同名 key；类型按备份里记录的类型还原） */
    private fun applyPrefs(context: Context, prefs: JSONObject?) {
        val files = prefs?.optJSONObject("files") ?: return
        val fileNames = files.keys()
        while (fileNames.hasNext()) {
            val fileName = fileNames.next()
            val values = files.optJSONObject(fileName) ?: continue
            val editor = context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = values.optJSONObject(k) ?: continue
                val raw = entry.optString("v", "")
                when (entry.optString("t", "")) {
                    "bool" -> editor.putBoolean(k, raw == "true")
                    "int" -> raw.toIntOrNull()?.let { editor.putInt(k, it) }
                    "long" -> raw.toLongOrNull()?.let { editor.putLong(k, it) }
                    "float" -> raw.toFloatOrNull()?.let { editor.putFloat(k, it) }
                    "set" -> {
                        val arr = entry.optJSONArray("v")
                        val set = mutableSetOf<String>()
                        if (arr != null) for (i in 0 until arr.length()) set += arr.optString(i, "")
                        editor.putStringSet(k, set)
                    }
                    "string" -> editor.putString(k, raw)
                }
            }
            editor.commit()
        }
    }

    /** 当前账本 id 若在新数据里不存在，就落回第一个账本，避免重启后看到空账本 */
    private fun fixCurrentLedger(context: Context, raw: SupportSQLiteDatabase) {
        if (!DbSql.tableExists(raw, "ledger")) return
        val first = DbSql.scalarLong(raw, "SELECT id FROM ledger ORDER BY isDefault DESC, id ASC LIMIT 1")
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = sp.getLong("current_ledger_id", 0L)
        val exists = current > 0L &&
            DbSql.count(raw, "SELECT COUNT(*) FROM ledger WHERE id = $current") > 0L
        if (!exists && first != null) {
            sp.edit().putLong("current_ledger_id", first).commit()
        }
    }

    private fun kindOf(type: String): Kind {
        val t = type.uppercase(Locale.US)
        return when {
            t.contains("INT") -> Kind.INT
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> Kind.TEXT
            t.contains("BLOB") -> Kind.BLOB
            t.contains("REAL") || t.contains("FLOA") || t.contains("DOUB") -> Kind.REAL
            else -> Kind.TEXT
        }
    }
}
