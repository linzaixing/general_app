package com.robotx.app.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Shared singleton OkHttp client with sensible timeouts for multimodal LLM
 * calls (which can be slow). Body logging is intentionally muted to avoid
 * dumping large base64 payloads to logcat; switch to Level.HEADERS for debug.
 */
object HttpClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.HEADERS }
            )
            .build()
    }
}
