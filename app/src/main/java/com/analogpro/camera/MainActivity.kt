package com.analogpro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD4AF37),
                    secondary = Color(0xFFE53935),
                    background = Color(0xFF08090C),
                    surface = Color(0xFF14161B)
                )
            ) {
                AnalogProRoot()
            }
        }
    }
}

@Composable
fun AnalogProRoot() {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            val list = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
                list.add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            permLauncher.launch(list.toTypedArray())
        }
    }

    if (hasPermissions) {
        CinemaCameraScreen()
    } else {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF08090C)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ANALOG PRO CINEMA", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("Concede permiso de Cámara y Micrófono para continuar", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun CinemaCameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    // Sonidos nativos de cámara (Foto, Inicio de Video y Corte de Video)
    val mediaActionSound = remember {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }
    DisposableEffect(Unit) {
        onDispose { mediaActionSound.release() }
    }

    // 1. Rotación fluida de iconos manteniendo la plantilla fija tal cual
    var isDeviceLandscape by remember { mutableStateOf(false) }
    var uiRotationDeg by remember { mutableStateOf(0f) }
    val animatedIconRotation by animateFloatAsState(targetValue = uiRotationDeg, label = "iconRot")

    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                when (orientation) {
                    in 60..120 -> {
                        isDeviceLandscape = true
                        uiRotationDeg = -90f
                    }
                    in 240..300 -> {
                        isDeviceLandscape = true
                        uiRotationDeg = 90f
                    }
                    in 150..210 -> {
                        isDeviceLandscape = false
                        uiRotationDeg = 180f
                    }
                    in 0..35, in 325..360 -> {
                        isDeviceLandscape = false
                        uiRotationDeg = 0f
                    }
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    // 2. Inventario de sensores y lentes físicos
    val availableLenses = remember {
        val list = ArrayList<LensCameraInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val isFront = (facing == CameraCharacteristics.LENS_FACING_FRONT)
                val sensorOrient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: if (isFront) 270 else 90
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.2f)
                val focal = focals.firstOrNull() ?: 4.2f
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES) ?: floatArrayOf(1.8f)
                val aperture = apertures.firstOrNull() ?: 1.8f

                val hwInt = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val hwName = when (hwInt) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Pro RAW + YUV)"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Manual Total)"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    else -> "LEGACY"
                }

                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                val supportsManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

                val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
                val maxRaw = if (supportsRaw) map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } else null
                val videoSizes = map?.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
                val maxVideo = videoSizes.filter { it.width <= 3840 && it.height <= 2160 }
                    .maxByOrNull { it.width * it.height } ?: Size(1920, 1080)

                val mp = String.format(Locale.US, "%.1f MP (%dx%d)", (maxJpeg.width * maxJpeg.height) / 1_000_000f, maxJpeg.width, maxJpeg.height)
                val physSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val physStr = if (physSize != null) String.format(Locale.US, "%.2f x %.2f mm", physSize.width, physSize.height) else "Estándar"
                val physArea = if (physSize != null) physSize.width * physSize.height else 24f

                val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
                val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(100_000L, 1_000_000_000L)
                val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-12, 12)
                val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0.33f
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val maxFlashLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                } else 1

                val badge = when {
                    isFront -> "FRONT"
                    focal < 2.8f -> "0.5x UW"
                    focal > 6.0f -> "3x TELE"
                    else -> "1x MAIN"
                }
                val label = when {
                    isFront -> "Frontal (${String.format(Locale.US, "%.1fmm", focal)})"
                    focal < 2.8f -> "Ultra Gran Angular (${String.format(Locale.US, "%.1fmm", focal)})"
                    focal > 6.0f -> "Teleobjetivo (${String.format(Locale.US, "%.1fmm", focal)})"
                    else -> "Principal (${String.format(Locale.US, "%.1fmm", focal)})"
                }

                list.add(
                    LensCameraInfo(
                        cameraId = id,
                        label = label,
                        badge = badge,
                        isFront = isFront,
                        sensorOrientation = sensorOrient,
                        focalLengthMm = focal,
                        aperture = aperture,
                        hardwareLevel = hwName,
                        maxResolutionMp = mp,
                        maxJpegSize = maxJpeg,
                        maxRawSize = maxRaw,
                        maxVideoSize = maxVideo,
                        supportsRaw = supportsRaw,
                        supportsManualSensor = supportsManual,
                        physicalSizeMm = physStr,
                        physicalAreaMm2 = physArea,
                        isoRange = isoRange,
                        exposureTimeRangeNs = expRange,
                        evRange = evRange,
                        evStep = evStep,
                        minFocusDistance = minFocus,
                        maxZoom = maxZoom.coerceAtLeast(4f),
                        hasFlash = hasFlash,
                        maxFlashStrengthLevel = maxFlashLevel
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }

    var activeLens by remember {
        mutableStateOf(availableLenses.firstOrNull { !it.isFront } ?: availableLenses.first())
    }

    // 3. Estado de Formato (Solo 16:9 y 4:3, sin Open Gate), Films, VFX y PRO
    var aspectMode by remember { mutableStateOf(AspectRatioMode.RATIO_16_9) }
    var activeDockTab by remember { mutableStateOf(DockTab.NONE) }

    var selectedPreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) }
    var customLut by remember { mutableStateOf<CubeLut3D?>(null) }
    var vfxConfig by remember { mutableStateOf(VfxConfig()) }

    var isManualMode by remember { mutableStateOf(false) }
    var selectedIso by remember { mutableStateOf(100) }
    var selectedShutterNs by remember { mutableStateOf(33_333_333L) }
    var exposureEvSteps by remember { mutableStateOf(0) }
    var captureFormat by remember { mutableStateOf(CaptureFormat.JPG) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var flashStrengthPercent by remember { mutableStateOf(0.8f) }

    // Zoom táctil, Enfoque táctil con mini barra de exposición a la derecha, y Detección Facial
    var currentZoom by remember { mutableStateOf(1.0f) }
    var focusNormX by remember { mutableStateOf(0.5f) }
    var focusNormY by remember { mutableStateOf(0.5f) }
    var showFocusEvBar by remember { mutableStateOf(false) }
    var lastFocusTouchMillis by remember { mutableStateOf(0L) }
    val detectedFacesNorm = remember { mutableStateListOf<RectF>() }

    // Ocultar automáticamente la mini barra de exposición 3.5s después de dejar de ajustar
    LaunchedEffect(lastFocusTouchMillis) {
        if (lastFocusTouchMillis > 0L) {
            delay(3500L)
            showFocusEvBar = false
        }
    }

    var sceneLight by remember { mutableStateOf(SceneLightAnalysis()) }
    var lowLightPromptDismissed by remember { mutableStateOf(false) }

    // Video Recording y Timecode central
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var mediaRecorderRef by remember { mutableStateOf<MediaRecorder?>(null) }
    var pendingVideoPfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    // Galería Persistente
    val persistedMedia = remember { mutableStateListOf<PersistedMediaItem>() }
    var latestThumbnailBmp by remember { mutableStateOf<Bitmap?>(null) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var showHardwareModal by remember { mutableStateOf(false) }
    var isCapturingPhoto by remember { mutableStateOf(false) }

    fun refreshGalleryFromMediaStore() {
        scope.launch(Dispatchers.IO) {
            val items = MediaStoreRepository.loadPersistedMedia(context)
            val thumb = items.firstOrNull()?.let { MediaStoreRepository.loadThumbnailBitmap(context, it, 200) }
            withContext(Dispatchers.Main) {
                persistedMedia.clear()
                persistedMedia.addAll(items)
                latestThumbnailBmp = thumb
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshGalleryFromMediaStore()
    }

    LaunchedEffect(isRecordingVideo) {
        if (isRecordingVideo) {
            recordingSeconds = 0
            while (isRecordingVideo) {
                delay(1000L)
                recordingSeconds += 1
            }
        } else {
            recordingSeconds = 0
        }
    }

    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val parsed = CubeLutParser.parseUri(context, uri, "Custom .CUBE")
                withContext(Dispatchers.Main) {
                    if (parsed != null) {
                        customLut = parsed
                        Toast.makeText(context, "LUT cargado: ${parsed.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var jpegReader by remember { mutableStateOf<ImageReader?>(null) }
    var rawReader by remember { mutableStateOf<ImageReader?>(null) }
    var currentCharacteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    var viewBoxSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    var previewBufferSize by remember { mutableStateOf(Size(1920, 1080)) }

    /**
     * Calcula la matriz del TextureView considerando la orientación exacta del sensor
     * (frontal o trasero) para eliminar por completo cualquier deformación.
     */
    fun applyNonDistortingTransform(texView: TextureView, viewW: Int, viewH: Int, bufSize: Size, lens: LensCameraInfo) {
        if (viewW <= 0 || viewH <= 0) return
        val swapped = (lens.sensorOrientation == 90 || lens.sensorOrientation == 270)
        val bufferWidth = if (swapped) bufSize.height.toFloat() else bufSize.width.toFloat()
        val bufferHeight = if (swapped) bufSize.width.toFloat() else bufSize.height.toFloat()

        val viewRatio = viewW.toFloat() / viewH.toFloat()
        val bufferRatio = bufferWidth / bufferHeight

        val scaleX: Float
        val scaleY: Float
        if (bufferRatio > viewRatio) {
            scaleY = 1f
            scaleX = bufferRatio / viewRatio
        } else {
            scaleX = 1f
            scaleY = viewRatio / bufferRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        texView.setTransform(matrix)
    }

    // Callback que recibe las caras detectadas por el ISP de la cámara en tiempo real
    val previewCaptureCallback = remember(activeLens) {
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val faces = result.get(CaptureResult.STATISTICS_FACES)
                val activeArray = currentCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (faces != null && activeArray != null && activeArray.width() > 0 && activeArray.height() > 0) {
                    val mapped = faces.take(5).map { face ->
                        val b = face.bounds
                        // Mapear coordenadas del sensor al visor vertical (0..1)
                        val nx = if (activeLens.isFront) {
                            1f - (b.centerY().toFloat() / activeArray.height().toFloat())
                        } else {
                            1f - (b.centerY().toFloat() / activeArray.height().toFloat())
                        }
                        val ny = if (activeLens.isFront) {
                            1f - (b.centerX().toFloat() / activeArray.width().toFloat())
                        } else {
                            b.centerX().toFloat() / activeArray.width().toFloat()
                        }
                        val nw = (b.height().toFloat() / activeArray.height().toFloat()).coerceIn(0.08f, 0.6f)
                        val nh = (b.width().toFloat() / activeArray.width().toFloat()).coerceIn(0.08f, 0.6f)
                        RectF(
                            (nx - nw / 2f).coerceIn(0.02f, 0.98f),
                            (ny - nh / 2f).coerceIn(0.02f, 0.98f),
                            (nx + nw / 2f).coerceIn(0.02f, 0.98f),
                            (ny + nh / 2f).coerceIn(0.02f, 0.98f)
                        )
                    }
                    detectedFacesNorm.clear()
                    detectedFacesNorm.addAll(mapped)
                } else if (detectedFacesNorm.isNotEmpty()) {
                    detectedFacesNorm.clear()
                }
            }
        }
    }

    fun applyRepeatingPreviewParameters(
        cam: CameraDevice,
        session: CameraCaptureSession,
        texView: TextureView,
        triggerAfPoint: Offset? = null
    ) {
        val surfaceTexture = texView.surfaceTexture ?: return
        val chars = currentCharacteristics ?: return
        try {
            val template = if (isRecordingVideo) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val req = cam.createCaptureRequest(template).apply {
                addTarget(Surface(surfaceTexture))
                if (isRecordingVideo) {
                    mediaRecorderRef?.surface?.let { addTarget(it) }
                }

                // Máxima fidelidad cromática y nitidez del ISP
                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)

                // Activar Detección Facial por hardware si el sensor lo soporta
                val faceModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: intArrayOf()
                if (faceModes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
                    set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
                }

                // Zoom en Foto y Video
                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
                } else if (activeArray != null && currentZoom > 1.0f) {
                    val cropW = (activeArray.width() / currentZoom).toInt()
                    val cropH = (activeArray.height() / currentZoom).toInt()
                    val cropX = (activeArray.width() - cropW) / 2
                    val cropY = (activeArray.height() - cropH) / 2
                    set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                }

                // Enfoque Táctil donde se toque en pantalla
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (activeArray != null) {
                    val meterW = (activeArray.width() * 0.14f).toInt().coerceAtLeast(100)
                    val meterH = (activeArray.height() * 0.14f).toInt().coerceAtLeast(100)
                    val cx = (focusNormX * activeArray.width()).toInt().coerceIn(meterW, activeArray.width() - meterW)
                    val cy = (focusNormY * activeArray.height()).toInt().coerceIn(meterH, activeArray.height() - meterH)
                    val rect = MeteringRectangle(
                        cx - meterW / 2,
                        cy - meterH / 2,
                        meterW,
                        meterH,
                        MeteringRectangle.METERING_WEIGHT_MAX - 1
                    )
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
                }

                // Exposición Manual (PRO) o Automática con Compensación EV (barra lateral de enfoque)
                if (isManualMode && activeLens.supportsManualSensor) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso.coerceIn(activeLens.isoRange.lower, activeLens.isoRange.upper))
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs.coerceIn(activeLens.exposureTimeRangeNs.lower, activeLens.exposureTimeRangeNs.upper))
                } else {
                    val aeMode = when (flashMode) {
                        FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                        FlashMode.ON, FlashMode.DISPOSABLE -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        else -> CaptureRequest.CONTROL_AE_MODE_ON
                    }
                    set(CaptureRequest.CONTROL_AE_MODE, aeMode)
                    val totalEv = exposureEvSteps.coerceIn(activeLens.evRange.lower, activeLens.evRange.upper)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, totalEv)
                }

                if (flashMode == FlashMode.TORCH && activeLens.hasFlash) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activeLens.maxFlashStrengthLevel > 1) {
                        try {
                            val level = (flashStrengthPercent * activeLens.maxFlashStrengthLevel).toInt()
                                .coerceIn(1, activeLens.maxFlashStrengthLevel)
                            cameraManager.turnOnTorchWithStrengthLevel(activeLens.cameraId, level)
                        } catch (_: Exception) {}
                    }
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }

                if (triggerAfPoint != null) {
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                }
            }
            session.setRepeatingRequest(req.build(), previewCaptureCallback, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
    }

    fun openSelectedCamera(lens: LensCameraInfo, texView: TextureView) {
        try {
            cameraDevice?.close()
            val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            currentCharacteristics = chars

            val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val allPreviewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
            val targetR = aspectMode.ratioPortrait
            val chosenPreview = allPreviewSizes
                .filter { it.width <= 1920 && it.height <= 1920 }
                .minByOrNull { size ->
                    val r = size.height.toFloat() / size.width.toFloat()
                    abs(r - targetR)
                } ?: Size(1920, 1080)

            previewBufferSize = chosenPreview

            jpegReader?.close()
            jpegReader = ImageReader.newInstance(lens.maxJpegSize.width, lens.maxJpegSize.height, ImageFormat.JPEG, 2)

            rawReader?.close()
            rawReader = if (lens.supportsRaw && lens.maxRawSize != null) {
                ImageReader.newInstance(lens.maxRawSize.width, lens.maxRawSize.height, ImageFormat.RAW_SENSOR, 2)
            } else null

            cameraManager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val st = texView.surfaceTexture ?: return
                    st.setDefaultBufferSize(chosenPreview.width, chosenPreview.height)
                    applyNonDistortingTransform(texView, texView.width, texView.height, chosenPreview, lens)

                    val previewSurface = Surface(st)
                    val targets = mutableListOf(previewSurface)
                    jpegReader?.surface?.let { targets.add(it) }
                    rawReader?.surface?.let { targets.add(it) }

                    camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            applyRepeatingPreviewParameters(camera, session, texView)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, null)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close() }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(
        isManualMode, selectedIso, selectedShutterNs, exposureEvSteps,
        flashMode, flashStrengthPercent, currentZoom,
        focusNormX, focusNormY, captureSession
    ) {
        val cam = cameraDevice ?: return@LaunchedEffect
        val session = captureSession ?: return@LaunchedEffect
        val tex = textureViewRef.value ?: return@LaunchedEffect
        applyRepeatingPreviewParameters(cam, session, tex)
    }

    LaunchedEffect(aspectMode, viewBoxSize, previewBufferSize, activeLens) {
        val tex = textureViewRef.value ?: return@LaunchedEffect
        applyNonDistortingTransform(tex, tex.width, tex.height, previewBufferSize, activeLens)
    }

    // Aplicar el Film / LUT en TIEMPO REAL directamente sobre el TextureView (en Foto y Video)
    LaunchedEffect(selectedPreset, activeLens, exposureEvSteps, vfxConfig.temperature, vfxConfig.tint) {
        val tex = textureViewRef.value ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (selectedPreset.isNoLut && exposureEvSteps == 0 && vfxConfig.temperature == 0f && vfxConfig.tint == 0f) {
                tex.setRenderEffect(null)
            } else {
                val liveMatrix = AnalogImageProcessor.buildSensorAdaptedMatrix(
                    preset = selectedPreset,
                    isFrontSensor = activeLens.isFront,
                    sensorAreaMm2 = activeLens.physicalAreaMm2,
                    exposureBias = 0f,
                    temperature = vfxConfig.temperature,
                    tint = vfxConfig.tint
                )
                tex.setRenderEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(liveMatrix))
                )
            }
        }
    }

    // Analizador de escena en tiempo real (Flares solares, Anamórficos y alerta de Baja Luz)
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(120L)
            val tex = textureViewRef.value
            if (tex != null && tex.isAvailable) {
                val tinyBmp = withContext(Dispatchers.Main) {
                    try { tex.getBitmap(64, 64) } catch (_: Exception) { null }
                }
                if (tinyBmp != null) {
                    val analysis = withContext(Dispatchers.Default) {
                        AnalogImageProcessor.analyzeSceneLight(tinyBmp)
                    }
                    tinyBmp.recycle()
                    sceneLight = analysis
                }
            }
        }
    }

    // Grabación de Video en máxima calidad con sonido de inicio y corte
    fun toggleVideoRecording() {
        val cam = cameraDevice ?: return
        val texView = textureViewRef.value ?: return
        val st = texView.surfaceTexture ?: return

        if (isRecordingVideo) {
            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            try { mediaRecorderRef?.stop() } catch (_: Exception) {}
            try { mediaRecorderRef?.release() } catch (_: Exception) {}
            mediaRecorderRef = null
            try { pendingVideoPfd?.close() } catch (_: Exception) {}
            pendingVideoPfd = null
            isRecordingVideo = false
            refreshGalleryFromMediaStore()
            Toast.makeText(context, "Video guardado en la Galería de la App", Toast.LENGTH_SHORT).show()
            openSelectedCamera(activeLens, texView)
        } else {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "ANALOG_VID_${timestamp}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AnalogPro")
                    }
                }
                val videoUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
                val pfd = context.contentResolver.openFileDescriptor(videoUri, "w") ?: return
                pendingVideoPfd = pfd

                val vidSize = activeLens.maxVideoSize
                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setOutputFile(pfd.fileDescriptor)
                    setVideoEncodingBitRate(42_000_000) // 42 Mbps máxima calidad
                    setVideoFrameRate(30)
                    setVideoSize(vidSize.width, vidSize.height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(48_000)
                    val hint = if (activeLens.isFront) {
                        if (isDeviceLandscape) 0 else 270
                    } else {
                        if (isDeviceLandscape) 0 else 90
                    }
                    setOrientationHint(hint)
                    prepare()
                }

                mediaRecorderRef = recorder
                val previewSurface = Surface(st)
                val recordSurface = recorder.surface

                cam.createCaptureSession(
                    listOf(previewSurface, recordSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            isRecordingVideo = true
                            mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
                            applyRepeatingPreviewParameters(cam, session, texView)
                            try {
                                recorder.start()
                            } catch (e: Exception) {
                                isRecordingVideo = false
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    },
                    null
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Error de video: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Disparo Fotográfico en Máxima Resolución Nativa del Sensor (Frontal y Trasero)
     * eliminando por completo cualquier deformación y guardando la Metadata EXIF.
     */
    fun capturePhoto() {
        val texView = textureViewRef.value ?: return
        val chars = currentCharacteristics ?: return
        if (isCapturingPhoto) return
        isCapturingPhoto = true
        mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)

        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dateLabel = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())
                val lens = activeLens

                // 1. Captura RAW (.DNG 16-bit) si fue elegida
                if ((captureFormat == CaptureFormat.RAW || captureFormat == CaptureFormat.RAW_JPG) && lens.supportsRaw && rawReader != null) {
                    val cam = cameraDevice
                    val session = captureSession
                    if (cam != null && session != null) {
                        var lastRes: TotalCaptureResult? = null
                        val rawReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rawReader!!.surface)
                        }
                        rawReader?.setOnImageAvailableListener({ reader ->
                            val img: Image? = reader.acquireLatestImage()
                            val r = lastRes
                            if (img != null && r != null) {
                                val dngValues = ContentValues().apply {
                                    put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_${timestamp}.dng")
                                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro/RAW")
                                    }
                                }
                                val dngUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, dngValues)
                                if (dngUri != null) {
                                    context.contentResolver.openOutputStream(dngUri)?.use { out ->
                                        val creator = DngCreator(chars, r)
                                        creator.writeImage(out, img)
                                        creator.close()
                                    }
                                }
                                img.close()
                            } else {
                                img?.close()
                            }
                        }, Handler(Looper.getMainLooper()))

                        session.capture(rawReq.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                                lastRes = result
                            }
                        }, Handler(Looper.getMainLooper()))
                    }
                }

                // 2. Captura en Máxima Resolución desde el ImageReader JPEG (100% calidad ISP, Cero Deformación)
                var highResCleanBitmap: Bitmap? = null
                val cam = cameraDevice
                val session = captureSession
                val jReader = jpegReader

                if (cam != null && session != null && jReader != null) {
                    val rawJpegBytes: ByteArray? = withTimeoutOrNull(2800L) {
                        suspendCancellableCoroutine { cont ->
                            jReader.setOnImageAvailableListener({ reader ->
                                val img = reader.acquireLatestImage()
                                if (img != null) {
                                    val buffer = img.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    img.close()
                                    if (cont.isActive) cont.resume(bytes)
                                } else if (cont.isActive) {
                                    cont.resume(null)
                                }
                            }, Handler(Looper.getMainLooper()))

                            val stillReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(jReader.surface)
                                set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)

                                val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
                                } else if (activeArray != null && currentZoom > 1.0f) {
                                    val cropW = (activeArray.width() / currentZoom).toInt()
                                    val cropH = (activeArray.height() / currentZoom).toInt()
                                    val cropX = (activeArray.width() - cropW) / 2
                                    val cropY = (activeArray.height() - cropH) / 2
                                    set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
                                }

                                if (isManualMode && lens.supportsManualSensor) {
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs)
                                } else {
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureEvSteps)
                                }
                                if (flashMode == FlashMode.ON || flashMode == FlashMode.DISPOSABLE) {
                                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                                }
                            }
                            session.capture(stillReq.build(), null, Handler(Looper.getMainLooper()))
                        }
                    }

                    if (rawJpegBytes != null) {
                        val decoded = try {
                            BitmapFactory.decodeByteArray(rawJpegBytes, 0, rawJpegBytes.size)
                        } catch (_: OutOfMemoryError) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            BitmapFactory.decodeByteArray(rawJpegBytes, 0, rawJpegBytes.size, opts)
                        }
                        if (decoded != null) {
                            // Corregir rotación física del sensor (Frontal 270° + espejo horizontal / Trasera 90°)
                            val m = Matrix()
                            val rotDeg = if (isDeviceLandscape) {
                                if (uiRotationDeg < 0f) (lens.sensorOrientation + 90) % 360
                                else (lens.sensorOrientation + 270) % 360
                            } else {
                                lens.sensorOrientation
                            }
                            m.postRotate(rotDeg.toFloat())
                            if (lens.isFront) {
                                // Espejo horizontal en selfies para coincidir con el visor sin deformar proporción
                                m.postScale(-1f, 1f)
                            }
                            highResCleanBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                            if (highResCleanBitmap !== decoded) decoded.recycle()
                        }
                    }
                }

                if (highResCleanBitmap != null) {
                    val cleanBase = AnalogImageProcessor.cropToAspectRatio(highResCleanBitmap, aspectMode)
                    val shutterStr = "1/${(1_000_000_000L / selectedShutterNs.coerceAtLeast(1)).coerceAtLeast(1)}s"
                    val stamp = "${selectedPreset.brand} ${selectedPreset.name}  •  ISO $selectedIso  •  $shutterStr  •  $dateLabel"

                    val finalBmp = AnalogImageProcessor.processImage(
                        source = cleanBase,
                        aspectMode = aspectMode,
                        preset = selectedPreset,
                        customLut = customLut,
                        vfx = vfxConfig,
                        light = sceneLight,
                        exposureBias = exposureEvSteps * lens.evStep,
                        disposableFlashActive = (flashMode == FlashMode.DISPOSABLE),
                        isFrontSensor = lens.isFront,
                        sensorAreaMm2 = lens.physicalAreaMm2,
                        metadataStamp = stamp
                    )

                    val isPng = (captureFormat == CaptureFormat.PNG)
                    val ext = if (isPng) "png" else "jpg"
                    val mime = if (isPng) "image/png" else "image/jpeg"
                    val dispName = "ANALOG_${timestamp}.$ext"

                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, dispName)
                        put(MediaStore.Images.Media.MIME_TYPE, mime)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                        }
                    }

                    val savedUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (savedUri != null) {
                        context.contentResolver.openOutputStream(savedUri)?.use { out ->
                            val fmt = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                            finalBmp.compress(fmt, 100, out)
                        }

                        // Guardar la metadata EXIF y el respaldo original limpio para re-edición futura
                        val recipe = PhotoEditRecipe(
                            presetId = selectedPreset.id,
                            ev = exposureEvSteps * lens.evStep,
                            temperature = vfxConfig.temperature,
                            tint = vfxConfig.tint,
                            grain = vfxConfig.grainIntensity,
                            noise = vfxConfig.noiseIntensity,
                            flare = vfxConfig.sunFlareIntensity,
                            leak = vfxConfig.lightLeakIntensity,
                            anamorphic = vfxConfig.anamorphicIntensity,
                            halation = vfxConfig.halationIntensity,
                            polaroid = vfxConfig.polaroidFrame,
                            aspectName = aspectMode.name
                        )
                        MediaStoreRepository.saveOriginalBackupAndExifRecipe(
                            context = context,
                            savedUri = savedUri,
                            displayName = dispName,
                            cleanOriginalBitmap = cleanBase,
                            recipe = recipe
                        )
                    }

                    finalBmp.recycle()
                    if (cleanBase !== highResCleanBitmap) cleanBase.recycle()
                    highResCleanBitmap.recycle()
                }

                refreshGalleryFromMediaStore()
                withContext(Dispatchers.Main) {
                    isCapturingPhoto = false
                    Toast.makeText(context, "Guardada en máxima calidad (${lens.maxResolutionMp})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCapturingPhoto = false
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showGalleryScreen) {
        DarkroomGalleryScreen(
            persistedMedia = persistedMedia,
            onRefreshGallery = { refreshGalleryFromMediaStore() },
            onClose = { showGalleryScreen = false }
        )
        return
    }

    // ========================================================================
    // PLANTILLA UNIFICADA FIJA: TODO SE MANTIENE TAL CUAL Y SOLO ROTAN ICONOS
    // ========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .onSizeChanged { viewBoxSize = it }
    ) {
        val viewfinderModifier = when (aspectMode) {
            AspectRatioMode.RATIO_16_9 -> Modifier.fillMaxWidth().aspectRatio(9f / 16f)
            AspectRatioMode.RATIO_4_3 -> Modifier.fillMaxWidth().aspectRatio(3f / 4f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 104.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = viewfinderModifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            currentZoom = (currentZoom * zoom).coerceIn(1.0f, activeLens.maxZoom)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            focusNormX = (offset.x / size.width.toFloat()).coerceIn(0.08f, 0.90f)
                            focusNormY = (offset.y / size.height.toFloat()).coerceIn(0.08f, 0.90f)
                            showFocusEvBar = true
                            lastFocusTouchMillis = System.currentTimeMillis()
                            val cam = cameraDevice
                            val sess = captureSession
                            val tex = textureViewRef.value
                            if (cam != null && sess != null && tex != null) {
                                applyRepeatingPreviewParameters(cam, sess, tex, offset)
                            }
                        }
                    }
            ) {
                // VISOR DE CÁMARA EN TIEMPO REAL
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef.value = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    openSelectedCamera(activeLens, this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    applyNonDistortingTransform(this@apply, w, h, previewBufferSize, activeLens)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // CAPA EN VIVO DE VFX INTERACTIVOS, DETECCIÓN FACIAL Y RETÍCULA DE ENFOQUE
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. VFX Ópticos Interactivos en Tiempo Real
                    drawIntoCanvas { composeCanvas ->
                        AnalogImageProcessor.drawInteractiveOpticalVfx(
                            canvas = composeCanvas.nativeCanvas,
                            width = w,
                            height = h,
                            vfx = vfxConfig,
                            light = sceneLight
                        )
                    }

                    // 2. Marco Polaroid en vivo si está activado
                    if (vfxConfig.polaroidFrame) {
                        val bSide = w * 0.045f
                        val bBot = h * 0.12f
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, bSide))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, h - bBot), size = androidx.compose.ui.geometry.Size(w, bBot))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(bSide, h))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(w - bSide, 0f), size = androidx.compose.ui.geometry.Size(bSide, h))
                    }

                    // 3. Guías de Esquinas del Visor
                    val pad = 18.dp.toPx()
                    val arm = 54.dp.toPx()
                    val strokeW = 1.4.dp.toPx()
                    val bracketCol = Color.White.copy(alpha = 0.85f)

                    drawLine(bracketCol, Offset(pad, pad), Offset(pad + arm * 1.4f, pad), strokeW)
                    drawLine(bracketCol, Offset(pad, pad), Offset(pad, pad + arm), strokeW)

                    drawLine(bracketCol, Offset(w - pad - arm * 1.4f, pad), Offset(w - pad, pad), strokeW)
                    drawLine(bracketCol, Offset(w - pad, pad), Offset(w - pad, pad + arm), strokeW)

                    drawLine(bracketCol, Offset(pad, h - pad - arm), Offset(pad, h - pad), strokeW)
                    drawLine(bracketCol, Offset(pad, h - pad), Offset(pad + arm * 1.4f, h - pad), strokeW)

                    // 4. Recuadros de Detección Facial en tiempo real
                    val faceCol = Color(0xFFD4AF37).copy(alpha = 0.85f)
                    for (faceRect in detectedFacesNorm) {
                        val left = faceRect.left * w
                        val top = faceRect.top * h
                        val right = faceRect.right * w
                        val bottom = faceRect.bottom * h
                        drawRoundRect(
                            color = faceCol,
                            topLeft = Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }

                    // 5. Retícula de Enfoque [ + ] donde toca el usuario
                    val fx = focusNormX * w
                    val fy = focusNormY * h
                    val boxR = 42.dp.toPx()
                    val cornerLen = 16.dp.toPx()

                    drawLine(bracketCol, Offset(fx - boxR, fy - boxR), Offset(fx - boxR + cornerLen, fy - boxR), strokeW)
                    drawLine(bracketCol, Offset(fx - boxR, fy - boxR), Offset(fx - boxR, fy - boxR + cornerLen), strokeW)

                    drawLine(bracketCol, Offset(fx + boxR - cornerLen, fy - boxR), Offset(fx + boxR, fy - boxR), strokeW)
                    drawLine(bracketCol, Offset(fx + boxR, fy - boxR), Offset(fx + boxR, fy - boxR + cornerLen), strokeW)

                    drawLine(bracketCol, Offset(fx - boxR, fy + boxR - cornerLen), Offset(fx - boxR, fy + boxR), strokeW)
                    drawLine(bracketCol, Offset(fx - boxR, fy + boxR), Offset(fx - boxR + cornerLen, fy + boxR), strokeW)

                    drawLine(bracketCol, Offset(fx + boxR - cornerLen, fy + boxR), Offset(fx + boxR, fy + boxR), strokeW)
                    drawLine(bracketCol, Offset(fx + boxR, fy + boxR - cornerLen), Offset(fx + boxR, fy + boxR), strokeW)

                    val cross = 7.dp.toPx()
                    drawLine(bracketCol, Offset(fx - cross, fy), Offset(fx + cross, fy), strokeW)
                    drawLine(bracketCol, Offset(fx, fy - cross), Offset(fx, fy + cross), strokeW)
                }

                // ============================================================
                // MINI BARRA DE EXPOSICIÓN A LA DERECHA DEL ÁREA DE ENFOQUE
                // ============================================================
                if (showFocusEvBar) {
                    val evRange = activeLens.evRange
                    val normEv = ((exposureEvSteps - evRange.lower).toFloat() /
                        (evRange.upper - evRange.lower).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val barX = ((focusNormX * maxWidth.value) + 52f).coerceIn(24f, maxWidth.value - 42f).dp
                        val barY = ((focusNormY * maxHeight.value) - 58f).coerceIn(48f, maxHeight.value - 150f).dp

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .offset(x = barX, y = barY)
                                .width(28.dp)
                                .height(116.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                                .padding(vertical = 6.dp)
                                .pointerInput(evRange) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        lastFocusTouchMillis = System.currentTimeMillis()
                                        val delta = (-dragAmount / size.height.toFloat()) * (evRange.upper - evRange.lower)
                                        exposureEvSteps = (exposureEvSteps + delta.roundToInt())
                                            .coerceIn(evRange.lower, evRange.upper)
                                    }
                                }
                        ) {
                            Icon(
                                Icons.Default.WbSunny,
                                contentDescription = "Exposición",
                                tint = Color(0xFFD4AF37),
                                modifier = Modifier.size(14.dp).rotate(animatedIconRotation)
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .weight(1f)
                                    .background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .fillMaxHeight(normEv)
                                        .background(Color(0xFFD4AF37), RoundedCornerShape(2.dp))
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${if (exposureEvSteps >= 0) "+" else ""}$exposureEvSteps",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // ============================================================
                // HUD SUPERIOR IZQUIERDO (SIN ICONO DE BATERÍA): AUTO/PRO + 16:9 / 4:3
                // ============================================================
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 28.dp, top = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .rotate(animatedIconRotation)
                            .clickable { isManualMode = !isManualMode }
                    ) {
                        Text(
                            text = if (isManualMode) "PRO" else "AUTO",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .rotate(animatedIconRotation)
                            .clickable {
                                aspectMode = if (aspectMode == AspectRatioMode.RATIO_16_9) {
                                    AspectRatioMode.RATIO_4_3
                                } else {
                                    AspectRatioMode.RATIO_16_9
                                }
                            }
                    ) {
                        Text(
                            text = aspectMode.label,
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

                    if (currentZoom > 1.05f) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Color(0xFFD4AF37)),
                            modifier = Modifier.rotate(animatedIconRotation)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.1fx", currentZoom),
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // ============================================================
                // TIMECODE SUPERIOR CENTRAL Y ● REC (SOLO APARECEN AL GRABAR)
                // ============================================================
                AnimatedVisibility(
                    visible = isRecordingVideo,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                ) {
                    val hrs = recordingSeconds / 3600
                    val mins = (recordingSeconds % 3600) / 60
                    val secs = recordingSeconds % 60
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Color(0xFFE53935)),
                        modifier = Modifier.rotate(animatedIconRotation)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isRecordingVideo,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 28.dp, top = 26.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.rotate(animatedIconRotation)
                    ) {
                        Box(modifier = Modifier.size(10.dp).background(Color(0xFFFF1744), CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text("REC", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Alerta de Baja Luz
                AnimatedVisibility(
                    visible = sceneLight.isLowLight && flashMode == FlashMode.OFF && !lowLightPromptDismissed,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp)
                ) {
                    Surface(
                        color = Color(0xDD1A1D24),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFFD4AF37))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(15.dp))
                            Text("Baja luz", color = Color.White, fontSize = 11.sp)
                            Text(
                                text = "ACTIVAR FLASH",
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { flashMode = FlashMode.TORCH }
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cerrar",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp).clickable { lowLightPromptDismissed = true }
                            )
                        }
                    }
                }

                // ============================================================
                // DOCK DE 4 BOTONES: FILMS | VFX | PRO | LENS (SOLO ROTAN ICONOS)
                // ============================================================
                Surface(
                    color = Color(0xAA101216),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 18.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DockIconButton(
                            icon = Icons.Default.Theaters,
                            label = "FILMS",
                            selected = activeDockTab == DockTab.FILMS,
                            rotationDeg = animatedIconRotation,
                            onClick = { activeDockTab = if (activeDockTab == DockTab.FILMS) DockTab.NONE else DockTab.FILMS }
                        )
                        DockIconButton(
                            icon = Icons.Default.AutoFixHigh,
                            label = "VFX",
                            selected = activeDockTab == DockTab.VFX,
                            rotationDeg = animatedIconRotation,
                            onClick = { activeDockTab = if (activeDockTab == DockTab.VFX) DockTab.NONE else DockTab.VFX }
                        )
                        DockIconButton(
                            icon = Icons.Default.Camera,
                            label = "PRO",
                            selected = activeDockTab == DockTab.PRO,
                            rotationDeg = animatedIconRotation,
                            onClick = { activeDockTab = if (activeDockTab == DockTab.PRO) DockTab.NONE else DockTab.PRO }
                        )
                        DockIconButton(
                            icon = Icons.Default.CenterFocusStrong,
                            label = "LENS",
                            selected = activeDockTab == DockTab.LENS,
                            rotationDeg = animatedIconRotation,
                            onClick = { activeDockTab = if (activeDockTab == DockTab.LENS) DockTab.NONE else DockTab.LENS }
                        )
                    }
                }

                // ============================================================
                // PANELES DESPLEGABLES (FILMS, VFX, PRO, LENS)
                // ============================================================
                AnimatedVisibility(
                    visible = activeDockTab != DockTab.NONE,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp, start = 12.dp, end = 12.dp)
                ) {
                    Surface(
                        color = Color(0xEE12151B),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (activeDockTab) {
                                        DockTab.FILMS -> "FILMS EN TIEMPO REAL (INCLUYE OPCIÓN SIN LUT)"
                                        DockTab.VFX -> "VFX INTERACTIVOS (SOL, ALTAS LUCES, GRANO SUTIL)"
                                        DockTab.PRO -> "MANUAL PRO, TEMPERATURA, TINTE & FLASH"
                                        DockTab.LENS -> "SELECCIÓN DE LENTE (${availableLenses.size} DETECTADOS)"
                                        else -> ""
                                    },
                                    color = Color(0xFFD4AF37),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                IconButton(
                                    onClick = { activeDockTab = DockTab.NONE },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.LightGray)
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            when (activeDockTab) {
                                DockTab.FILMS -> {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(BUILT_IN_PRESETS) { preset ->
                                            val sampleBmp = remember(preset.id) {
                                                FilmThumbnailGenerator.getRepresentativeThumbnail(preset)
                                            }
                                            val isSelected = selectedPreset.id == preset.id
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier
                                                    .width(96.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) Color(0xFFD4AF37) else Color(0xFF1E222B))
                                                    .clickable {
                                                        selectedPreset = preset
                                                        if (preset.isNoLut) customLut = null
                                                        vfxConfig = vfxConfig.copy(
                                                            grainIntensity = preset.defaultGrain,
                                                            noiseIntensity = preset.defaultNoise
                                                        )
                                                    }
                                                    .padding(5.dp)
                                            ) {
                                                Image(
                                                    bitmap = sampleBmp.asImageBitmap(),
                                                    contentDescription = preset.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(54.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = preset.brand,
                                                    color = if (isSelected) Color.Black else Color.Gray,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = preset.name,
                                                    color = if (isSelected) Color.Black else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(BUILT_IN_ASSET_LUTS) { assetLut ->
                                            AssistChip(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        val parsed = CubeLutParser.parseAsset(context, assetLut)
                                                        withContext(Dispatchers.Main) { customLut = parsed }
                                                    }
                                                },
                                                label = { Text(assetLut.removeSuffix(".cube").replace("_", " "), fontSize = 10.sp) }
                                            )
                                        }
                                        item {
                                            AssistChip(
                                                onClick = { lutPicker.launch("*/*") },
                                                label = { Text("+ Importar .CUBE", fontSize = 10.sp) }
                                            )
                                        }
                                    }
                                }

                                DockTab.VFX -> {
                                    Column(
                                        modifier = Modifier
                                            .heightIn(max = 200.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text("Flare Solar Interactivo: ${(vfxConfig.sunFlareIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.sunFlareIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(sunFlareIntensity = it) }
                                        )

                                        Text("Light Leaks Direccionales: ${(vfxConfig.lightLeakIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.lightLeakIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(lightLeakIntensity = it) }
                                        )

                                        Text("Light Leaks Anamórficos (Altas Luces): ${(vfxConfig.anamorphicIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.anamorphicIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(anamorphicIntensity = it) }
                                        )

                                        Text("Halation en Altas Luces: ${(vfxConfig.halationIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.halationIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(halationIntensity = it) }
                                        )

                                        Text("Grano Sutil Controlado: ${(vfxConfig.grainIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.grainIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(grainIntensity = it.coerceIn(0f, 0.40f)) }
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(
                                                selected = vfxConfig.polaroidFrame,
                                                onClick = { vfxConfig = vfxConfig.copy(polaroidFrame = !vfxConfig.polaroidFrame) },
                                                label = { Text("Marco Polaroid", fontSize = 11.sp) }
                                            )
                                            FilterChip(
                                                selected = vfxConfig.anamorphicWarmColor,
                                                onClick = { vfxConfig = vfxConfig.copy(anamorphicWarmColor = !vfxConfig.anamorphicWarmColor) },
                                                label = { Text(if (vfxConfig.anamorphicWarmColor) "Anamórfico Ámbar" else "Anamórfico Cian", fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }

                                DockTab.PRO -> {
                                    Column(
                                        modifier = Modifier
                                            .heightIn(max = 210.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            FilterChip(
                                                selected = !isManualMode,
                                                onClick = { isManualMode = false },
                                                label = { Text("AUTO + EV", fontSize = 11.sp) }
                                            )
                                            FilterChip(
                                                selected = isManualMode,
                                                onClick = { isManualMode = true },
                                                label = { Text("MANUAL PRO", fontSize = 11.sp) }
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    val vals = CaptureFormat.values()
                                                    captureFormat = vals[(captureFormat.ordinal + 1) % vals.size]
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(captureFormat.label, color = Color(0xFFD4AF37), fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { showHardwareModal = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text("SENSOR MAX", color = Color.White, fontSize = 10.sp)
                                            }
                                        }

                                        if (isManualMode) {
                                            val isoR = activeLens.isoRange
                                            Text("ISO: $selectedIso", color = Color.LightGray, fontSize = 11.sp)
                                            Slider(
                                                value = selectedIso.toFloat().coerceIn(isoR.lower.toFloat(), isoR.upper.toFloat()),
                                                onValueChange = { selectedIso = it.toInt() },
                                                valueRange = isoR.lower.toFloat()..isoR.upper.toFloat().coerceAtMost(6400f)
                                            )

                                            val shutterDenom = (1_000_000_000L / selectedShutterNs.coerceAtLeast(1)).coerceAtLeast(1)
                                            Text("Obturación: 1/${shutterDenom}s", color = Color.LightGray, fontSize = 11.sp)
                                            Slider(
                                                value = selectedShutterNs.toFloat(),
                                                onValueChange = { selectedShutterNs = it.toLong() },
                                                valueRange = 200_000f..100_000_000f
                                            )
                                        }

                                        Text("Temperatura de Color (Suave): ${String.format(Locale.US, "%+.2f", vfxConfig.temperature)}", color = Color.LightGray, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.temperature,
                                            onValueChange = { vfxConfig = vfxConfig.copy(temperature = it) },
                                            valueRange = -1.0f..1.0f
                                        )

                                        Text("Tinte Verde/Magenta (Suave): ${String.format(Locale.US, "%+.2f", vfxConfig.tint)}", color = Color.LightGray, fontSize = 11.sp)
                                        Slider(
                                            value = vfxConfig.tint,
                                            onValueChange = { vfxConfig = vfxConfig.copy(tint = it) },
                                            valueRange = -1.0f..1.0f
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Flash: ${flashMode.label}", color = Color(0xFFD4AF37), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            TextButton(
                                                onClick = {
                                                    val modes = FlashMode.values()
                                                    flashMode = modes[(flashMode.ordinal + 1) % modes.size]
                                                }
                                            ) {
                                                Text("CAMBIAR MODO FLASH", color = Color.White, fontSize = 11.sp)
                                            }
                                        }

                                        Text("Intensidad del Flash: ${(flashStrengthPercent * 100).toInt()}%", color = Color.LightGray, fontSize = 11.sp)
                                        Slider(
                                            value = flashStrengthPercent,
                                            onValueChange = { flashStrengthPercent = it.coerceIn(0.1f, 1.0f) },
                                            valueRange = 0.1f..1.0f
                                        )
                                    }
                                }

                                DockTab.LENS -> {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        items(availableLenses) { lens ->
                                            val isCurrent = lens.cameraId == activeLens.cameraId
                                            Surface(
                                                color = if (isCurrent) Color(0xFFD4AF37) else Color(0xFF1F232C),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.clickable {
                                                    activeLens = lens
                                                    currentZoom = 1.0f
                                                    textureViewRef.value?.let { openSelectedCamera(lens, it) }
                                                }
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally
                                                ) {
                                                    Text(
                                                        text = lens.badge,
                                                        color = if (isCurrent) Color.Black else Color(0xFFD4AF37),
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.ExtraBold
                                                    )
                                                    Text(
                                                        text = lens.label,
                                                        color = if (isCurrent) Color.Black else Color.White,
                                                        fontSize = 10.sp
                                                    )
                                                    Text(
                                                        text = "f/${lens.aperture} • ${lens.maxResolutionMp}",
                                                        color = if (isCurrent) Color.DarkGray else Color.Gray,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                DockTab.NONE -> {}
                            }
                        }
                    }
                }
            }
        }

        // ====================================================================
        // BARRA DE DISPARO FIJA (SE MANTIENE TAL CUAL Y SOLO ROTAN LOS ICONOS)
        // ====================================================================
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(104.dp)
                .background(Color(0xFF0A0B0E))
                .padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryThumbnailButton(
                bitmap = latestThumbnailBmp,
                rotationDeg = animatedIconRotation,
                onClick = { showGalleryScreen = true }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MetallicShutterButton(
                    isCapturing = isCapturingPhoto,
                    onClick = { capturePhoto() }
                )

                VideoRecordSideButton(
                    isRecording = isRecordingVideo,
                    onClick = { toggleVideoRecording() }
                )
            }

            CameraSwitchCircleButton(
                rotationDeg = animatedIconRotation,
                onClick = {
                    val targetFront = !activeLens.isFront
                    val nextLens = availableLenses.firstOrNull { it.isFront == targetFront } ?: availableLenses.first()
                    activeLens = nextLens
                    currentZoom = 1.0f
                    textureViewRef.value?.let { openSelectedCamera(nextLens, it) }
                }
            )
        }
    }

    if (showHardwareModal) {
        AlertDialog(
            onDismissRequest = { showHardwareModal = false },
            containerColor = Color(0xFF14161D),
            title = {
                Text(
                    "Capacidad y Calibración del Sensor",
                    color = Color(0xFFD4AF37),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Lente Activo: ${activeLens.label} (ID ${activeLens.cameraId})", color = Color.White, fontSize = 12.sp)
                    Text("• Nivel Hardware ISP: ${activeLens.hardwareLevel}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución Fotográfica Nativa: ${activeLens.maxResolutionMp}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución de Video Nativa: ${activeLens.maxVideoSize.width}x${activeLens.maxVideoSize.height}", color = Color.White, fontSize = 12.sp)
                    Text("• Tamaño Físico del Sensor: ${activeLens.physicalSizeMm}", color = Color.White, fontSize = 12.sp)
                    Text(
                        "• Adaptación de Color: ${if (activeLens.isFront) "Modo Frontal Suave (Protección de Piel y Bits)" else "Modo Sensor Principal Completo"}",
                        color = Color(0xFF81C784),
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHardwareModal = false }) {
                    Text("CERRAR", color = Color(0xFFD4AF37))
                }
            }
        )
    }
}

@Composable
fun DockIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    rotationDeg: Float = 0f,
    onClick: () -> Unit
) {
    val tintColor = if (selected) Color(0xFFD4AF37) else Color.White
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .rotate(rotationDeg)
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tintColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = tintColor,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
fun MetallicShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clickable(enabled = !isCapturing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val ringStroke = 6.dp.toPx()

            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFE0E0E0),
                        Color(0xFF757575),
                        Color(0xFFF5F5F5),
                        Color(0xFF616161),
                        Color(0xFFE0E0E0)
                    )
                ),
                radius = radius - ringStroke / 2f,
                style = Stroke(width = ringStroke)
            )

            drawArc(
                color = Color(0xFFE53935),
                startAngle = -15f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = ringStroke * 0.7f)
            )
            drawArc(
                color = Color(0xFFE53935),
                startAngle = 75f,
                sweepAngle = 30f,
                useCenter = false,
                style = Stroke(width = ringStroke * 0.7f)
            )
        }

        Box(
            modifier = Modifier
                .size(58.dp)
                .background(if (isCapturing) Color.LightGray else Color.White, CircleShape)
        )
    }
}

@Composable
fun VideoRecordSideButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
            .padding(5.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (isRecording) 16.dp else 22.dp)
                .background(
                    Color(0xFFE53935),
                    if (isRecording) RoundedCornerShape(4.dp) else CircleShape
                )
        )
    }
}

@Composable
fun GalleryThumbnailButton(
    bitmap: Bitmap?,
    rotationDeg: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .rotate(rotationDeg)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1C22))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Última foto en galería",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = "Galería",
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun CameraSwitchCircleButton(
    rotationDeg: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .rotate(rotationDeg)
            .background(Color(0xFF16181E), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Cameraswitch,
            contentDescription = "Cambiar cámara",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}
