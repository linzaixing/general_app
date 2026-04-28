package com.robotx.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.robotx.app.R
import com.robotx.app.network.ApiConfig
import com.robotx.app.network.ConnectivityProbe
import kotlinx.coroutines.launch

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
) {
    val current by ApiConfig.state.collectAsState()
    val scope = rememberCoroutineScope()

    var baseUrl by rememberSaveable(current.baseUrl) { mutableStateOf(current.baseUrl) }
    var apiKey by rememberSaveable(current.apiKey) { mutableStateOf(current.apiKey) }
    var asrPath by rememberSaveable(current.asrPath) { mutableStateOf(current.asrPath) }
    var asrModel by rememberSaveable(current.asrModel) { mutableStateOf(current.asrModel) }
    var vlaPath by rememberSaveable(current.vlaPath) { mutableStateOf(current.vlaPath) }
    var vlaModel by rememberSaveable(current.vlaModel) { mutableStateOf(current.vlaModel) }

    var probeText by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }

    fun applyPreset(p: ApiConfig.Preset) {
        baseUrl = p.baseUrl
        asrPath = p.asrPath
        asrModel = p.asrModel
        vlaPath = p.vlaPath
        vlaModel = p.vlaModel
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.preset_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = { applyPreset(ApiConfig.Preset.SELF_HOSTED) },
                        label = { Text(stringResource(R.string.preset_self)) },
                    )
                    AssistChip(
                        onClick = { applyPreset(ApiConfig.Preset.DASHSCOPE) },
                        label = { Text(stringResource(R.string.preset_dashscope)) },
                    )
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.settings_base_url)) },
                    placeholder = { Text("http://192.168.1.100:8000/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = {
                            probing = true
                            probeText = null
                            scope.launch {
                                val resolvedVla = baseUrl.trimEnd('/') + (if (vlaPath.startsWith("/")) vlaPath else "/$vlaPath")
                                val r = ConnectivityProbe.probeVla(
                                    url = resolvedVla,
                                    model = vlaModel,
                                    apiKey = apiKey,
                                )
                                probing = false
                                probeText = if (r.ok) {
                                    "✔ ${r.message}"
                                } else {
                                    "✘ ${r.message}"
                                }
                            }
                        },
                        enabled = !probing && baseUrl.isNotBlank(),
                    ) {
                        Text(if (probing) stringResource(R.string.testing) else stringResource(R.string.test_connection))
                    }
                    if (probeText != null) {
                        Text(
                            text = probeText!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                OutlinedTextField(
                    value = asrPath,
                    onValueChange = { asrPath = it },
                    label = { Text(stringResource(R.string.settings_asr_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = asrModel,
                    onValueChange = { asrModel = it },
                    label = { Text(stringResource(R.string.settings_asr_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vlaPath,
                    onValueChange = { vlaPath = it },
                    label = { Text(stringResource(R.string.settings_vla_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vlaModel,
                    onValueChange = { vlaModel = it },
                    label = { Text(stringResource(R.string.settings_vla_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val resolvedAsr = baseUrl.trimEnd('/') + (if (asrPath.startsWith("/")) asrPath else "/$asrPath")
                val resolvedVla = baseUrl.trimEnd('/') + (if (vlaPath.startsWith("/")) vlaPath else "/$vlaPath")
                Text(
                    text = stringResource(R.string.resolved_urls, resolvedAsr, resolvedVla),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    ApiConfig.update(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        asrPath = asrPath,
                        asrModel = asrModel,
                        vlaPath = vlaPath,
                        vlaModel = vlaModel,
                    )
                    onDismiss()
                }
            }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}
