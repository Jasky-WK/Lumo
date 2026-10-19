package com.qingjizhang.app.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 图片保存结果：成功时带上保存位置，失败时带上可读原因（不静默吞异常）。
 */
data class ImageSaveResult(val ok: Boolean, val location: String, val error: String = "") {
    /** 提示给用户的文案 */
    val message: String get() = if (ok) "已保存到「$location」" else "保存失败：$error"
}

/**
 * 本地图片导出（小票 / 报表共用）。
 * 纯本地：写文件 + 系统分享，不联网、不申请网络权限。
 */
object ImageExport {

    /** API 29 以下写相册需要存储权限；API 29+ 走 MediaStore，不需要权限 */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * 保存图片到系统「图片」目录下的 <folder> 子目录（API 29+ 走 MediaStore，会被相册索引到）。
     * 返回保存结果，由调用方给出明确的成功提示或失败原因。
     */
    suspend fun saveImage(context: Context, file: File, folder: String): ImageSaveResult =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() <= 0L) {
                return@withContext ImageSaveResult(false, "", "图片没生成出来，请重试")
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(context, file, folder)
                } else {
                    saveToPicturesDir(context, file, folder)
                }
            }.getOrElse { e ->
                when (e) {
                    is SecurityException -> ImageSaveResult(false, "", "未获得存储权限")
                    else -> ImageSaveResult(false, "", e.message ?: e.javaClass.simpleName)
                }
            }
        }

    /** API 29+：写进 MediaStore.Images，RELATIVE_PATH = Pictures/<folder> */
    private fun saveViaMediaStore(context: Context, file: File, folder: String): ImageSaveResult {
        val relative = Environment.DIRECTORY_PICTURES + "/" + folder
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relative)
            // 写完再撤销 pending，避免相册读到只写了一半的图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri: Uri = resolver.insert(collection, values)
            ?: return ImageSaveResult(false, "", "系统相册拒绝写入，请检查存储空间")
        try {
            val out = resolver.openOutputStream(uri)
                ?: return ImageSaveResult(false, "", "无法打开写入流")
            out.use {
                it.write(file.readBytes())
                it.flush()
            }
        } catch (e: Exception) {
            // 写失败时删掉半成品，别在相册里留一张坏图
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return ImageSaveResult(true, relative + "/" + file.name)
    }

    /** API 29 以下：直接写外部存储的 Pictures/<folder>，需要 WRITE_EXTERNAL_STORAGE */
    private fun saveToPicturesDir(context: Context, file: File, folder: String): ImageSaveResult {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            folder
        )
        if (!dir.exists() && !dir.mkdirs()) {
            return ImageSaveResult(false, "", "无法创建保存目录")
        }
        val dest = File(dir, file.name)
        FileOutputStream(dest).use { out ->
            out.write(file.readBytes())
            out.flush()
        }
        // 通知媒体库扫描，保证在系统相册里也能直接看到
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/png"), null)
        return ImageSaveResult(true, dest.absolutePath)
    }

    /** 系统分享（本地，不需要联网） */
    fun share(context: Context, file: File, chooserTitle: String) {
        runCatching {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
        }
    }
}
