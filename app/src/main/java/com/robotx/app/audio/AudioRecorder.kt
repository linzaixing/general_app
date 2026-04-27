package com.robotx.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records mono 16kHz 16-bit PCM into a .wav file (with proper RIFF header)
 * and exposes the live RMS amplitude as a StateFlow in [0f, 1f] for UI use.
 *
 * Usage:
 * ```
 * val rec = AudioRecorder(context)
 * rec.start()
 * // ... user holding button
 * val file = rec.stop()
 * ```
 */
class AudioRecorder(context: Context) {

    private val ctx = context.applicationContext

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(2048)

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var output: File? = null

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile private var recording = false
    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (recording) return
        val outFile = File(ctx.cacheDir, "asr_${System.currentTimeMillis()}.wav")
        output = outFile

        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            minBuffer * 2,
        )
        record?.startRecording()
        recording = true

        job = scope.launch(Dispatchers.IO) {
            val raf = RandomAccessFile(outFile, "rw")
            try {
                writeWavHeaderPlaceholder(raf)
                val buffer = ShortArray(minBuffer)
                val bytes = ByteArray(minBuffer * 2)
                var totalBytes = 0L
                while (isActive && recording) {
                    val read = record?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue
                    var sum = 0.0
                    for (i in 0 until read) {
                        val s = buffer[i].toInt()
                        sum += (s * s).toDouble()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    raf.write(bytes, 0, read * 2)
                    totalBytes += read * 2
                    val rms = Math.sqrt(sum / read) / Short.MAX_VALUE
                    _amplitude.value = rms.toFloat().coerceIn(0f, 1f)
                }
                finalizeWavHeader(raf, totalBytes)
            } finally {
                raf.close()
            }
        }
    }

    /**
     * Stop recording and return the resulting WAV file (or null if nothing was recorded).
     * Safe to call multiple times.
     */
    suspend fun stop(): File? {
        if (!recording) return output
        recording = false
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped
        }
        record?.release()
        record = null
        // Wait for the writer coroutine to flush the header
        job?.let {
            withContext(Dispatchers.IO) { it.join() }
        }
        job = null
        _amplitude.value = 0f
        return output
    }

    fun cancel() {
        recording = false
        try { record?.stop() } catch (_: IllegalStateException) {}
        record?.release()
        record = null
        job?.cancel()
        job = null
        output?.delete()
        output = null
        _amplitude.value = 0f
    }

    private fun writeWavHeaderPlaceholder(raf: RandomAccessFile) {
        // 44 bytes filled later by finalize.
        raf.write(ByteArray(44))
    }

    private fun finalizeWavHeader(raf: RandomAccessFile, dataBytes: Long) {
        val totalDataLen = dataBytes + 36
        val byteRate = sampleRate * 1 * 16 / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat = PCM
        header.putShort(1) // NumChannels = 1
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((1 * 16 / 8).toShort()) // BlockAlign
        header.putShort(16) // BitsPerSample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }
}
