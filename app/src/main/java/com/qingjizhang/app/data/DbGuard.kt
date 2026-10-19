package com.qingjizhang.app.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qingjizhang.app.util.DateFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一列的结构信息（PRAGMA table_info 的结果里我们关心的部分） */
data class ColumnInfo(
    val name: String,
    val type: String,
    val notNull: Boolean
)

/** 数据库自检结果（N25） */
data class DbCheckResult(
    val ok: Boolean,
    val problems: List<String>,
    /** 已经留档到日志目录的文件（人可读位置，如「下载/Lumo日志/xxx」） */
    val dumped: List<String>
) {
    val summary: String get() = if (ok) "数据库自检通过" else problems.joinToString("；")
}

/**
 * 数据库守护（N25）：启动自检 + 异常时把库文件留档到「下载/Lumo日志」+ 导入前的整库快照。
 *
 * 设计原则：
 * 1. 只做「检查 / 留档 / 报告」，**绝不删库、绝不改数据**；
 * 2. 全部走 IO 线程，且任何异常都自己吞掉（返回结果对象），绝不让启动流程崩在自检上；
 * 3. 目录规则与 util/BootLog.kt 保持一致（优先「下载/Lumo日志」，兜底应用外部私有目录），
 *    不另起一套路径。
 */
@SuppressLint("RestrictedApi")
object DbGuard {

    /** 与 util/BootLog.kt 相同的目录名 */
    const val LOG_FOLDER = "Lumo日志"

    private const val MIME_BIN = "application/octet-stream"
    private const val MIME_TXT = "text/plain"

    /** 核心表：缺一张就说明库结构不完整（例如迁移中断） */
    private val CORE_TABLES =
        listOf("ledger", "account", "category", "txn", "recurring_rule", "account_types")

    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())

    // ---------------------------------------------------------------- 启动自检

    /**
     * 启动自检：`PRAGMA integrity_check` + `PRAGMA foreign_key_check` + 核心表存在性。
     * 正常库几毫秒返回；发现问题就把「说明 + 库文件」留档到日志目录，然后照常返回让 App 继续跑。
     */
    suspend fun checkOnStartup(context: Context, db: AppDatabase): DbCheckResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = db.openHelper.readableDatabase
                val problems = mutableListOf<String>()

                val integrity = DbSql.firstColumn(raw, "PRAGMA integrity_check")
                if (!(integrity.size == 1 && integrity[0].equals("ok", ignoreCase = true))) {
                    problems += "完整性检查未通过：" + integrity.take(3).joinToString("；")
                }
                val fk = DbSql.count(raw, "PRAGMA foreign_key_check")
                if (fk > 0) problems += "外键一致性异常 $fk 处"
                CORE_TABLES.forEach { t ->
                    if (!DbSql.tableExists(raw, t)) problems += "缺少数据表 $t"
                }

                if (problems.isEmpty()) DbCheckResult(true, emptyList(), emptyList())
                else DbCheckResult(false, problems, dumpFiles(context, "数据库异常", report(context, problems)))
            }.getOrElse { e ->
                // 连自检都跑不起来（例如库文件被改坏到打不开）：同样留档，不往外抛
                val problems = listOf(
                    "自检无法完成：" + e.javaClass.simpleName + (e.message?.let { " / $it" } ?: "")
                )
                DbCheckResult(false, problems, dumpFiles(context, "数据库异常", report(context, problems)))
            }
        }

    /** 导入完整备份前自动留档（N23「导入可回滚」的前提） */
    suspend fun snapshotBeforeImport(context: Context): List<String> = withContext(Dispatchers.IO) {
        dumpFiles(
            context,
            "导入前快照",
            "导入完整备份前，自动把当前数据库留档一份。\n" +
                "时间：" + DateFmt.isoDateTime(System.currentTimeMillis()) + "\n"
        )
    }

    /**
     * 把当前数据库（bookkeeping.db 及 -wal/-shm/-journal）连同说明一起写进日志目录。
     * 返回可读位置列表；任何一步失败都只影响那一份文件，不影响别的。
     */
    fun dumpFiles(context: Context, prefix: String, reportText: String? = null): List<String> {
        val out = mutableListOf<String>()
        val tag = stamp()
        if (!reportText.isNullOrBlank()) {
            pushToLogDir(context, "$prefix-$tag.txt", reportText.toByteArray(Charsets.UTF_8), MIME_TXT)
                ?.let { out += it }
        }
        checkpoint(context)
        val src = context.getDatabasePath(AppDatabase.DB_NAME)
        val dir = src.parentFile ?: return out
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val f = File(dir, AppDatabase.DB_NAME + suffix)
            if (!f.isFile) return@forEach
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
            pushToLogDir(context, "$prefix-$tag.db$suffix", bytes, MIME_BIN)?.let { out += it }
        }
        return out
    }

    private fun report(context: Context, problems: List<String>): String = buildString {
        append("==== Lumo 数据库自检报告 ====\n")
        append("时间：").append(DateFmt.isoDateTime(System.currentTimeMillis())).append('\n')
        append("包名：").append(context.packageName).append('\n')
        append("数据库：").append(AppDatabase.DB_NAME).append('\n')
        append("结论：\n")
        problems.forEach { append("  - ").append(it).append('\n') }
        append("\n同一时间点已经把数据库文件一起留档到本目录（").append(AppDatabase.DB_NAME)
        append(" 及 -wal / -shm）。\n留档只是排查用的副本，App 会继续尝试运行，不会自动删改你的数据。\n")
    }

    /** 先做一次 WAL checkpoint（尽力而为），让 .db 文件自身包含最新数据 */
    private fun checkpoint(context: Context) {
        runCatching {
            val raw = AppDatabase.get(context.applicationContext).openHelper.writableDatabase
            raw.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        }
    }

    // ---------------------------------------------------------------- 写日志目录

    /**
     * 与 BootLog 相同的落盘规则：
     * API 29+ 走 MediaStore 写公共「下载/Lumo日志」；低版本直接写公共下载目录（无权限则跳过）；
     * 最后都兜底写一份到应用外部私有目录，保证一定能取到文件。
     */
    private fun pushToLogDir(context: Context, name: String, bytes: ByteArray, mime: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wrote = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_FOLDER)
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri: Uri = context.contentResolver.insert(collection, values) ?: return@runCatching null
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            }.getOrNull()
            if (wrote != null) return "下载/$LOG_FOLDER/$name"
        } else {
            val wrote = runCatching {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOG_FOLDER
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, name).writeBytes(bytes)
                true
            }.getOrDefault(false)
            if (wrote) return "下载/$LOG_FOLDER/$name"
        }
        val fallback = runCatching {
            val dir = File(context.getExternalFilesDir(null), LOG_FOLDER)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeBytes(bytes)
            true
        }.getOrDefault(false)
        return if (fallback) "应用目录/$LOG_FOLDER/$name" else null
    }
}

