package com.qingjizhang.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.qingjizhang.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动自检日志（仅自检版启用）。
 *
 * 目的：闪退时用户在手机上就能拿到原因，不需要连电脑抓 logcat。
 * 记录两样东西：
 * 1. 启动每一步的打点（`boot-时间.txt`）—— 最后一行就是"卡在哪一步"
 * 2. 未捕获异常的完整堆栈（`crash-时间.txt`）
 *
 * 纯本地：优先写「下载/Lumo日志」，并在应用外部私有目录留一份兜底；不联网。
 */
object BootLog {

    /** 逐步打点：排查问题时临时改成 true；正式版保持 false，只记录崩溃 */
    private const val STEP_LOG = false

    /** 崩溃报告：始终保留（自动写到「下载/Lumo日志」，方便用户直接转发） */
    private const val CRASH_REPORT = true

    private const val FOLDER = "Lumo日志"
    private const val LOGCAT_TAG = "QJZ-BOOT"

    @Volatile private var outFile: File? = null
    @Volatile private var downloadUri: Uri? = null
    @Volatile private var downloadTried = false
    @Volatile private var started = false

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())

    /** 在 Application.onCreate 最开始调用 */
    fun init(context: Context) {
        if (!STEP_LOG || started) return
        started = true
        runCatching {
            val dir = File(context.getExternalFilesDir(null), FOLDER)
            if (!dir.exists()) dir.mkdirs()
            outFile = File(dir, "boot-" + stamp() + ".txt")
        }
        log(context, "BootLog 已就绪（版本 " + BuildConfig.VERSION_NAME + "）")
    }

    /** 打一个启动步骤（STEP_LOG 关闭时不产生任何文件；logcat 也一并跳过） */
    fun log(context: Context, step: String) {
        if (!STEP_LOG) return
        runCatching { android.util.Log.i(LOGCAT_TAG, step) }
        val line = time() + "  " + step + "\n"
        runCatching { outFile?.appendText(line) }
        runCatching { appendToDownloads(context, line) }
    }

    /** 注册全局崩溃捕获：把堆栈写文件后再交给系统默认处理 */
    fun installCrashHandler(context: Context) {
        if (!CRASH_REPORT) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val sb = StringBuilder()
        sb.append("==== Lumo 崩溃报告 ====\n")
        sb.append("时间：").append(time()).append('\n')
        sb.append("版本：").append(BuildConfig.VERSION_NAME)
            .append("（versionCode ").append(BuildConfig.VERSION_CODE).append("）\n")
        sb.append("包名：").append(context.packageName).append('\n')
        sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        sb.append("ABI：").append(Build.SUPPORTED_ABIS.joinToString(",")).append('\n')
        sb.append("线程：").append(thread.name).append('\n')
        sb.append("异常：").append(error.javaClass.name).append(": ").append(error.message).append('\n')
        sb.append("\n---- 堆栈 ----\n")
        sb.append(android.util.Log.getStackTraceString(error)).append('\n')
        error.cause?.let { c ->
            sb.append("\n---- cause ----\n")
            sb.append(android.util.Log.getStackTraceString(c)).append('\n')
        }
        runCatching { android.util.Log.e(LOGCAT_TAG, sb.toString()) }

        val body = sb.toString()
        // 1) 写「下载/Lumo日志」，方便用户直接转发
        val name = "crash-" + stamp() + ".txt"
        val toDownload = runCatching { createInDownloads(context, name)?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
            uri
        } }.getOrNull()
        // 2) 兜底写应用外部私有目录
        runCatching {
            val dir = File(context.getExternalFilesDir(null), FOLDER)
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeText(body)
        }
        log(context, "已写入崩溃报告：" + (if (toDownload != null) "下载/$FOLDER/$name" else "应用目录/$FOLDER/$name"))
    }

    private fun appendToDownloads(context: Context, line: String) {
        if (!downloadTried && downloadUri == null) {
            downloadTried = true
            downloadUri = createInDownloads(context, "boot-" + stamp() + ".txt")
        }
        val uri = downloadUri ?: return
        runCatching {
            context.contentResolver.openOutputStream(uri, "wa")?.use { it.write(line.toByteArray()) }
        }
    }

    /** API 29+ 走 MediaStore；更低版本写公共下载目录（需要存储权限，失败就只留私有目录那份） */
    private fun createInDownloads(context: Context, name: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            context.contentResolver.insert(collection, values)
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FOLDER
                )
                if (!dir.exists()) dir.mkdirs()
                Uri.fromFile(File(dir, name))
            }.getOrNull()
        }
    }

    private fun time(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
}
