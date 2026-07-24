package com.digital.datamosh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digital.datamosh.camera.CameraEngine
import com.digital.datamosh.camera.CameraUiState
import com.digital.datamosh.camera.CaptureAction
import com.digital.datamosh.camera.CapturePhase
import com.digital.datamosh.camera.FilterMode
import com.digital.datamosh.camera.PreviewLayers
import com.digital.datamosh.camera.RecordingConfiguration
import com.digital.datamosh.camera.SegmentMode
import com.digital.datamosh.camera.VideoCodec
import com.digital.datamosh.camera.VideoResolution
import com.digital.datamosh.camera.clockwiseDeviceOrientation
import com.digital.datamosh.camera.reduceCaptureState
import com.digital.datamosh.ui.theme.DatamoshTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private lateinit var controller: CameraController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        controller = CameraController(this)
        setContent {
            DatamoshTheme {
                DatamoshApp(controller)
            }
        }
    }

    override fun onStop() {
        controller.pauseForLifecycle()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        controller.resumeForLifecycle()
    }

    override fun onDestroy() {
        controller.release(isFinishing)
        super.onDestroy()
    }
}

private class CameraController(
    private val activity: Activity,
) : CameraEngine.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences = RecordingPreferences(activity)
    private val mutableState = MutableStateFlow(
        CameraUiState(
            recordingConfiguration = preferences.loadConfiguration(),
            filterMode = preferences.loadFilter(),
        ),
    )
    val state: StateFlow<CameraUiState> = mutableState.asStateFlow()
    private var engine: CameraEngine? = null
    private var layers: PreviewLayers? = null
    private var torch = false
    @Volatile private var deviceOrientationDegrees = 0
    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val sensorDegrees = ((orientation + 45) / 90 * 90) % 360
            // OrientationEventListener increases counter-clockwise from portrait, while camera
            // orientation hints use the display's clockwise rotation convention.
            deviceOrientationDegrees = clockwiseDeviceOrientation(sensorDegrees)
        }
    }

    init {
        orientationListener.enable()
    }

    fun attach(newLayers: PreviewLayers) {
        layers = newLayers
        if (engine == null) {
            engine = CameraEngine(
                activity,
                this,
                mutableState.value.recordingConfiguration,
                mutableState.value.filterMode,
            ).also { it.attach(newLayers) }
        }
    }

    fun holdStart() {
        val current = mutableState.value
        if (!current.canHold) return
        dispatch(CaptureAction.HoldStarted)
        engine?.startSegment(current.nextMode, deviceOrientationDegrees)
    }

    fun holdEnd() {
        if (mutableState.value.isHolding) engine?.pauseSegment()
    }

    fun resetNext() = dispatch(CaptureAction.ResetNext)

    fun switchCamera() {
        if (mutableState.value.phase !in setOf(CapturePhase.READY, CapturePhase.PAUSED)) return
        torch = false
        mutableState.value = mutableState.value.copy(
            phase = CapturePhase.PREPARING,
            cameraReady = false,
            torchEnabled = false,
        )
        engine?.switchCamera()
    }

    fun setRecordingConfiguration(configuration: RecordingConfiguration) {
        val current = mutableState.value
        if (!current.canChangeRecordingSettings) return
        if (engine?.setRecordingConfiguration(configuration) == true) {
            preferences.saveConfiguration(configuration)
            mutableState.value = current.copy(
                phase = CapturePhase.PREPARING,
                cameraReady = false,
                recordingConfiguration = configuration,
                warning = null,
            )
        }
    }

    fun toggleFilter() {
        val current = mutableState.value
        if (current.isHolding || current.phase == CapturePhase.FINALIZING) return
        val requested = if (current.filterMode == FilterMode.REGULAR) {
            FilterMode.INVERTED
        } else {
            FilterMode.REGULAR
        }
        if (engine?.setFilterMode(requested) == true) {
            preferences.saveFilter(requested)
            mutableState.value = current.copy(filterMode = requested, warning = null)
        }
    }

    fun toggleTorch() {
        val current = mutableState.value
        if (!current.torchAvailable || current.usingFrontCamera || current.isHolding) return
        torch = !torch
        engine?.setTorch(torch)
        mutableState.value = current.copy(torchEnabled = torch)
    }

    fun finish() {
        if (!mutableState.value.canFinish) return
        dispatch(CaptureAction.FinalizeStarted)
        engine?.finish()
    }

    fun newTake() {
        engine?.release(false)
        engine = null
        torch = false
        dispatch(CaptureAction.NewTake)
    }

    fun deleteAndStartOver(uri: Uri) {
        activity.contentResolver.delete(uri, null, null)
        newTake()
    }

    fun pauseForLifecycle() {
        orientationListener.disable()
        engine?.pauseForLifecycle()
    }

    fun resumeForLifecycle() {
        orientationListener.enable()
        engine?.resumeForLifecycle()
    }

    fun release(abandon: Boolean) {
        orientationListener.disable()
        engine?.release(abandon && mutableState.value.savedUri == null)
        engine = null
    }

    override fun onCameraReady(
        front: Boolean,
        torchAvailable: Boolean,
        availableConfigurations: Set<RecordingConfiguration>,
        activeConfiguration: RecordingConfiguration,
        invertedFilterAvailable: Boolean,
        activeFilterMode: FilterMode,
    ) = onMain {
        dispatch(CaptureAction.CameraReady)
        mutableState.value = mutableState.value.copy(
            usingFrontCamera = front,
            torchAvailable = torchAvailable,
            torchEnabled = false,
            availableConfigurations = availableConfigurations,
            recordingConfiguration = activeConfiguration,
            invertedFilterAvailable = invertedFilterAvailable,
            filterMode = activeFilterMode,
        )
    }

    override fun onConfigurationFallback(
        configuration: RecordingConfiguration,
        message: String,
    ) = onMain {
        preferences.saveConfiguration(configuration)
        mutableState.value = mutableState.value.copy(
            recordingConfiguration = configuration,
            warning = message,
        )
    }

    override fun onFilterFallback(filterMode: FilterMode, message: String) = onMain {
        preferences.saveFilter(filterMode)
        mutableState.value = mutableState.value.copy(filterMode = filterMode, warning = message)
    }

    override fun onSegmentFinished(durationMs: Long?) = onMain {
        dispatch(
            if (durationMs == null) CaptureAction.HoldDiscarded
            else CaptureAction.HoldCommitted(durationMs),
        )
    }

    override fun onSaved(uri: Uri) = onMain {
        dispatch(CaptureAction.Finalized(uri))
    }

    override fun onWarning(message: String?) = onMain {
        dispatch(CaptureAction.Warning(message))
    }

    override fun onError(message: String) = onMain {
        dispatch(CaptureAction.CameraFailed(message))
    }

    private fun dispatch(action: CaptureAction) {
        mutableState.value = reduceCaptureState(mutableState.value, action)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

@Composable
private fun DatamoshApp(controller: CameraController) {
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
    var granted by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> granted = permissions.all { results[it] == true } }

    if (!granted) {
        PermissionScreen(
            onGrant = { launcher.launch(permissions) },
            onSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            },
        )
        return
    }

    val state by controller.state.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    LaunchedEffect(state.savedUri) {
        if (state.savedUri != null) showOptions = false
    }
    BackHandler(enabled = showOptions) { showOptions = false }
    AnimatedContent(targetState = state.savedUri, label = "camera-result") { uri ->
        if (uri == null) {
            Box(Modifier.fillMaxSize()) {
                CameraScreen(
                    state = state,
                    controller = controller,
                    onOpenOptions = { showOptions = true },
                )
                if (showOptions) {
                    OptionsScreen(
                        state = state,
                        onBack = { showOptions = false },
                        onConfigurationChange = controller::setRecordingConfiguration,
                    )
                }
            }
        } else {
            ResultScreen(
                uri = uri,
                onShare = { shareVideo(context as Activity, uri) },
                onDelete = { controller.deleteAndStartOver(uri) },
                onNewTake = controller::newTake,
            )
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onSettings: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_monochrome),
                        contentDescription = "Datamosh logo",
                        modifier = Modifier.size(76.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Camera, bent", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                "Datamosh needs the camera and microphone to create a single hold-to-record video. Your takes stay on this device.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onGrant) { Text("Allow camera & microphone") }
            TextButton(onClick = onSettings) { Text("Open app settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    state: CameraUiState,
    controller: CameraController,
    onOpenOptions: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var gridEnabled by remember { mutableStateOf(false) }
    var elapsedDuringHold by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isHolding) {
        val started = android.os.SystemClock.elapsedRealtime()
        while (state.isHolding) {
            elapsedDuringHold = android.os.SystemClock.elapsedRealtime() - started
            delay(100)
        }
        elapsedDuringHold = 0
    }
    val shownDuration = state.activeDurationMs + elapsedDuringHold

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .align(Alignment.Center),
        ) {
            AndroidView(
                factory = { PreviewLayers(it).also(controller::attach) },
                modifier = Modifier.fillMaxSize(),
            )
            if (gridEnabled) CameraGrid()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f)),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 34.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraIconButton(
                    icon = Icons.Rounded.RestartAlt,
                    description = if (state.nextMode == SegmentMode.CLEAN) {
                        "Next segment is clean"
                    } else {
                        "Reset datamosh for next segment"
                    },
                    enabled = state.canReset,
                    selected = state.nextMode == SegmentMode.CLEAN && state.segmentCount > 0,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.resetNext()
                    },
                )

                HoldButton(
                    state = state,
                    onDown = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        controller.holdStart()
                    },
                    onUp = controller::holdEnd,
                )

                CameraIconButton(
                    icon = Icons.Rounded.Check,
                    description = "Finish and save",
                    enabled = state.canFinish,
                    selected = state.phase == CapturePhase.FINALIZING,
                    onClick = controller::finish,
                )
            }
            if (state.warning != null || state.error != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    color = Color.Black.copy(alpha = 0.74f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        state.error ?: state.warning.orEmpty(),
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CameraIconButton(
                icon = if (state.torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                description = "Toggle torch",
                enabled = state.torchAvailable && !state.usingFrontCamera && !state.isHolding,
                selected = state.torchEnabled,
                onClick = controller::toggleTorch,
            )
            CameraIconButton(
                icon = if (gridEnabled) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                description = "Toggle composition grid",
                enabled = true,
                selected = gridEnabled,
                onClick = { gridEnabled = !gridEnabled },
            )
            CameraIconButton(
                icon = Icons.Rounded.InvertColors,
                description = if (state.invertedFilterAvailable) {
                    "Toggle inverted filter"
                } else {
                    "Inverted filter unavailable on this camera"
                },
                enabled = state.invertedFilterAvailable &&
                    !state.isHolding &&
                    state.phase != CapturePhase.FINALIZING,
                selected = state.filterMode == FilterMode.INVERTED,
                onClick = controller::toggleFilter,
            )
            CameraIconButton(
                icon = Icons.Rounded.Settings,
                description = "Recording options",
                enabled = !state.isHolding && state.phase != CapturePhase.FINALIZING,
                onClick = onOpenOptions,
            )
            CameraIconButton(
                icon = Icons.Rounded.Cameraswitch,
                description = "Switch camera",
                enabled = !state.isHolding && state.phase != CapturePhase.FINALIZING,
                onClick = controller::switchCamera,
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 70.dp),
            color = Color.Black.copy(alpha = 0.58f),
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if ((state.activeMode ?: state.nextMode) == SegmentMode.MOSH) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        contentDescription = "Datamosh mode",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    "${formatDuration(shownDuration)}  ·  " +
                        "${state.recordingConfiguration.resolution.label}  " +
                        "${state.targetFps} fps",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun OptionsScreen(
    state: CameraUiState,
    onBack: () -> Unit,
    onConfigurationChange: (RecordingConfiguration) -> Unit,
) {
    val current = state.recordingConfiguration
    val unlocked = state.canChangeRecordingSettings
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to camera")
                }
                Text(
                    "Recording options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                if (!unlocked) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            "Start a new take to change resolution, frame rate, or codec.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }

                OptionsHeading("Resolution")
                VideoResolution.entries.forEach { resolution ->
                    val candidate = current.copy(resolution = resolution)
                    val supported = candidate in state.availableConfigurations
                    RecordingOption(
                        title = resolution.label,
                        subtitle = when {
                            !unlocked -> null
                            supported -> if (resolution == VideoResolution.FULL_HD_1080P) {
                                "Sharper video with higher processing cost"
                            } else {
                                "Best compatibility and classic datamosh texture"
                            }
                            else -> "Unavailable with the selected frame rate and codec"
                        },
                        selected = current.resolution == resolution,
                        enabled = unlocked && supported,
                        onClick = { onConfigurationChange(candidate) },
                    )
                }

                Spacer(Modifier.height(18.dp))
                OptionsHeading("Frame rate")
                listOf(30, 60).forEach { fps ->
                    val candidate = current.copy(fps = fps)
                    val supported = candidate in state.availableConfigurations
                    RecordingOption(
                        title = "$fps fps",
                        subtitle = when {
                            !unlocked -> null
                            supported && fps == 60 -> "Smoother motion and denser glitch movement"
                            supported -> "Best compatibility"
                            else -> "Unavailable at the selected resolution and codec"
                        },
                        selected = current.fps == fps,
                        enabled = unlocked && supported,
                        onClick = { onConfigurationChange(candidate) },
                    )
                }

                Spacer(Modifier.height(18.dp))
                OptionsHeading("Codec")
                VideoCodec.entries.forEach { codec ->
                    val candidate = current.copy(codec = codec)
                    val supported = candidate in state.availableConfigurations
                    RecordingOption(
                        title = codec.label,
                        subtitle = when {
                            !unlocked -> null
                            codec == VideoCodec.HEVC && supported ->
                                "Experimental — artifacts and playback vary by device"
                            codec == VideoCodec.HEVC ->
                                "Unavailable for the selected resolution and frame rate"
                            supported -> "Most compatible"
                            else -> "Unavailable on this device"
                        },
                        selected = current.codec == codec,
                        enabled = unlocked && supported,
                        onClick = { onConfigurationChange(candidate) },
                    )
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun OptionsHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RecordingOption(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled || selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.55f,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CameraGrid() {
    Canvas(Modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.42f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width / 3f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 3f, size.height), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width * 2f / 3f, size.height), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height / 3f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3f), strokeWidth = 1f)
        drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(0f, size.height * 2f / 3f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height * 2f / 3f), strokeWidth = 1f)
    }
}

