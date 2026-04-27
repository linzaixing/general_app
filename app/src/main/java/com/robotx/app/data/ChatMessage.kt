package com.robotx.app.data

import java.util.UUID

enum class ChatRole { USER, AI }

enum class MediaKind { NONE, IMAGE, VIDEO }

/**
 * A single line in the chat list. Either has [text] (typical),
 * [mediaPath] (user-attached photo / video) or both.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
    val mediaPath: String? = null,
    val mediaKind: MediaKind = MediaKind.NONE,
    val timestamp: Long = System.currentTimeMillis(),
    val pending: Boolean = false,
    val error: String? = null,
)
