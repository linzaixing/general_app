package com.robotx.app.arm

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

private const val SAMPLE_RATE = 16_000
private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** 短于这个时长就当成误触，不往外送。 */
private const val MIN_SECONDS = 0.4

/**
 * 录音：16kHz 单声道 PCM16，停止时输出**带 WAV 头**的字节流。
 *
 * 格式不是随便定的 —— 服务端 `LLMClient.transcribe` 的契约就是「16kHz 单声道 WAV」，
 * 云端 ASR 前必须对齐，否则识别会失败或听成噪音。
 *
 * 服务端 `/api/voice_command` 接受 `audio_b64`，所以整条链路是：
 * 本机录音 → WAV → Base64 → 服务端做 ASR + 意图解析。
 * 这样就不依赖「手机自带语音识别服务」了（华为部分机型上根本没有）。
 */
class ArmAudioRecorder {

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    var isRecording: Boolean = false
        private set

    /** 开始录音。返回 false 表示麦克风不可用（无权限 / 被占用 / 设备不支持）。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return false
        // 缓冲给到 0.5s 以上，避免读线程被调度延迟时丢帧。
        val bufSize = maxOf(minBuf, SAMPLE_RATE * 2 / 2)

        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufSize)
        } catch (e: SecurityException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        pcm.reset()
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release()
            return false
        }
        record = rec
        isRecording = true
        worker = thread(name = "arm-audio", isDaemon = true) {
            val chunk = ByteArray(bufSize)
            while (isRecording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(pcm) { pcm.write(chunk, 0, n) }
                }
            }
        }
        return true
    }

    /** 停止录音并返回 WAV 字节；太短或没录到则返回 null。 */
    fun stop(): ByteArray? {
        if (!isRecording && record == null) return null
        isRecording = false
        worker?.join(600)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null

        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < (SAMPLE_RATE * 2 * MIN_SECONDS).toInt()) return null
        return wavOf(data)
    }

    /** 录音中途退出（比如关弹窗）用这个，不产出音频。 */
    fun release() {
        isRecording = false
        worker?.join(300)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        synchronized(pcm) { pcm.reset() }
    }

    /** PCM16 裸流 + 44 字节标准 WAV 头。 */
    private fun wavOf(data: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE * 1 * 2 // 采样率 × 声道 × 位深/8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + data.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)              // fmt 块长度
            putShort(1)             // PCM
            putShort(1)             // 单声道
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(2)             // 块对齐
            putShort(16)            // 位深
            put("data".toByteArray())
            putInt(data.size)
        }
        return header.array() + data
    }
}