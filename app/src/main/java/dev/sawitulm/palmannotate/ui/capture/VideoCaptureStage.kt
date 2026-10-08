package dev.sawitulm.palmannotate.ui.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.sawitulm.palmannotate.R
import dev.sawitulm.palmannotate.ui.common.LocalToasts
import java.io.File

private val RecordRed = Color(0xFFC62828)

private enum class CameraState { STARTING, READY, FAILED }
private enum class RecordState { IDLE, RECORDING, STOPPING }

/** What the finalize listener needs after this composable may already be gone. */
private class VideoTake {
    var recording: Recording? = null
    /** Set only by the Stop button. Any other end of a recording discards the file. */
    var accepted = false
    /** The screen is being left on purpose; the discard needs no error message. */
    var abandoned = false
}

/**
 * Tablet-camera stage for the multiside-video module: live preview, a shutter and a recorder that
 * run together, so photos are taken while the video keeps recording.
 *
 * Unlike [CameraCaptureStage] the camera is bound exactly once for the lifetime of the stage.
 * Binding from an `AndroidView.update` block re-runs on recomposition and calls `unbindAll()`,
 * which would silently end the recording.
 */
@Composable
internal fun VideoCaptureStage(
    /** The current side is empty, so a photo may be taken for it. */
    canShoot: Boolean,
    allCaptured: Boolean,
    hasPhotos: Boolean,
    /** An accepted recording already exists; only a retake of an emptied side remains. */
    videoReady: Boolean,
    createPhotoFile: () -> File?,
    createVideoFiles: () -> Pair<File, File>?,
    onPhotoCaptured: (Uri) -> Unit,
    onRecordingChange: (Boolean) -> Unit,
    onBeginTake: () -> Unit,
    onVideoAccepted: () -> Unit,
    onVideoFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    Quality.FHD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD),
                ),
            )
            // Measured on the Pad 6: the default is about 20 Mbps (150 MB per minute). 12 Mbps
            // keeps 1080p detail at roughly 90 MB per minute, which matters for storage and for
            // the SAF mirror copy of every tree.
            .setTargetVideoEncodingBitRate(12_000_000)
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.PERFORMANCE }
    }
    val take = remember { VideoTake() }

    var cameraState by remember { mutableStateOf(CameraState.STARTING) }
    var recordState by remember { mutableStateOf(RecordState.IDLE) }
    var elapsedNanos by remember { mutableLongStateOf(0L) }
    var capturing by remember { mutableStateOf(false) }
    var confirmRetakeAll by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val activity = remember(context) { context.findActivity() }
    fun micRationale(): Boolean = activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
    var micRationaleBeforeAsk by remember { mutableStateOf(true) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        // No rationale before and after a denied request means the system showed no dialog
        // ("don't ask again"). Recording is mandatory here, so open the app's settings instead
        // of leaving a button that does nothing.
        if (!granted && !micRationaleBeforeAsk && !micRationale()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
    // Requested here, not with camera/location: this stage only exists once that batch resolved,
    // so the two system dialogs can never overlap.
    LaunchedEffect(Unit) {
        if (!micGranted && !videoReady) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // The permission can be granted in the system settings while this stage is in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The activity handles orientation changes itself and the camera is bound once, so nothing
    // else tells the use cases that the tablet was turned. Without this a photo taken after a
    // turn carries the orientation the stage was opened in. A running recording keeps its own.
    DisposableEffect(Unit) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        fun applyRotation() {
            runCatching {
                val rotation = view.display?.rotation ?: return
                imageCapture.targetRotation = rotation
                videoCapture.targetRotation = rotation
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) = applyRotation()
        }
        applyRotation()
        displayManager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    // The recorder's listener outlives recompositions; always call the latest callbacks.
    val latestRecordingChange by rememberUpdatedState(onRecordingChange)
    val latestAccepted by rememberUpdatedState(onVideoAccepted)
    val latestFailed by rememberUpdatedState(onVideoFailed)

    DisposableEffect(lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    videoCapture,
                )
                cameraState = CameraState.READY
            } catch (error: Exception) {
                // Some cameras cannot serve preview + photo + video at once. Say so instead of
                // leaving a spinner; the tree cannot be captured in this module on that device.
                Log.w("VideoCaptureStage", "Camera bind failed", error)
                cameraState = CameraState.FAILED
            }
        }, mainExecutor)
        onDispose {
            disposed = true
            // Leaving the stage for any reason ends the take without accepting it.
            take.abandoned = !take.accepted
            runCatching { take.recording?.stop() }
            runCatching { provider?.unbindAll() }
        }
    }

    // A screen timeout stops the activity, which unbinds the camera and loses the recording.
    DisposableEffect(recordState) {
        view.keepScreenOn = recordState != RecordState.IDLE
        onDispose { view.keepScreenOn = false }
    }

    @SuppressLint("MissingPermission") // Guarded by micGranted at the only call site.
    fun startRecording() {
        // A second tap before the button is replaced must not delete the file being written.
        if (take.recording != null || recordState != RecordState.IDLE) return
        val files = createVideoFiles()
        if (files == null) {
            latestFailed("draft storage unavailable")
            return
        }
        val (incoming, acceptedFile) = files
        take.accepted = false
        take.abandoned = false
        elapsedNanos = 0L
        try {
            take.recording = recorder
                .prepareRecording(context, FileOutputOptions.Builder(incoming).build())
                .withAudioEnabled()
                .start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Status ->
                            elapsedNanos = event.recordingStats.recordedDurationNanos
                        is VideoRecordEvent.Finalize -> {
                            take.recording = null
                            recordState = RecordState.IDLE
                            latestRecordingChange(false)
                            // Plain file operations on purpose: this can run after the screen
                            // and its ViewModel scope are gone.
                            val clean = take.accepted && !event.hasError() && incoming.length() > 0L
                            if (clean) {
                                acceptedFile.delete()
                                if (incoming.renameTo(acceptedFile)) {
                                    latestAccepted()
                                } else {
                                    incoming.delete()
                                    latestFailed("could not store the file")
                                }
                            } else {
                                incoming.delete()
                                if (!take.abandoned) {
                                    latestFailed(
                                        if (event.hasError()) "error ${event.error}" else "interrupted",
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            recordState = RecordState.RECORDING
            latestRecordingChange(true)
        } catch (error: Exception) {
            Log.w("VideoCaptureStage", "Recording could not start", error)
            take.recording = null
            incoming.delete()
            recordState = RecordState.IDLE
            latestRecordingChange(false)
            latestFailed(error.message ?: "could not start")
        }
    }

    val recording = recordState == RecordState.RECORDING
    val cameraReady = cameraState == CameraState.READY
    val shutterEnabled = cameraReady && !capturing && canShoot && (recording || videoReady)

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        when (cameraState) {
            CameraState.STARTING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            CameraState.FAILED -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                StageNotice(stringResource(R.string.video_camera_unavailable))
            }
            CameraState.READY -> Unit
        }

        if (recordState != RecordState.IDLE) {
            // Below the side thumbnails (56 dp + 12 dp inset) so the two never overlap.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color.Red))
                Spacer(Modifier.width(8.dp))
                val seconds = elapsedNanos / 1_000_000_000L
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Column(
            modifier = Modifier.padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!micGranted && !videoReady) StageNotice(stringResource(R.string.video_mic_required))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Once a recording is accepted only a retake of one side is left; there is
                // nothing to record, so the button is not shown at all.
                if (!videoReady) {
                    if (recordState == RecordState.IDLE) {
                        Button(
                            onClick = {
                                when {
                                    !micGranted -> {
                                        micRationaleBeforeAsk = micRationale()
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    hasPhotos -> confirmRetakeAll = true
                                    else -> { onBeginTake(); startRecording() }
                                }
                            },
                            enabled = cameraReady,
                            // Fixed record red: the theme's dark-mode error colour is a pale
                            // pink that leaves white text under 4.5:1.
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RecordRed,
                                contentColor = Color.White,
                                disabledContainerColor = Color.Black.copy(alpha = 0.6f),
                                disabledContentColor = Color.White.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            Icon(Icons.Default.FiberManualRecord, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.video_record))
                        }
                    } else {
                        Button(
                            onClick = {
                                // Latch before stop(): Finalize reads it to decide keep or delete.
                                take.accepted = true
                                recordState = RecordState.STOPPING
                                runCatching { take.recording?.stop() }
                            },
                            // The recording has to cover every side's photo.
                            enabled = recording && allCaptured,
                            // The default disabled colours are near-transparent and vanish
                            // over the live preview; the button has to stay visible while it
                            // waits for the remaining sides.
                            colors = ButtonDefaults.buttonColors(
                                disabledContainerColor = Color.Black.copy(alpha = 0.6f),
                                disabledContentColor = Color.White.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) {
                            if (recordState == RecordState.STOPPING) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Default.Stop, null, Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.video_stop))
                        }
                    }
                }

                FloatingActionButton(
                    onClick = {
                        if (!shutterEnabled) return@FloatingActionButton
                        capturing = true
                        val file = createPhotoFile() ?: run {
                            capturing = false
                            toasts.error(context.getString(R.string.capture_failed, "draft storage unavailable"))
                            return@FloatingActionButton
                        }
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            mainExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    onPhotoCaptured(Uri.fromFile(file))
                                }
                                override fun onError(exc: ImageCaptureException) {
                                    capturing = false
                                    toasts.error(context.getString(R.string.capture_failed, exc.message ?: ""))
                                }
                            },
                        )
                    },
                    modifier = Modifier.size(72.dp),
                    containerColor = if (shutterEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (shutterEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    if (capturing) {
                        CircularProgressIndicator(
                            Modifier.size(28.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            Icons.Default.CameraAlt,
                            stringResource(R.string.cd_capture),
                            Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmRetakeAll) {
        AlertDialog(
            onDismissRequest = { confirmRetakeAll = false },
            title = { Text(stringResource(R.string.video_retake_all_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetakeAll = false
                    onBeginTake()
                    startRecording()
                }) { Text(stringResource(R.string.video_record)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRetakeAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** One line of text that stays readable over a bright camera preview. */
@Composable
private fun StageNotice(text: String) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
