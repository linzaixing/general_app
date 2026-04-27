package com.robotx.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.robotx.app.R
import com.robotx.app.data.ChatMessage
import com.robotx.app.data.ChatRole
import com.robotx.app.data.MediaKind
import java.io.File

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val align = if (isUser) Arrangement.End else Arrangement.Start

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = align,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = if (isUser) stringResource(R.string.user_role) else stringResource(R.string.ai_role),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (message.mediaKind != MediaKind.NONE && message.mediaPath != null) {
                MediaThumbnail(message.mediaPath, message.mediaKind)
            }
            Box(
                modifier = Modifier
                    .background(bubbleColor, RoundedCornerShape(16.dp))
                    .border(
                        width = if (isUser) 0.dp else 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (message.pending && message.text.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(14.dp),
                            color = textColor,
                        )
                        Text(
                            text = "  " + stringResource(R.string.thinking),
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                } else {
                    Text(
                        text = if (message.pending) message.text + "…" else message.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            message.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaThumbnail(path: String, kind: MediaKind) {
    val file = File(path)
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (file.exists()) file else path,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.heightIn(max = 200.dp),
        )
        if (kind == MediaKind.VIDEO) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}
