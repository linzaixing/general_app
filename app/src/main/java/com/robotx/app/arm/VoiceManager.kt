package com.robotx.app.arm

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音播报（系统 TTS）。
 *
 * 三处会说话：
 *   * **急停被按下** —— 让操作者用耳朵也能确认「停了」，眼睛可以继续盯着机械臂；
 *   * **电机温度越过报警线** —— 眼睛多半不在屏幕上，报警只闪不响很容易漏；
 *   * **语音指令的回复** —— 说进去的东西，听回来最自然。
 *
 * 做成单例而不是每个页面一个实例：`TextToSpeech` 初始化要绑系统服务、有几百毫秒延迟，
 * 在页面上新建的话第一句话必然被丢掉（页面进来就报警的场景正好撞上）。
 *
 * ⚠️ 录音前必须先 [stop]：否则播报声会被自己的麦克风收进去，
 * 服务端 ASR 会把「急停已触发」当成用户指令（见 `VoiceDialog.startRecording`）。
 *
 * 播报是**附加能力**：语音包缺失、TTS 引擎被禁用时全部静默降级，绝不影响控制功能。
 */
object VoiceManager {

    private var tts: TextToSpeech? = null
    private val ready = AtomicBoolean(false)

    /** 在 `Application.onCreate` 调一次；幂等，重复调用无副作用。 */
    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            // 中文语音包缺失时返回 LANG_MISSING_DATA / LANG_NOT_SUPPORTED —— 保持静默。
            val r = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            ready.set(r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED)
        }
    }

    /**
     * 播报一句话；未就绪或文本为空时什么都不做。
     *
     * @param urgent true = `QUEUE_FLUSH` 打断当前播报（急停 / 温度报警必须立刻听到）；
     *               false = `QUEUE_ADD` 排队播完（普通回复）。
     */
    fun speak(text: String, urgent: Boolean = false) {
        if (text.isBlank() || !ready.get()) return
        val engine = tts ?: return
        engine.speak(
            text,
            if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "robotx-${System.nanoTime()}",
        )
    }

    /** 立刻停止播报。录音前调用，避免自己的声音进麦克风。 */
    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        ready.set(false)
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}