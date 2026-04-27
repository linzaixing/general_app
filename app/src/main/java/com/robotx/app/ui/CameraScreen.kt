package com.robotx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.camera.view.PreviewView
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.viewinterop.AndroidView
import com.robotx.app.R
import com.robotx.app.camera.CameraController
import com.robotx.app.data.ChatRepository
import com.robotx.app.data.MediaKind
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context) }
    val previewView = remember { PreviewView(context) }
    var useFront by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(useFront) {
        runCatching { controller.bindToLifecycle(owner, previewView, useFront) }
            .onFailure { snack.showSnackbar("打开相机失败: ${it.message}") }
    }

    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp, start = 12.dp, end = 12.dp)
                .align(Alignment.TopStart),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                )
            }
            Text(
                text = if (isRecording) stringResource(R.string.recording) else "",
                color = MaterialTheme.colorScheme.error,
            )
            IconButton(onClick = { useFront = !useFront }) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.switch_camera),
                    tint = Color.White,
                )
            }
        }

        // Hint
        Text(
            text = if (isRecording) "" else "点击拍照 · 长按录像",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 168.dp),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Shutter
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .size(86.dp)
                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .pointerInput(useFront) {
                    detectTapGestures(
                        onTap = {
                            scope.launch {
                                val r = controller.takePhoto()
                                r.onSuccess {
                                    ChatRepository.setPendingMedia(it.absolutePath, MediaKind.IMAGE)
                                    onClose()
                                }.onFailure {
                                    snack.showSnackbar("拍照失败: ${it.message}")
                                }
                            }
                        },
                        onLongPress = {
                            val started = controller.startVideo { result ->
                                isRecording = false
                                result.onSuccess { file ->
                                    ChatRepository.setPendingMedia(file.absolutePath, MediaKind.VIDEO)
                                }.onFailure { err ->
                                    scope.launch { snack.showSnackbar("录像失败: ${err.message}") }
                                }
                            }
                            if (started) isRecording = true
                        },
                        onPress = {
                            val released = tryAwaitRelease()
                            if (released && controller.isRecordingVideo) {
                                controller.stopVideo()
                                onClose()
                            }
                        },
                    )
                },
        ) {
            // Inner red dot when recording
            if (isRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }

        SnackbarHost(
            hostState = snack,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}
