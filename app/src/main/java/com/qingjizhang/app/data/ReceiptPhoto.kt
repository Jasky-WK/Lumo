package com.qingjizhang.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账单小票 / 凭证照片（N56）。
 *
 * 一笔账单可以带多张照片；照片文件本身存在 App 私有目录
 * `files/receipts/<txnId>/<uuid>.jpg`，这里只存文件路径。
 *
 * - 删除账单（进回收站）**不删照片**，还原时照片还在；
 * - 彻底删除（回收站里 purge / 删账本）时才连文件一起清掉；
 * - 备份（.bak JSON）只含数据库内容，**不含照片文件**，
 *   恢复备份后照片记录会消失（表被备份数据覆盖），需要重新拍。
 */
@Entity(tableName = "receipt_photos", indices = [Index(value = ["txnId"])])
data class ReceiptPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val txnId: Long,
    /** 照片文件的绝对路径（App 私有目录） */
    val filePath: String,
    val createdAt: Long = System.currentTimeMillis()
)
