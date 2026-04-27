package com.robotx.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robotx.app.R
import com.robotx.app.data.ChatMessage
import com.robotx.app.data.ChatRepository
import com.robotx.app.data.MediaKind
import com.robotx.app.network.ApiConfig
import com.robotx.app.permission.rememberCoreFeaturePermissions
import com.robotx.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val messages by ChatRepository.messages.collectAsState()
    val pendingMedia by ChatRepository.pendingMedia.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val amplitude by vm.amplitude.collectAsState()
    val toast by vm.toast.collectAsState()
    val cfg by ApiConfig.state.collectAsState()

    val perms = rememberCoreFeaturePermissions()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var promptText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(toast) {
        toast?.let {
            snack.showSnackbar(it)
            vm.consumeToast()
        }
    }

    // First-launch hint to open Settings
    LaunchedEffect(cfg) {
        if (cfg.apiKey.isBlank() && messages.isEmpty()) {
            showSettings = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.clear_chat))
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ChatList(
                messages = messages,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            BottomBar(
                pendingMedia = pendingMedia,
                isRecording = isRecording,
                amplitude = amplitude,
                promptText = promptText,
                onPromptChange = { promptText = it },
                onSend = {
                    val pm = ChatRepository.consumePendingMedia()
                    if (pm != null) {
                        vm.submitMediaWithPrompt(pm.path, pm.kind, promptText)
                    } else if (promptText.isNotBlank()) {
                        vm.submitTextOnly(promptText)
                    }
                    promptText = ""
                },
                onMicDown = {
                    if (!perms.allGranted) {
                        perms.request()
                        scope.launch { snack.showSnackbar(context.getString(R.string.permission_required_msg)) }
                        return@BottomBar
                    }
                    if (!cfg.isConfigured) {
                        scope.launch { snack.showSnackbar(context.getString(R.string.missing_config)) }
                        showSettings = true
                        return@BottomBar
                    }
                    vm.startRecording()
                },
                onMicUp = { canceled ->
                    if (canceled) vm.cancelRecording() else vm.finishRecording()
                },
                onCamera = {
                    if (!perms.allGranted) {
                        perms.request()
                        scope.launch { snack.showSnackbar(context.getString(R.string.permission_required_msg)) }
                        return@BottomBar
                    }
                    onOpenCamera()
                },
                onClearMedia = { ChatRepository.consumePendingMedia() },
            )
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun ChatList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        if (messages.isEmpty()) {
            item { WelcomeCard() }
        }
        items(messages, key = { it.id }) { msg -> ChatBubble(msg) }
    }
}

@Composable
private fun WelcomeCard() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.welcome_message),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun BottomBar(
    pendingMedia: ChatRepository.PendingMedia?,
    isRecording: Boolean,
    amplitude: Float,
    promptText: String,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: (canceled: Boolean) -> Unit,
    onCamera: () -> Unit,
    onClearMedia: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(visible = pendingMedia != null) {
                pendingMedia?.let { PendingMediaPreview(it, onClearMedia) }
            }

            // Top row: text input + send (合并到一行，节省底部空间给大尺寸麦克风)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = onPromptChange,
                    placeholder = { Text(stringResource(R.string.prompt_hint), maxLines = 1) },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    shape = RoundedCornerShape(22.dp),
                )
                IconButton(
                    onClick = onSend,
                    enabled = promptText.isNotBlank() || pendingMedia != null,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (promptText.isNotBlank() || pendingMedia != null)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                }
            }

            // Bottom row: 左相机 / 中央巨型麦克风 / 右占位（对称）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LargeSideAction(
                    icon = Icons.Filled.CameraAlt,
                    label = stringResource(R.string.open_camera),
                    onClick = onCamera,
                )
                MicButton(
                    isRecording = isRecording,
                    amplitude = amplitude,
                    onDown = onMicDown,
                    onUp = onMicUp,
                )
                // Right-side spacer-action keeps the mic visually centered.
                // Reserved for a future quick action (e.g. clear chat shortcut).
                Box(modifier = Modifier.size(64.dp))
            }
        }
    }
}

@Composable
private fun LargeSideAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(width = 72.dp, height = 80.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
        )
    }
}

@Composable
private fun PendingMediaPreview(media: ChatRepository.PendingMedia, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (media.kind == MediaKind.VIDEO) "已捕获视频，输入指令并发送…" else "已捕获图片，输入指令并发送…",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onClear) { Text("清除") }
    }
}

@Composable
private fun MicButton(
    isRecording: Boolean,
    amplitude: Float,
    onDown: () -> Unit,
    onUp: (canceled: Boolean) -> Unit,
) {
    val pulse by animateFloatAsState(
        targetValue = if (isRecording) 1f + (amplitude * 0.4f).coerceIn(0f, 0.4f) else 1f,
        label = "mic-pulse",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (isRecording) 0.35f + (amplitude * 0.4f).coerceIn(0f, 0.4f) else 0f,
        label = "mic-ring-alpha",
    )
    val color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val label = if (isRecording) stringResource(R.string.recording) else stringResource(R.string.hold_to_talk)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Concentric pulse ring + main mic disc (90dp diameter)
        Box(
            modifier = Modifier.size(118.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Outer ring that grows with voice amplitude
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .scale(pulse)
                    .background(color.copy(alpha = ringAlpha), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .scale(pulse)
                    .background(color, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onDown()
                                val released = tryAwaitRelease()
                                onUp(!released)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_drone_mark),
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(46.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
