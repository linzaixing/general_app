package com.robotx.app.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tolerant ASR response wrapper. Different vendors return different shapes; we
 * accept the most common ones and pick the first non-empty text we can find.
 *
 *  - { "text": "..." }                                   (DashScope / OpenAI Whisper)
 *  - { "result": "..." }                                  (some custom backends)
 *  - { "data": { "text": "..." } }                        (DashScope async)
 *  - { "output": { "sentence": [{ "text": "..." }, ...] } } (DashScope ws)
 */
@Serializable
data class AsrResponse(
    val text: String? = null,
    val result: String? = null,
    val data: AsrData? = null,
    val output: AsrOutput? = null,
    val message: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
) {
    fun pickText(): String {
        text?.takeIf { it.isNotBlank() }?.let { return it }
        result?.takeIf { it.isNotBlank() }?.let { return it }
        data?.text?.takeIf { it.isNotBlank() }?.let { return it }
        output?.text?.takeIf { it.isNotBlank() }?.let { return it }
        output?.sentence?.joinToString(separator = "") { it.text.orEmpty() }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return ""
    }
}

@Serializable
data class AsrData(val text: String? = null)

@Serializable
data class AsrOutput(
    val text: String? = null,
    val sentence: List<AsrSentence>? = null,
)

@Serializable
data class AsrSentence(val text: String? = null)
