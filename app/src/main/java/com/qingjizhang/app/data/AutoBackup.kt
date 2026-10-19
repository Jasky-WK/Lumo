package com.qingjizhang.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * v2.9.0：每月自动备份。
 *
 * 与手动备份（App 私有目录 + 下载/Lumo备份）分开存放，落在**公开目录**：
 * `Documents/Lumo/自动备份/Lumo自动备份-yyyyMMdd-HHmm.bak`
 *
 * - 格式与「完整备份」完全一致（.bak JSON），恢复走同一套预览 + 校验 + 回滚路径；
 * - Android 10+ 走 MediaStore.Files（无需任何权限），9 及以下走 File（WRITE_EXTERNAL_STORAGE，已在清单里限 maxSdk 28）；
 * - 只写公开目录一份：私有目录仍只放手动备份，两处不混；
 * - 软上限 3 份：第 4 次生成前由 [AutoBackupReminder] 提醒用户手动删最旧的，**不自动删文件**。
 */
object AutoBackup {

    const val PREFIX = "Lumo自动备份-"
    const val SUFFIX = ".bak"

    /** MediaStore 里的相对路径（Documents 下），注意末尾斜杠是 MediaStore 的约定 */
    const val RELATIVE_PATH = "Documents/Lumo/自动备份/"

    /** 自动备份软上限：到这个数就在下次生成前提醒 */
    const val KEEP_LIMIT = 3

    private const val LEGACY_DIR = "Lumo/自动备份"

    /** 一条自动备份（Q+ 用 uri，旧系统用 file） */
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        val timeMillis: Long,
        val uri: Uri? = null,
        val file: File? = null
    ) {
        val sizeText: String
            get() = when {
                sizeBytes >= 1024 * 1024 -> String.format("%.1f MB", sizeBytes / 1024.0 / 1024.0)
                sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
                else -> "$sizeBytes B"
            }

        val timeText: String
            get() = com.qingjizhang.app.util.DateFmt.isoDateTime(timeMillis)
    }

    fun fileName(now: LocalDateTime = LocalDateTime.now()): String =
        PREFIX + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + SUFFIX

    /** 列出现有自动备份，按时间倒序 */
    fun list(context: Context): List<Entry> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listViaMediaStore(context) else listLegacy(context)

    private fun listViaMediaStore(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " +
            MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?"
        val args = arrayOf(RELATIVE_PATH, "$PREFIX%$SUFFIX")
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"), projection, selection, args, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val timeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += Entry(
                        name = c.getString(nameCol) ?: "",
                        sizeBytes = c.getLong(sizeCol),
                        timeMillis = c.getLong(timeCol) * 1000L, // DATE_MODIFIED 是秒
                        uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    )
                }
            }
        }
        return out.sortedByDescending { it.timeMillis }
    }

    private fun listLegacy(context: Context): List<Entry> {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            LEGACY_DIR
        )
        return dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Entry(it.name, it.length(), it.lastModified(), file = it) }
            ?: emptyList()
    }

    /** 写入一份自动备份；返回是否成功（失败不抛，调用方静默处理） */
    suspend fun save(context: Context, name: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Files.getContentUri("external")
                val uri = runCatching { context.contentResolver.insert(collection, values) }
                    .getOrNull() ?: return@withContext false
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("无法写入")
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                        null,
                        null
                    )
                }.isSuccess
                if (!ok) runCatching { context.contentResolver.delete(uri, null, null) }
                ok
            } else {
                runCatching {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        LEGACY_DIR
                    )
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, name).writeBytes(bytes)
                    true
                }.getOrDefault(false)
            }
        }

    suspend fun delete(context: Context, entry: Entry): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (entry.uri != null) {
                context.contentResolver.delete(entry.uri, null, null) > 0
            } else {
                entry.file?.delete() == true
            }
        }.getOrDefault(false)
    }

    /** 把自动备份内容读出来（.bak JSON），交给现有的预览 / 导入流程 */
    suspend fun readText(context: Context, entry: Entry): String = withContext(Dispatchers.IO) {
        if (entry.uri != null) {
            context.contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取备份文件")
        } else {
            entry.file?.readText() ?: error("备份文件不存在")
        }
    }
}
