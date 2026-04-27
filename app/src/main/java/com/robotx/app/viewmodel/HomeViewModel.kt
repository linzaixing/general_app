package com.robotx.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robotx.app.audio.AsrClient
import com.robotx.app.audio.AudioRecorder
import com.robotx.app.data.ChatMessage
import com.robotx.app.data.ChatRepository
import com.robotx.app.data.ChatRole
import com.robotx.app.data.MediaKind
import com.robotx.app.network.VlaClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drives the HomeScreen: wraps the AudioRecorder, calls AsrClient + VlaClient
 * and pushes chat messages into the [ChatRepository].
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val recorder = AudioRecorder(app)

    val amplitude: StateFlow<Float> = recorder.amplitude

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var inferenceJob: Job? = null

    fun startRecording() {
        if (_isRecording.value) return
        _isRecording.value = true
        recorder.start(viewModelScope)
    }

    fun cancelRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        recorder.cancel()
    }

    fun finishRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false
        viewModelScope.launch {
            val file = recorder.stop()
            if (file == null || file.length() < 4096) {
                _toast.value = "录音太短"
                file?.delete()
                return@launch
            }
            handleAsrThenInfer(file)
        }
    }

    fun submitMediaWithPrompt(mediaPath: String, mediaKind: MediaKind, prompt: String) {
        if (mediaKind == MediaKind.NONE) return
        val userMsg = ChatMessage(
            role = ChatRole.USER,
            text = prompt.ifBlank {
                if (mediaKind == MediaKind.VIDEO) "[视频] 请分析画面" else "[图片] 请分析画面"
            },
            mediaPath = mediaPath,
            mediaKind = mediaKind,
        )
        ChatRepository.add(userMsg)
        runVlaInference(prompt = prompt, mediaPath = mediaPath, mediaKind = mediaKind)
    }

    fun submitTextOnly(prompt: String) {
        if (prompt.isBlank()) return
        ChatRepository.add(ChatMessage(role = ChatRole.USER, text = prompt))
        runVlaInference(prompt, null, MediaKind.NONE)
    }

    fun clearChat() {
        ChatRepository.clear()
    }

    fun consumeToast() {
        _toast.value = null
    }

    private fun handleAsrThenInfer(audio: File) {
        viewModelScope.launch {
            _isThinking.value = true
            val pendingId = ChatMessage(role = ChatRole.USER, text = "…", pending = true)
            ChatRepository.add(pendingId)
            val asr = AsrClient.transcribe(audio)
            audio.delete()
            asr.onSuccess { text ->
                ChatRepository.update(pendingId.id) { it.copy(text = text, pending = false) }
                val pending = ChatRepository.consumePendingMedia()
                if (pending != null) {
                    ChatRepository.update(pendingId.id) {
                        it.copy(mediaPath = pending.path, mediaKind = pending.kind)
                    }
                    runVlaInference(text, pending.path, pending.kind, hideUserMessage = true)
                } else {
                    runVlaInference(text, null, MediaKind.NONE, hideUserMessage = true)
                }
            }.onFailure { err ->
                ChatRepository.update(pendingId.id) {
                    it.copy(text = "[语音识别失败]", pending = false, error = err.message)
                }
                _toast.value = "语音识别失败: ${err.message}"
                _isThinking.value = false
            }
        }
    }

    private fun runVlaInference(
        prompt: String,
        mediaPath: String?,
        mediaKind: MediaKind,
        hideUserMessage: Boolean = false,
    ) {
        inferenceJob?.cancel()
        inferenceJob = viewModelScope.launch {
            _isThinking.value = true
            val placeholder = ChatMessage(role = ChatRole.AI, text = "", pending = true)
            ChatRepository.add(placeholder)
            val image = if (mediaKind == MediaKind.IMAGE) mediaPath?.let { File(it) } else null
            val video = if (mediaKind == MediaKind.VIDEO) mediaPath?.let { File(it) } else null
            val r = VlaClient.infer(prompt = prompt, imageFile = image, videoFile = video)
            r.onSuccess { reply ->
                ChatRepository.update(placeholder.id) {
                    it.copy(text = reply, pending = false)
                }
            }.onFailure { err ->
                ChatRepository.update(placeholder.id) {
                    it.copy(
                        text = "[请求失败]",
                        pending = false,
                        error = err.message,
                    )
                }
                _toast.value = "请求失败: ${err.message}"
            }
            _isThinking.value = false
            if (hideUserMessage) Unit // placeholder for symmetry
        }
    }
}
