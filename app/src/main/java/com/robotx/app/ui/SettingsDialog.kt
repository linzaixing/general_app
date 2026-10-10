package com.robotx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.robotx.app.BuildConfig
import com.robotx.app.network.ApiConfig
import kotlinx.coroutines.launch

/**
 * 设置：只剩三件事 —— 版本号、API Key、以及各功能用哪个模型。
 *
 * 原先那套「云端 API 配置」（Base URL / ASR 路径 / VLA 路径 / 预设 / 测试连接）已删除：
 * 请求地址由服务端自己决定，App 不需要也不应该知道。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
) {
    val current by ApiConfig.state.collectAsState()
    val scope = rememberCoroutineScope()

    var apiKey by rememberSaveable(current.apiKey) { mutableStateOf(current.apiKey) }
    var asrModel by rememberSaveable(current.asrModel) { mutableStateOf(current.asrModel) }
    var chatModel by rememberSaveable(current.chatModel) { mutableStateOf(current.chatModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "机械臂小助手  v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("语音识别模型（语音 → 文字）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiConfig.ASR_MODEL_CHOICES.forEach { m ->
                        AssistChip(
                            onClick = { asrModel = m },
                            label = { Text(m) },
                        )
                    }
                }
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = { asrModel = it },
                    label = { Text("语音识别模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("图像识别 / 指令理解模型（文字 → 意图）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ApiConfig.CHAT_MODEL_CHOICES.forEach { m ->
                        AssistChip(
                            onClick = { chatModel = m },
                            label = { Text(m) },
                        )
                    }
                }
                OutlinedTextField(
                    value = chatModel,
                    onValueChange = { chatModel = it },
                    label = { Text("视觉 / 指令理解模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    ApiConfig.update(
                        apiKey = apiKey,
                        asrModel = asrModel,
                        chatModel = chatModel,
                    )
                    onDismiss()
                }
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}