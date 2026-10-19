package com.qingjizhang.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 小票 / 凭证照片的本地文件管理（N56）。
 *
 * 文件一律放 App 私有目录 `files/receipts/<txnId>/<uuid>.jpg`：
 * - 不需要任何存储权限（私有目录），卸载 App 时一起清掉；
 * - 相机拍照先写 cache 里的临时文件（配合 FileProvider 给相机 App），
 *   拍成功后 copy 进 receipts 目录再删临时文件；
 * - 相册选图直接复制所选图片流，并压到长边 1600 以内再存（原图可能好几兆）；
 * - 备份（.bak）只含数据库、不含这些文件，恢复备份后照片需重新拍。
 */
object ReceiptStore {

    /** 某笔账单的照片目录（不存在就建） */
    fun dirFor(context: Context, txnId: Long): File {
        val dir = File(context.filesDir, "receipts/$txnId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 相机拍照用的临时文件（cache/share 下，file_paths.xml 已声明可分享） */
    fun newTempFile(context: Context): File =
        File(context.cacheDir, "share/receipt_capture.jpg").also { it.parentFile?.mkdirs() }

    /** 相机拍完：把临时文件搬进 receipts/<txnId>/，返回入库路径；失败返回 null */
    fun commitCapture(context: Context, txnId: Long, temp: File): String? {
        if (!temp.exists() || temp.length() <= 0L) return null
        val target = File(dirFor(context, txnId), UUID.randomUUID().toString() + ".jpg")
        runCatching { temp.copyTo(target, overwrite = true) }.getOrElse { return null }
        runCatching { temp.delete() }
        return target.absolutePath
    }

    /** 相册选图：复制到 receipts/<txnId>/ 并压缩，返回入库路径；失败返回 null */
    fun importFromUri(context: Context, txnId: Long, uri: Uri): String? = runCatching {
        val target = File(dirFor(context, txnId), UUID.randomUUID().toString() + ".jpg")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        if (copied == null || target.length() <= 0L) {
            target.delete()
            return null
        }
        compressInPlace(target)
        target.absolutePath
    }.getOrNull()

    /** 删照片文件（文件本来就不存在也算成功） */
    fun deleteFile(path: String) {
        runCatching { File(path).delete() }
    }

    /** 彻底删除某笔账单时清掉它的照片目录（连同空目录） */
    fun deleteDir(context: Context, txnId: Long) {
        runCatching { dirFor(context, txnId).deleteRecursively() }
    }

    /**
     * 列表缩略图 / 查看大图：按采样率解码，**带 LruCache**。
     *
     * v2.8.0 修 OOM（见 bug 报告 2026-10-17）：
     * - 之前每帧重组都重新 decode，且采样目标是 `reqWidth * 2`（一张 240dp 缩略图要 480px 宽，
     *   4000px 原图采样后仍是 2000px ≈ 3MB）；现在采样到接近目标尺寸（≈0.2MB/张）。
     * - 加 LruCache（占用最大内存的 1/8），同一张照片重复显示不再反复解码，减少 GC 抖动。
     */
    private val thumbCache by lazy {
        val maxKb = Runtime.getRuntime().maxMemory() / 1024
        object : android.util.LruCache<String, Bitmap>(maxOf(4 * 1024, (maxKb / 8).toInt())) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Bitmap? = runCatching {
        if (!File(path).exists()) return null
        val key = "$path@$reqWidth"
        thumbCache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var scale = 1
        // 采样到「接近目标尺寸」即可：再大一倍内存就要翻四倍
        while (bounds.outWidth / (scale * 2) >= reqWidth && bounds.outHeight / (scale * 2) >= reqHeight) {
            scale *= 2
        }
        val bmp = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = scale }
        ) ?: return null
        thumbCache.put(key, bmp)
        bmp
    }.getOrNull()

    /** 长边超过 1600 的图压缩一遍再存（相机原图常见 3000+，没必要原样存） */
    private fun compressInPlace(file: File) {
        // v2.8.0 修 OOM：相机原图（常见 4000×3000）全尺寸 decode 一次就要 ~48MB，
        // 在 256MB 堆的机器上直接爆。先按 1600 长边采样再解码，内存降到 ~8MB。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var scale = 1
        while (bounds.outWidth / (scale * 2) >= 1600 || bounds.outHeight / (scale * 2) >= 1600) {
            scale *= 2
        }
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = scale }
        ) ?: return
        val max = 1600
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) {
            bmp.recycle()
            return
        }
        if (w <= max && h <= max) {
            bmp.recycle()
            return
        }
        val ratio = max.toFloat() / maxOf(w, h)
        val scaled = Bitmap.createScaledBitmap(bmp, (w * ratio).toInt(), (h * ratio).toInt(), true)
        if (scaled !== bmp) bmp.recycle()
        runCatching {
            FileOutputStream(file).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 88, out) }
        }
        scaled.recycle()
    }
}
