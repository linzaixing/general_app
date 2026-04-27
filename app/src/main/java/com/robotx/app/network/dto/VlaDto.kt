package com.robotx.app.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenAI / DashScope compatible Chat-Completion request body.
 * The `messages[*].content` field is polymorphic: it can either be a plain
 * string or a list of typed parts (text + image_url + video_url). To keep the
 * wire format stable we model it as a JsonElement and let [VlaClient] build it.
 */
@Serializable
data class VlaRequest(
    val model: String,
    val messages: List<VlaMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = 1024,
    val temperature: Double? = 0.7,
)

@Serializable
data class VlaMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class VlaResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<VlaChoice>? = null,
    val usage: JsonObject? = null,
    val message: String? = null,
    val error: VlaError? = null,
) {
    fun pickText(): String {
        val c = choices?.firstOrNull() ?: return ""
        val msg = c.message ?: return ""
        // content can be a string or a structured array; we extract text.
        val content = msg.content
        if (content is JsonPrimitive) return content.content
        if (content is kotlinx.serialization.json.JsonArray) {
            return content.joinToString(separator = "") { part ->
                val po = part as? JsonObject ?: return@joinToString ""
                (po["text"] as? JsonPrimitive)?.content.orEmpty()
            }
        }
        return ""
    }
}

@Serializable
data class VlaChoice(
    val index: Int? = null,
    val message: VlaResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class VlaResponseMessage(
    val role: String,
    val content: JsonElement? = null,
)

@Serializable
data class VlaError(
    val code: String? = null,
    val message: String? = null,
    val type: String? = null,
)
