package com.robotx.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory store for the chat history. The CameraScreen and HomeScreen both
 * push into the same repository so the conversation feels continuous.
 *
 * A "pending media" channel lets the CameraScreen hand a captured photo /
 * video over to the HomeScreen, where the user types the prompt and triggers
 * the VLA call.
 */
object ChatRepository {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    data class PendingMedia(val path: String, val kind: MediaKind)

    private val _pendingMedia = MutableStateFlow<PendingMedia?>(null)
    val pendingMedia: StateFlow<PendingMedia?> = _pendingMedia.asStateFlow()

    fun add(message: ChatMessage) {
        _messages.update { it + message }
    }

    fun update(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    fun setPendingMedia(path: String, kind: MediaKind) {
        _pendingMedia.value = PendingMedia(path, kind)
    }

    fun consumePendingMedia(): PendingMedia? {
        val cur = _pendingMedia.value
        _pendingMedia.value = null
        return cur
    }
}
