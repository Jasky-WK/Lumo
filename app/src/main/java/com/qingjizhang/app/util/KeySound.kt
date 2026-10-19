package com.qingjizhang.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.qingjizhang.app.R

/**
 * 记账按键音（N39）。
 *
 * 四个使用场景各配一段随 APK 打包的原生音效（`res/raw`，均为 16bit / 48kHz 单声道 WAV）：
 *
 * | 场景 | 资源 | 触发点 |
 * | --- | --- | --- |
 * | [Scene.ENTER] | `key_enter.wav` | 点击记一笔（首页右下角「＋」） |
 * | [Scene.CATEGORY] | `key_category.wav` | 选择账单分类 / 账户 |
 * | [Scene.AMOUNT] | `key_amount.wav` | 记账键盘输入金额（数字 / 小数点 / 运算符 / 退格） |
 * | [Scene.SAVE] | `key_save.wav` | 保存账单（保存 / 再记） |
 *
 * - 播放走 [SoundPool]（短音效专用，低延迟、可重入），进入记一笔时四段一起预加载，
 *   首次按键也不延迟；`load` 是异步的，没加载完的那一段直接跳过、不会卡住操作。
 * - 音频属性用 [AudioAttributes.USAGE_ALARM]：**与闹钟同一条音频流**，
 *   手机处于静音 / 振动 / 勿扰模式时按键音照常播放（铃声静音只影响铃声与通知流）。
 */
object KeySound {

    /** 按键音的使用场景（开关只控制「放不放」，四段音效共用同一个开关） */
    enum class Scene(val resId: Int) {
        ENTER(R.raw.key_enter),
        CATEGORY(R.raw.key_category),
        AMOUNT(R.raw.key_amount),
        SAVE(R.raw.key_save)
    }

    /**
     * 相对音量：在流本身音量之上再收一点，保持常规大小、不吵。
     * 按用户反馈逐步下调：0.85 → 0.425（×0.5）→ 0.10625（×0.25）→ 0.0354167（÷3）。
     * 只改响度，音效资源与音色不变。
     */
    private const val VOLUME = 0.0354167f

    private var pool: SoundPool? = null
    /** 场景 -> SoundPool 的 sampleId */
    private val sampleIds = mutableMapOf<Scene, Int>()
    /** 已加载完成、可以播放的场景 */
    private val ready = mutableSetOf<Scene>()

    /** 预加载四段音效（进入记一笔时调用，避开第一次按键的加载延迟） */
    @Synchronized
    fun prepare(context: Context) {
        if (pool != null) return
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()
            p.setOnLoadCompleteListener { _, sampleId, status -> onSampleLoaded(sampleId, status) }
            Scene.values().forEach { scene ->
                sampleIds[scene] = p.load(context.applicationContext, scene.resId, 1)
            }
            pool = p
        }
    }

    @Synchronized
    private fun onSampleLoaded(sampleId: Int, status: Int) {
        if (status != 0) return
        sampleIds.entries.firstOrNull { it.value == sampleId }?.let { ready += it.key }
    }

    /** 播放某个场景的音效；该段还没加载完时什么都不做（不阻塞、不报错） */
    @Synchronized
    fun play(context: Context, scene: Scene) {
        prepare(context)
        if (scene !in ready) return
        val p = pool ?: return
        val id = sampleIds[scene] ?: return
        runCatching { p.play(id, VOLUME, VOLUME, 1, 0, 1f) }
    }
}