@Composable
private fun HoldButton(
    state: CameraUiState,
    onDown: () -> Unit,
    onUp: () -> Unit,
) {
    val pressed = state.isHolding
    val canHold by rememberUpdatedState(state.canHold)
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    val scale by animateFloatAsState(if (pressed) 0.78f else 1f, label = "hold-scale")
    val mode = state.activeMode ?: state.nextMode
    val color = if (mode == SegmentMode.MOSH) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(86.dp)
            .semantics { contentDescription = "Hold to record ${mode.name.lowercase()} segment" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (canHold) {
                        currentOnDown()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            currentOnUp()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(82.dp)
                .border(3.dp, Color.White.copy(alpha = if (state.canHold || pressed) 1f else 0.32f), CircleShape),
        )
        Box(
            Modifier
                .size(64.dp)
                .scale(scale)
                .background(
                    color.copy(alpha = if (state.canHold || pressed) 1f else 0.28f),
                    if (pressed) RoundedCornerShape(22.dp) else CircleShape,
                ),
        )
    }
}

@Composable
private fun CameraIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.28f)
                selected -> MaterialTheme.colorScheme.secondary
                else -> Color.White
            },
            modifier = Modifier.size(29.dp),
        )
    }
}

@Composable
private fun ResultScreen(
    uri: Uri,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onNewTake: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Text("Share")
                }
                Button(onClick = onNewTake, modifier = Modifier.weight(1f)) {
                    Text("New take")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setVideoURI(uri)
                        setOnPreparedListener {
                            it.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this take?") },
            text = { Text("The video will be removed from your gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
        )
    }
}

private fun shareVideo(activity: Activity, uri: Uri) {
    activity.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share datamosh",
        ),
    )
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
