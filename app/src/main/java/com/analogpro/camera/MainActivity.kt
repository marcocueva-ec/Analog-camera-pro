package com.analogpro.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
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
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.math.*
import kotlin.random.Random

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

    // 1. Detección de Orientación Física sin reiniciar la cámara ni deformar (Squeeze) el visor
    var isDeviceLandscape by remember { mutableStateOf(false) }
    var uiRotationDeg by remember { mutableStateOf(0f) }
    val animatedUiRotation by animateFloatAsState(targetValue = uiRotationDeg, label = "uiRot")

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

    // 2. Nivel de batería real para el HUD
    val batteryLevel = remember {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 80) ?: 80
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        ((level.toFloat() / scale.toFloat()) * 100f).toInt().coerceIn(1, 100)
    }

    // 3. Inventario de todos los Lentes / Cámaras del teléfono (para el menú LENS)
    val availableLenses = remember {
        val list = ArrayList<LensCameraInfo>()
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                val isFront = (facing == CameraCharacteristics.LENS_FACING_FRONT)
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
                    isFront -> "Frontal Selfie (${String.format(Locale.US, "%.1fmm", focal)})"
                    focal < 2.8f -> "Ultra Gran Angular (${String.format(Locale.US, "%.1fmm", focal)})"
                    focal > 6.0f -> "Teleobjetivo (${String.format(Locale.US, "%.1fmm", focal)})"
                    else -> "Gran Angular Principal (${String.format(Locale.US, "%.1fmm", focal)})"
                }

                list.add(
                    LensCameraInfo(
                        cameraId = id,
                        label = label,
                        badge = badge,
                        isFront = isFront,
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

    // 4. Estado de Formato (16:9, 4:3, OpenGate), Film, LUT, VFX, PRO y Enfoque
    var aspectMode by remember { mutableStateOf(AspectRatioMode.RATIO_16_9) }
    var activeDockTab by remember { mutableStateOf(DockTab.NONE) }

    var selectedPreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) } // Fujifilm Classic Chrome
    var customLut by remember { mutableStateOf<CubeLut3D?>(null) }
    var vfxConfig by remember {
        mutableStateOf(
            VfxConfig(
                sunFlareIntensity = 0.0f,
                lightLeakIntensity = 0.0f,
                anamorphicIntensity = 0.0f,
                halationIntensity = 0.0f,
                grainIntensity = 0.22f,
                noiseIntensity = 0.08f,
                sceneBlurBokeh = 0.0f,
                polaroidFrame = false
            )
        )
    }

    // Controles PRO (ISO, Obturación, EV, Enfoque Manual Macro, Flash e Intensidad de Flash)
    var isManualMode by remember { mutableStateOf(false) }
    var selectedIso by remember { mutableStateOf(100) }
    var selectedShutterNs by remember { mutableStateOf(33_333_333L) } // 1/30s
    var exposureEvSteps by remember { mutableStateOf(0) }
    var manualFocusDiopter by remember { mutableStateOf(0f) } // 0 = infinito, max = macro cercano
    var captureFormat by remember { mutableStateOf(CaptureFormat.JPG) }
    var flashMode by remember { mutableStateOf(FlashMode.OFF) }
    var flashStrengthPercent by remember { mutableStateOf(0.8f) } // 10% a 100%

    // Zoom con gestos (Pinch-to-Zoom) y Enfoque táctil (Tap-to-Focus)
    var currentZoom by remember { mutableStateOf(1.0f) }
    var focusNormX by remember { mutableStateOf(0.5f) }
    var focusNormY by remember { mutableStateOf(0.5f) }
    var showFocusSliderToast by remember { mutableStateOf(false) }

    // Análisis de luz en tiempo real (posición del sol, altas luces anamórficas y baja luz)
    var sceneLight by remember { mutableStateOf(SceneLightAnalysis()) }
    var lowLightPromptDismissed by remember { mutableStateOf(false) }

    // Grabación de Video en máxima calidad
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var mediaRecorderRef by remember { mutableStateOf<MediaRecorder?>(null) }
    var pendingVideoPfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }

    // Galería Persistente (MediaStore) + Miniatura real en la esquina
    val persistedPhotos = remember { mutableStateListOf<PersistedPhotoItem>() }
    var latestThumbnailBmp by remember { mutableStateOf<Bitmap?>(null) }
    var showGalleryScreen by remember { mutableStateOf(false) }
    var showHardwareModal by remember { mutableStateOf(false) }
    var isCapturingPhoto by remember { mutableStateOf(false) }

    fun refreshGalleryFromMediaStore() {
        scope.launch(Dispatchers.IO) {
            val items = MediaStoreRepository.loadPersistedPhotos(context)
            val thumb = items.firstOrNull()?.let { MediaStoreRepository.loadThumbnailBitmap(context, it.uri, 200) }
            withContext(Dispatchers.Main) {
                persistedPhotos.clear()
                persistedPhotos.addAll(items)
                latestThumbnailBmp = thumb
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshGalleryFromMediaStore()
    }

    // Temporizador cuando está grabando video
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

    // Importador de LUT .cube personalizado
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

    // Referencias Camera2 y TextureView
    var cameraDevice by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var jpegReader by remember { mutableStateOf<ImageReader?>(null) }
    var rawReader by remember { mutableStateOf<ImageReader?>(null) }
    var currentCharacteristics by remember { mutableStateOf<CameraCharacteristics?>(null) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    var viewBoxSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    var previewBufferSize by remember { mutableStateOf(Size(1920, 1080)) }

    /**
     * Aplica una matriz de transformación exacta sobre el TextureView para que NUNCA
     * deforme (squeeze) la imagen sin importar el formato (16:9, 4:3, OpenGate) ni el giro.
     */
    fun applyNonDistortingTransform(texView: TextureView, viewW: Int, viewH: Int, bufSize: Size) {
        if (viewW <= 0 || viewH <= 0) return
        // En orientación base vertical del sensor, el ancho efectivo del stream es bufSize.height y el alto es bufSize.width
        val bufferWidth = bufSize.height.toFloat()
        val bufferHeight = bufSize.width.toFloat()

        val viewRatio = viewW.toFloat() / viewH.toFloat()
        val bufferRatio = bufferWidth / bufferHeight

        val scaleX: Float
        val scaleY: Float
        if (bufferRatio > viewRatio) {
            // El sensor es más ancho que el contenedor -> igualar altura y escalar ancho proporcionalmente
            scaleY = 1f
            scaleX = bufferRatio / viewRatio
        } else {
            // El sensor es más alto que el contenedor -> igualar ancho y escalar altura proporcionalmente
            scaleX = 1f
            scaleY = viewRatio / bufferRatio
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        texView.setTransform(matrix)
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

                // 1. Zoom Digital sin pérdida de proporción
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

                // 2. Enfoque Táctil (Tap-to-Focus) o Enfoque Manual Macro (Desenfoque de objetos cercanos)
                if (manualFocusDiopter > 0.05f && activeLens.minFocusDistance > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDiopter.coerceIn(0f, activeLens.minFocusDistance))
                } else {
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
                }

                // 3. Exposición Manual (PRO) vs Automática con Compensación EV
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
                    // Modulación de exposición según la intensidad de flash configurada en PRO
                    val flashEvAdjust = if (flashMode != FlashMode.OFF && flashStrengthPercent < 0.7f) {
                        -((1f - flashStrengthPercent) * 3f).toInt()
                    } else 0
                    val totalEv = (exposureEvSteps + flashEvAdjust).coerceIn(activeLens.evRange.lower, activeLens.evRange.upper)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, totalEv)
                }

                // 4. Modo Linterna / Torch con control de intensidad (Android 13+)
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
            session.setRepeatingRequest(req.build(), null, null)
        } catch (_: Exception) {}
    }

    fun openSelectedCamera(lens: LensCameraInfo, texView: TextureView) {
        try {
            cameraDevice?.close()
            val chars = cameraManager.getCameraCharacteristics(lens.cameraId)
            currentCharacteristics = chars

            val map: StreamConfigurationMap? = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val allPreviewSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: arrayOf(Size(1920, 1080))
            // Elegir tamaño de previsualización óptimo acorde al AspectRatioMode sin deformar
            val chosenPreview = allPreviewSizes
                .filter { it.width <= 1920 && it.height <= 1920 }
                .minByOrNull { size ->
                    val r = size.height.toFloat() / size.width.toFloat()
                    val targetR = when (aspectMode) {
                        AspectRatioMode.RATIO_16_9 -> 9f / 16f
                        AspectRatioMode.RATIO_4_3 -> 3f / 4f
                        AspectRatioMode.OPEN_GATE -> lens.maxJpegSize.height.toFloat() / lens.maxJpegSize.width.toFloat()
                    }
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
                    applyNonDistortingTransform(texView, texView.width, texView.height, chosenPreview)

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

    // Reconfigurar parámetros en tiempo real al cambiar ISO, Obturación, Zoom, Flash, Enfoque, etc.
    LaunchedEffect(
        isManualMode, selectedIso, selectedShutterNs, exposureEvSteps,
        manualFocusDiopter, flashMode, flashStrengthPercent, currentZoom,
        focusNormX, focusNormY, captureSession
    ) {
        val cam = cameraDevice ?: return@LaunchedEffect
        val session = captureSession ?: return@LaunchedEffect
        val tex = textureViewRef.value ?: return@LaunchedEffect
        applyRepeatingPreviewParameters(cam, session, tex)
    }

    // Reajustar proporción sin deformar cuando el usuario cambia entre 16:9, 4:3 y Open Gate
    LaunchedEffect(aspectMode, viewBoxSize, previewBufferSize) {
        val tex = textureViewRef.value ?: return@LaunchedEffect
        applyNonDistortingTransform(tex, tex.width, tex.height, previewBufferSize)
    }

    // 5. Analizador en Tiempo Real (30fps) de la posición del Sol, Altas Luces Anamórficas y Baja Luz
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(110L)
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

    // 6. Función para Iniciar / Detener Grabación de Video en Máxima Calidad
    fun toggleVideoRecording() {
        val cam = cameraDevice ?: return
        val texView = textureViewRef.value ?: return
        val st = texView.surfaceTexture ?: return

        if (isRecordingVideo) {
            // Detener grabación
            try {
                mediaRecorderRef?.stop()
            } catch (_: Exception) {}
            try {
                mediaRecorderRef?.release()
            } catch (_: Exception) {}
            mediaRecorderRef = null
            try {
                pendingVideoPfd?.close()
            } catch (_: Exception) {}
            pendingVideoPfd = null
            isRecordingVideo = false
            Toast.makeText(context, "Video guardado en Galería (Movies/AnalogPro)", Toast.LENGTH_SHORT).show()
            openSelectedCamera(activeLens, texView)
        } else {
            // Iniciar grabación en máxima calidad (4K / 1080p High Bitrate)
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
                    setVideoEncodingBitRate(35_000_000) // 35 Mbps calidad cine
                    setVideoFrameRate(30)
                    setVideoSize(vidSize.width, vidSize.height)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(192_000)
                    setAudioSamplingRate(48_000)
                    setOrientationHint(if (isDeviceLandscape) 0 else 90)
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
                            applyRepeatingPreviewParameters(cam, session, texView)
                            try {
                                recorder.start()
                            } catch (e: Exception) {
                                isRecordingVideo = false
                            }
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(context, "Error configurando sesión de video", Toast.LENGTH_SHORT).show()
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                Toast.makeText(context, "No se pudo iniciar video: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 7. Función de Disparo Fotográfico (JPG, PNG, RAW DNG) con Guardado Permanente en MediaStore
    fun capturePhoto() {
        val texView = textureViewRef.value ?: return
        val chars = currentCharacteristics ?: return
        if (isCapturingPhoto) return
        isCapturingPhoto = true

        scope.launch(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val dateLabel = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date())

                // A. Guardado RAW (.DNG 16-bit) si fue elegido
                if ((captureFormat == CaptureFormat.RAW || captureFormat == CaptureFormat.RAW_JPG) && activeLens.supportsRaw && rawReader != null) {
                    val cam = cameraDevice
                    val session = captureSession
                    if (cam != null && session != null) {
                        var lastRes: TotalCaptureResult? = null
                        val rawReq = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(rawReader!!.surface)
                            if (isManualMode && activeLens.supportsManualSensor) {
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                                set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutterNs)
                            }
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

                // B. Captura Procesada con Film + LUT + VFX Interactivos + Aspect Ratio sin deformar
                var rawBitmap = withContext(Dispatchers.Main) { texView.bitmap }
                if (rawBitmap != null) {
                    // Si el teléfono está en posición horizontal, rotar el bitmap capturado para que quede derecho
                    if (isDeviceLandscape) {
                        val rotMatrix = Matrix().apply {
                            postRotate(if (uiRotationDeg < 0f) 90f else -90f)
                        }
                        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, rotMatrix, true)
                        if (rotated !== rawBitmap) {
                            rawBitmap.recycle()
                            rawBitmap = rotated
                        }
                    }

                    val shutterStr = "1/${(1_000_000_000L / selectedShutterNs.coerceAtLeast(1)).coerceAtLeast(1)}s"
                    val stamp = "${selectedPreset.brand} ${selectedPreset.name}  •  ISO $selectedIso  •  $shutterStr  •  $dateLabel"

                    val finalBmp = AnalogImageProcessor.processImage(
                        source = rawBitmap,
                        aspectMode = aspectMode,
                        preset = selectedPreset,
                        customLut = customLut,
                        vfx = vfxConfig,
                        light = sceneLight,
                        exposureBias = exposureEvSteps * activeLens.evStep,
                        disposableFlashActive = (flashMode == FlashMode.DISPOSABLE),
                        focusXNorm = focusNormX,
                        focusYNorm = focusNormY,
                        metadataStamp = stamp
                    )

                    val isPng = (captureFormat == CaptureFormat.PNG)
                    val ext = if (isPng) "png" else "jpg"
                    val mime = if (isPng) "image/png" else "image/jpeg"

                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_${timestamp}.$ext")
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
                    }
                    finalBmp.recycle()
                }

                refreshGalleryFromMediaStore()
                withContext(Dispatchers.Main) {
                    isCapturingPhoto = false
                    Toast.makeText(context, "Foto guardada en Galería (${captureFormat.label})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCapturingPhoto = false
                    Toast.makeText(context, "Error al capturar: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Si está abierta la pantalla de Galería y Modo Editor
    if (showGalleryScreen) {
        DarkroomGalleryScreen(
            persistedPhotos = persistedPhotos,
            onRefreshGallery = { refreshGalleryFromMediaStore() },
            onClose = { showGalleryScreen = false }
        )
        return
    }

    // ========================================================================
    // DISEÑO FIEL A LAS DOS IMÁGENES DE REFERENCIA (VERTICAL Y HORIZONTAL)
    // ========================================================================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090C))
            .onSizeChanged { viewBoxSize = it }
    ) {
        // 1. CONTENEDOR DEL VISOR (RESPETA EL ASPECT RATIO 16:9, 4:3, OPEN GATE SIN SQUEEZE)
        val viewfinderModifier = when (aspectMode) {
            AspectRatioMode.RATIO_16_9 -> Modifier.fillMaxWidth().aspectRatio(9f / 16f)
            AspectRatioMode.RATIO_4_3 -> Modifier.fillMaxWidth().aspectRatio(3f / 4f)
            AspectRatioMode.OPEN_GATE -> Modifier.fillMaxSize()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (!isDeviceLandscape) 104.dp else 0.dp),
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
                            focusNormX = (offset.x / size.width.toFloat()).coerceIn(0.08f, 0.92f)
                            focusNormY = (offset.y / size.height.toFloat()).coerceIn(0.08f, 0.92f)
                            showFocusSliderToast = true
                            val cam = cameraDevice
                            val sess = captureSession
                            val tex = textureViewRef.value
                            if (cam != null && sess != null && tex != null) {
                                applyRepeatingPreviewParameters(cam, sess, tex, offset)
                            }
                        }
                    }
            ) {
                // TEXTUREVIEW DE CÁMARA SIN DEFORMACIÓN
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef.value = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    openSelectedCamera(activeLens, this@apply)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                                    applyNonDistortingTransform(this@apply, w, h, previewBufferSize)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // CAPA EN TIEMPO REAL DE EMULACIÓN DE FILM, FLARES INTERACTIVOS, LIGHT LEAKS, ANAMÓRFICOS Y GRANO
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Tinte en vivo de la película seleccionada para ver el look antes de disparar
                    val m = selectedPreset.matrix
                    val rTint = ((m[0] - 1f) * 120f + m[4]).toInt().coerceIn(-40, 60)
                    val gTint = ((m[6] - 1f) * 120f + m[9]).toInt().coerceIn(-40, 60)
                    val bTint = ((m[12] - 1f) * 120f + m[14]).toInt().coerceIn(-40, 60)
                    if (selectedPreset.id == "fuji_acros" || selectedPreset.id == "kodak_trix") {
                        drawRect(Color.Black.copy(alpha = 0.25f))
                    } else if (rTint != 0 || gTint != 0 || bTint != 0) {
                        val tintCol = Color(
                            red = (128 + rTint).coerceIn(0, 255) / 255f,
                            green = (128 + gTint).coerceIn(0, 255) / 255f,
                            blue = (128 + bTint).coerceIn(0, 255) / 255f,
                            alpha = 0.14f
                        )
                        drawRect(tintCol)
                    }

                    // Dibujar VFX Ópticos Interactivos en Tiempo Real sobre el visor
                    drawIntoCanvas { composeCanvas ->
                        AnalogImageProcessor.drawInteractiveOpticalVfx(
                            canvas = composeCanvas.nativeCanvas,
                            width = w,
                            height = h,
                            vfx = vfxConfig,
                            light = sceneLight,
                            focusXNorm = focusNormX,
                            focusYNorm = focusNormY
                        )
                    }

                    // Grano en vivo sutil en tiempo real
                    if (vfxConfig.grainIntensity > 0.05f) {
                        val dotCount = (vfxConfig.grainIntensity * 180).toInt()
                        val rnd = Random(System.currentTimeMillis() / 60)
                        for (i in 0 until dotCount) {
                            val gx = rnd.nextFloat() * w
                            val gy = rnd.nextFloat() * h
                            drawCircle(
                                color = Color.White.copy(alpha = vfxConfig.grainIntensity * 0.22f),
                                radius = 1.4f,
                                center = Offset(gx, gy)
                            )
                        }
                    }

                    // Marco Polaroid en vivo si está activado en VFX
                    if (vfxConfig.polaroidFrame) {
                        val bSide = w * 0.045f
                        val bBot = h * 0.12f
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, bSide))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, h - bBot), size = androidx.compose.ui.geometry.Size(w, bBot))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(bSide, h))
                        drawRect(Color(0xFFF7F5EE), topLeft = Offset(w - bSide, 0f), size = androidx.compose.ui.geometry.Size(bSide, h))
                    }

                    // ========================================================
                    // HUD DE ESQUINAS Y RETÍCULA CENTRAL (IDÉNTICO A REFERENCIA)
                    // ========================================================
                    val pad = 18.dp.toPx()
                    val arm = 58.dp.toPx()
                    val strokeW = 1.4.dp.toPx()
                    val bracketCol = Color.White.copy(alpha = 0.85f)

                    // Esquina Superior Izquierda ┌
                    drawLine(bracketCol, Offset(pad, pad), Offset(pad + arm * 1.4f, pad), strokeW)
                    drawLine(bracketCol, Offset(pad, pad), Offset(pad, pad + arm), strokeW)

                    // Esquina Superior Derecha ┐
                    val rightLimit = if (isDeviceLandscape) w - 165.dp.toPx() else w - pad
                    drawLine(bracketCol, Offset(rightLimit - arm * 1.4f, pad), Offset(rightLimit, pad), strokeW)
                    drawLine(bracketCol, Offset(rightLimit, pad), Offset(rightLimit, pad + arm), strokeW)

                    // Esquina Inferior Izquierda └
                    val botLimit = h - pad
                    drawLine(bracketCol, Offset(pad, botLimit - arm), Offset(pad, botLimit), strokeW)
                    drawLine(bracketCol, Offset(pad, botLimit), Offset(pad + arm * 1.4f, botLimit), strokeW)

                    // Retícula de Enfoque Central [ + ] (Se mueve al punto donde el usuario toca para enfocar)
                    val fx = focusNormX * w
                    val fy = focusNormY * h
                    val boxR = 44.dp.toPx()
                    val cornerLen = 18.dp.toPx()

                    // 4 esquinas del cuadro de enfoque
                    drawLine(bracketCol, Offset(fx - boxR, fy - boxR), Offset(fx - boxR + cornerLen, fy - boxR), strokeW)
                    drawLine(bracketCol, Offset(fx - boxR, fy - boxR), Offset(fx - boxR, fy - boxR + cornerLen), strokeW)

                    drawLine(bracketCol, Offset(fx + boxR - cornerLen, fy - boxR), Offset(fx + boxR, fy - boxR), strokeW)
                    drawLine(bracketCol, Offset(fx + boxR, fy - boxR), Offset(fx + boxR, fy - boxR + cornerLen), strokeW)

                    drawLine(bracketCol, Offset(fx - boxR, fy + boxR - cornerLen), Offset(fx - boxR, fy + boxR), strokeW)
                    drawLine(bracketCol, Offset(fx - boxR, fy + boxR), Offset(fx - boxR + cornerLen, fy + boxR), strokeW)

                    drawLine(bracketCol, Offset(fx + boxR - cornerLen, fy + boxR), Offset(fx + boxR, fy + boxR), strokeW)
                    drawLine(bracketCol, Offset(fx + boxR, fy + boxR - cornerLen), Offset(fx + boxR, fy + boxR), strokeW)

                    // Cruz central +
                    val cross = 8.dp.toPx()
                    drawLine(bracketCol, Offset(fx - cross, fy), Offset(fx + cross, fy), strokeW)
                    drawLine(bracketCol, Offset(fx, fy - cross), Offset(fx, fy + cross), strokeW)
                }

                // ============================================================
                // ELEMENTOS INTERACTIVOS DEL HUD DENTRO DEL VISOR
                // ============================================================

                // 1. Arriba Izquierda: Batería [||||]  +  [AUTO/MANUAL]  +  [16:9 / 4:3 / OPEN GATE]
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 28.dp, top = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Icono de Batería con barras estilo cámara de cine
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(16.dp)
                                .border(1.5.dp, Color.White, RoundedCornerShape(2.dp))
                                .padding(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(1.5.dp)
                            ) {
                                val bars = (batteryLevel / 25).coerceIn(1, 4)
                                for (b in 1..4) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (b <= bars) Color.White else Color.Transparent)
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(7.dp)
                                .background(Color.White)
                        )
                    }

                    // Etiqueta AUTO / PRO (Clic para alternar)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.clickable { isManualMode = !isManualMode }
                    ) {
                        Text(
                            text = if (isManualMode) "PRO" else "AUTO",
                            color = Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

                    // Etiqueta Aspect Ratio: 16:9 -> 4:3 -> OPEN GATE (Clic para cambiar formato)
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier.clickable {
                            val modes = AspectRatioMode.values()
                            aspectMode = modes[(aspectMode.ordinal + 1) % modes.size]
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
                            border = BorderStroke(1.dp, Color(0xFFD4AF37))
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

                // 2. Arriba Derecha: Indicador ● REC (Clic para grabar video o ver tiempo de grabación)
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = if (isDeviceLandscape) 180.dp else 28.dp, top = 26.dp)
                        .clickable { toggleVideoRecording() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (isRecordingVideo) Color(0xFFFF1744) else Color(0xFFE53935),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isRecordingVideo) {
                            String.format(Locale.US, "REC %02d:%02d", recordingSeconds / 60, recordingSeconds % 60)
                        } else {
                            "REC"
                        },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                }

                // 3. Alerta Inteligente cuando se detecta Baja Luz para ofrecer activar el Flash
                AnimatedVisibility(
                    visible = sceneLight.isLowLight && flashMode == FlashMode.OFF && !lowLightPromptDismissed,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 62.dp)
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
                            Icon(Icons.Default.FlashOn, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(16.dp))
                            Text("Baja luz detectada", color = Color.White, fontSize = 11.sp)
                            Text(
                                text = "ACTIVAR FLASH",
                                color = Color(0xFFD4AF37),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    flashMode = FlashMode.TORCH
                                }
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

                // 4. Control rápido de Desenfoque Macro cuando el usuario toca la pantalla para enfocar
                AnimatedVisibility(
                    visible = showFocusSliderToast,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = if (isDeviceLandscape) 175.dp else 14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        Text("BOKEH", color = Color(0xFFD4AF37), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = vfxConfig.sceneBlurBokeh,
                            onValueChange = { vfxConfig = vfxConfig.copy(sceneBlurBokeh = it) },
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            "Cerrar",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            modifier = Modifier.clickable { showFocusSliderToast = false }
                        )
                    }
                }

                // 5. Abajo Izquierda: Telemetría 4K / 30 fps / ISO100 + Película activa
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 28.dp, bottom = 24.dp)
                ) {
                    Text(
                        text = if (activeLens.maxVideoSize.width >= 3840) "4K" else "1080p",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isManualMode) "1/${(1_000_000_000L / selectedShutterNs.coerceAtLeast(1)).coerceAtLeast(1)}s" else "30 fps",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "ISO$selectedIso",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${selectedPreset.brand} ${selectedPreset.name}",
                        color = Color(0xFFD4AF37),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // ============================================================
                // DOCK TRANSLÚCIDO DE 4 BOTONES: FILMS | VFX | PRO | LENS
                // Se adapta automáticamente a Vertical (abajo) u Horizontal (derecha)
                // ============================================================
                if (!isDeviceLandscape) {
                    // DOCK HORIZONTAL (FORMATO VERTICAL - IMAGEN 2)
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
                                onClick = { activeDockTab = if (activeDockTab == DockTab.FILMS) DockTab.NONE else DockTab.FILMS }
                            )
                            DockIconButton(
                                icon = Icons.Default.AutoFixHigh,
                                label = "VFX",
                                selected = activeDockTab == DockTab.VFX,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.VFX) DockTab.NONE else DockTab.VFX }
                            )
                            DockIconButton(
                                icon = Icons.Default.Camera,
                                label = "PRO",
                                selected = activeDockTab == DockTab.PRO,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.PRO) DockTab.NONE else DockTab.PRO }
                            )
                            DockIconButton(
                                icon = Icons.Default.CenterFocusStrong,
                                label = "LENS",
                                selected = activeDockTab == DockTab.LENS,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.LENS) DockTab.NONE else DockTab.LENS }
                            )
                        }
                    }
                } else {
                    // DOCK VERTICAL EN LA DERECHA DEL VISOR (FORMATO HORIZONTAL - IMAGEN 1)
                    Surface(
                        color = Color(0xAA101216),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 104.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            DockIconButton(
                                icon = Icons.Default.Theaters,
                                label = "FILMS",
                                selected = activeDockTab == DockTab.FILMS,
                                rotationDeg = animatedUiRotation,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.FILMS) DockTab.NONE else DockTab.FILMS }
                            )
                            DockIconButton(
                                icon = Icons.Default.AutoFixHigh,
                                label = "VFX",
                                selected = activeDockTab == DockTab.VFX,
                                rotationDeg = animatedUiRotation,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.VFX) DockTab.NONE else DockTab.VFX }
                            )
                            DockIconButton(
                                icon = Icons.Default.Camera,
                                label = "PRO",
                                selected = activeDockTab == DockTab.PRO,
                                rotationDeg = animatedUiRotation,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.PRO) DockTab.NONE else DockTab.PRO }
                            )
                            DockIconButton(
                                icon = Icons.Default.CenterFocusStrong,
                                label = "LENS",
                                selected = activeDockTab == DockTab.LENS,
                                rotationDeg = animatedUiRotation,
                                onClick = { activeDockTab = if (activeDockTab == DockTab.LENS) DockTab.NONE else DockTab.LENS }
                            )
                        }
                    }
                }

                // ============================================================
                // PANELES DESPLEGABLES EN TIEMPO REAL (FILMS, VFX, PRO, LENS)
                // ============================================================
                AnimatedVisibility(
                    visible = activeDockTab != DockTab.NONE,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = if (!isDeviceLandscape) 86.dp else 16.dp,
                            start = 12.dp,
                            end = if (isDeviceLandscape) 170.dp else 12.dp
                        )
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
                                        DockTab.FILMS -> "FILMS & LUTS EN TIEMPO REAL"
                                        DockTab.VFX -> "VFX ÓPTICOS INTERACTIVOS (SOL & ALTAS LUCES)"
                                        DockTab.PRO -> "CONTROLES MANUALES PRO & FLASH"
                                        DockTab.LENS -> "SELECCIÓN DE LENTE FÍSICO"
                                        else -> ""
                                    },
                                    color = Color(0xFFD4AF37),
                                    fontSize = 11.sp,
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
                                    // Carrusel de Películas Analógicas con Imagen Representativa de cada Film
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
                                                Text(
                                                    text = preset.isoTag,
                                                    color = if (isSelected) Color.DarkGray else Color(0xFFD4AF37),
                                                    fontSize = 8.sp
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
                                        if (customLut != null) {
                                            item {
                                                TextButton(onClick = { customLut = null }) {
                                                    Text("Quitar LUT", color = Color(0xFFE53935), fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                DockTab.VFX -> {
                                    Column(
                                        modifier = Modifier
                                            .heightIn(max = 210.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            "Flare Solar (Interactúa con la posición del Sol en cámara): ${(vfxConfig.sunFlareIntensity * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.sunFlareIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(sunFlareIntensity = it) }
                                        )

                                        Text(
                                            "Light Leaks Orgánicos (Reaccionan a la fuente de luz): ${(vfxConfig.lightLeakIntensity * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.lightLeakIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(lightLeakIntensity = it) }
                                        )

                                        Text(
                                            "Light Leaks Anamórficos (En puntos de Altas Luces): ${(vfxConfig.anamorphicIntensity * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.anamorphicIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(anamorphicIntensity = it) }
                                        )

                                        Text(
                                            "Halation CineStill (Resplandor rojo en luces altas): ${(vfxConfig.halationIntensity * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.halationIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(halationIntensity = it) }
                                        )

                                        Text(
                                            "Grano de Película: ${(vfxConfig.grainIntensity * 100).toInt()}%  |  Ruido: ${(vfxConfig.noiseIntensity * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.grainIntensity,
                                            onValueChange = { vfxConfig = vfxConfig.copy(grainIntensity = it) }
                                        )

                                        Text(
                                            "Desenfoque de Escena (Bokeh Objetos Cercanos): ${(vfxConfig.sceneBlurBokeh * 100).toInt()}%",
                                            color = Color.White,
                                            fontSize = 11.sp
                                        )
                                        Slider(
                                            value = vfxConfig.sceneBlurBokeh,
                                            onValueChange = { vfxConfig = vfxConfig.copy(sceneBlurBokeh = it) }
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
                                            .heightIn(max = 220.dp)
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

                                        if (!isManualMode) {
                                            Text("Compensación EV (${if (exposureEvSteps >= 0) "+" else ""}$exposureEvSteps pasos)", color = Color.LightGray, fontSize = 11.sp)
                                            Slider(
                                                value = exposureEvSteps.toFloat(),
                                                onValueChange = { exposureEvSteps = it.toInt() },
                                                valueRange = activeLens.evRange.lower.toFloat()..activeLens.evRange.upper.toFloat()
                                            )
                                        } else {
                                            val isoR = activeLens.isoRange
                                            Text("ISO Analógico: $selectedIso", color = Color.LightGray, fontSize = 11.sp)
                                            Slider(
                                                value = selectedIso.toFloat().coerceIn(isoR.lower.toFloat(), isoR.upper.toFloat()),
                                                onValueChange = { selectedIso = it.toInt() },
                                                valueRange = isoR.lower.toFloat()..isoR.upper.toFloat().coerceAtMost(6400f)
                                            )

                                            val shutterDenom = (1_000_000_000L / selectedShutterNs.coerceAtLeast(1)).coerceAtLeast(1)
                                            Text("Velocidad de Obturación: 1/${shutterDenom}s", color = Color.LightGray, fontSize = 11.sp)
                                            Slider(
                                                value = selectedShutterNs.toFloat(),
                                                onValueChange = { selectedShutterNs = it.toLong() },
                                                valueRange = 200_000f..100_000_000f
                                            )
                                        }

                                        if (activeLens.minFocusDistance > 0f) {
                                            Text(
                                                "Enfoque Óptico Macro (Objetos Cercanos): ${(manualFocusDiopter / activeLens.minFocusDistance * 100).toInt()}%",
                                                color = Color.LightGray,
                                                fontSize = 11.sp
                                            )
                                            Slider(
                                                value = manualFocusDiopter,
                                                onValueChange = { manualFocusDiopter = it },
                                                valueRange = 0f..activeLens.minFocusDistance
                                            )
                                        }

                                        // Control de Flash e Intensidad del Flash
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
                                    // Selector de todas las cámaras físicas/lógicas disponibles en el dispositivo
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
        // 2. BARRA DE DISPARO METÁLICA + MINIATURA DE GALERÍA + VIDEO + CAMBIO DE CÁMARA
        // Adaptada a Vertical (abajo) y Horizontal (columna derecha)
        // ====================================================================
        if (!isDeviceLandscape) {
            // BARRA INFERIOR (MODO VERTICAL - IMAGEN 2)
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
                // Miniatura Cuadrada Redondeada de la Galería Persistente
                GalleryThumbnailButton(
                    bitmap = latestThumbnailBmp,
                    rotationDeg = animatedUiRotation,
                    onClick = { showGalleryScreen = true }
                )

                // Disparador Metálico Cromado con Acentos Rojos + Botón de Video a su lado
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MetallicShutterButton(
                        isCapturing = isCapturingPhoto,
                        onClick = { capturePhoto() }
                    )

                    // Botón dedicado para Grabar Video en máxima calidad al lado del disparador
                    VideoRecordSideButton(
                        isRecording = isRecordingVideo,
                        onClick = { toggleVideoRecording() }
                    )
                }

                // Botón Circular de Cambio Rápido de Cámara Frontal / Trasera
                CameraSwitchCircleButton(
                    rotationDeg = animatedUiRotation,
                    onClick = {
                        val targetFront = !activeLens.isFront
                        val nextLens = availableLenses.firstOrNull { it.isFront == targetFront } ?: availableLenses.first()
                        activeLens = nextLens
                        currentZoom = 1.0f
                        textureViewRef.value?.let { openSelectedCamera(nextLens, it) }
                    }
                )
            }
        } else {
            // COLUMNA LATERAL DERECHA (MODO HORIZONTAL - IMAGEN 1)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(96.dp)
                    .fillMaxHeight()
                    .background(Color(0xCC0A0B0E))
                    .padding(vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Arriba: Cambio de Cámara
                CameraSwitchCircleButton(
                    rotationDeg = animatedUiRotation,
                    onClick = {
                        val targetFront = !activeLens.isFront
                        val nextLens = availableLenses.firstOrNull { it.isFront == targetFront } ?: availableLenses.first()
                        activeLens = nextLens
                        currentZoom = 1.0f
                        textureViewRef.value?.let { openSelectedCamera(nextLens, it) }
                    }
                )

                // Centro: Disparador Metálico Cromado + Botón de Video justo al lado/debajo
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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

                // Abajo: Miniatura de Galería Persistente
                GalleryThumbnailButton(
                    bitmap = latestThumbnailBmp,
                    rotationDeg = animatedUiRotation,
                    onClick = { showGalleryScreen = true }
                )
            }
        }
    }

    // MODAL DE DIAGNÓSTICO DE CAPACIDAD MÁXIMA DEL SENSOR
    if (showHardwareModal) {
        AlertDialog(
            onDismissRequest = { showHardwareModal = false },
            containerColor = Color(0xFF14161D),
            title = {
                Text(
                    "Capacidad Máxima del Sensor",
                    color = Color(0xFFD4AF37),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• Lente Activo: ${activeLens.label} (ID ${activeLens.cameraId})", color = Color.White, fontSize = 12.sp)
                    Text("• Nivel Hardware ISP: ${activeLens.hardwareLevel}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución Fotográfica Máxima: ${activeLens.maxResolutionMp}", color = Color.White, fontSize = 12.sp)
                    Text("• Resolución de Video Máxima: ${activeLens.maxVideoSize.width}x${activeLens.maxVideoSize.height}", color = Color.White, fontSize = 12.sp)
                    Text("• Tamaño Físico del Sensor: ${activeLens.physicalSizeMm}", color = Color.White, fontSize = 12.sp)
                    Text(
                        "• Captura RAW (.DNG 16-bit): ${if (activeLens.supportsRaw) "SOPORTADO (${activeLens.maxRawSize?.width}x${activeLens.maxRawSize?.height})" else "No expuesto en este lente"}",
                        color = if (activeLens.supportsRaw) Color(0xFF81C784) else Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )
                    Text("• Rango ISO Nativo: ${activeLens.isoRange.lower} - ${activeLens.isoRange.upper}", color = Color.White, fontSize = 12.sp)
                    Text("• Cámaras Físicas Detectadas: ${availableLenses.size}", color = Color.White, fontSize = 12.sp)
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

// ============================================================================
// COMPONENTES VISUALES DE LA INTERFAZ (FIELES A LAS IMÁGENES DE REFERENCIA)
// ============================================================================

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
        // Anillo metálico cromado con acentos rojos (idéntico al botón de las imágenes de referencia)
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

            // Dos muescas rojas en el anillo cromado (arriba/derecha y abajo)
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

        // Círculo blanco puro interior
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