/**
 * 通用 SQLite 小工具：动态枚举用户表 / 读列结构，完整备份的导入导出也用它。
 * 表清单一律从 sqlite_master 动态取，不硬编码，新增表无需改动。
 */
@SuppressLint("RestrictedApi")
internal object DbSql {

    private val SYSTEM_TABLES = setOf("android_metadata", "room_master_table")

    /** 用户表（排除 SQLite 自己的表与 Room 的结构表） */
    fun userTables(raw: SupportSQLiteDatabase): List<String> {
        val list = mutableListOf<String>()
        raw.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if (!SYSTEM_TABLES.contains(name)) list += name
            }
        }
        return list
    }

    fun tableExists(raw: SupportSQLiteDatabase, table: String): Boolean {
        raw.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf<Any?>(table)
        ).use { c -> return c.moveToFirst() && c.getLong(0) > 0L }
    }

    /** 列结构（顺序与 `SELECT *` 的列顺序一致） */
    fun tableInfo(raw: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val list = mutableListOf<ColumnInfo>()
        raw.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val typeIdx = c.getColumnIndex("type")
            val notNullIdx = c.getColumnIndex("notnull")
            while (c.moveToNext()) {
                list += ColumnInfo(
                    name = c.getString(nameIdx),
                    type = if (typeIdx >= 0) c.getString(typeIdx) ?: "" else "",
                    notNull = notNullIdx >= 0 && c.getInt(notNullIdx) != 0
                )
            }
        }
        return list
    }

    /** 查询任意 SQL 的第一列（用于 PRAGMA 之类的多行结果） */
    fun firstColumn(raw: SupportSQLiteDatabase, sql: String): List<String> {
        val list = mutableListOf<String>()
        raw.query(sql).use { c ->
            while (c.moveToNext()) list += (c.getString(0) ?: "")
        }
        return list
    }

    /** 取第一行第一列的整数（NULL / 空结果返回 null） */
    fun scalarLong(raw: SupportSQLiteDatabase, sql: String): Long? {
        raw.query(sql).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return null
            return c.getLong(0)
        }
    }

    fun count(raw: SupportSQLiteDatabase, sql: String): Long = scalarLong(raw, sql) ?: 0L
}
