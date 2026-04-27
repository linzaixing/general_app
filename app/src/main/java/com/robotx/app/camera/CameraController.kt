package com.robotx.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wrapper around CameraX use-cases (Preview + ImageCapture + VideoCapture).
 * Hold a single instance per CameraScreen and call [bindToLifecycle] to attach
 * the use-cases to the underlying Activity / Fragment lifecycle.
 */
class CameraController(private val context: Context) {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    val isRecordingVideo: Boolean get() = activeRecording != null

    suspend fun bindToLifecycle(
        owner: LifecycleOwner,
        previewView: PreviewView,
        useFront: Boolean = false,
    ) {
        cameraSelector = if (useFront) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val provider = awaitProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        provider.bindToLifecycle(
            owner,
            cameraSelector,
            preview,
            imageCapture,
            videoCapture,
        )
    }

    fun unbind() {
        runCatching { activeRecording?.stop() }
        activeRecording = null
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
    }

    suspend fun takePhoto(): Result<File> {
        val capture = imageCapture ?: return Result.failure(IllegalStateException("Camera not bound"))
        val outFile = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        cont.resume(Result.success(outFile))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resume(Result.failure(exception))
                    }
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideo(onFinish: (Result<File>) -> Unit): Boolean {
        val vc = videoCapture ?: return false
        if (activeRecording != null) return false
        val outFile = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
        val output = FileOutputOptions.Builder(outFile).build()
        val pending = vc.output.prepareRecording(context, output)
        // Audio is intentionally NOT enabled here to keep the runtime permission
        // story simple; the dedicated voice button handles speech input.
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    val rec = activeRecording
                    activeRecording = null
                    if (event.hasError()) {
                        rec?.close()
                        outFile.delete()
                        onFinish(Result.failure(IllegalStateException(
                            "Video error code=${event.error}: ${event.cause?.message}"
                        )))
                    } else {
                        onFinish(Result.success(outFile))
                    }
                }
                else -> Unit
            }
        }
        return true
    }

    fun stopVideo() {
        activeRecording?.stop()
        activeRecording = null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }

    @Suppress("unused")
    fun toUri(file: File): Uri = Uri.fromFile(file)
}
