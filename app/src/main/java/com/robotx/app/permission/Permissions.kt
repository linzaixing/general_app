package com.robotx.app.permission

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Encapsulates the runtime permissions required by the core features:
 *   - CAMERA   (photo / video)
 *   - RECORD_AUDIO (push-to-talk)
 *
 * Composable callers can do:
 * ```
 * val perms = rememberCoreFeaturePermissions()
 * if (!perms.allGranted) perms.request()
 * ```
 *
 * The request is fired once on first composition. Manual re-request is also
 * available via the returned [CorePermissionsState.request] handle.
 */
class CorePermissionsState internal constructor(
    val allGranted: Boolean,
    private val launcher: () -> Unit,
) {
    fun request() = launcher()
}

private val REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)

@Composable
fun rememberCoreFeaturePermissions(): CorePermissionsState {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(REQUIRED.all { isGranted(ctx, it) }) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = REQUIRED.all { result[it] == true || isGranted(ctx, it) }
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(REQUIRED)
    }

    return CorePermissionsState(
        allGranted = granted,
        launcher = { launcher.launch(REQUIRED) },
    )
}

private fun isGranted(ctx: android.content.Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
