package com.analogpro.camera

import android.graphics.*
import android.util.Range
import android.util.Size

enum class AspectRatioMode(val label: String, val ratioPortrait: Float) {
    RATIO_16_9("16:9", 9f / 16f),
    RATIO_4_3("4:3", 3f / 4f),
    OPEN_GATE("OPEN GATE", 0f) // 0f = full native sensor ratio
}

enum class CaptureFormat(val label: String) {
    JPG("JPG"),
    PNG("PNG"),
    RAW("RAW (.DNG)"),
    RAW_JPG("RAW + JPG")
}

enum class FlashMode(val label: String) {
    OFF("OFF"),
    AUTO("AUTO"),
    ON("ON"),
    TORCH("LUZ CONTINUA"),
    DISPOSABLE("FLASH DESECHABLE")
}

enum class DockTab {
    NONE, FILMS, VFX, PRO, LENS
}

data class FilmPreset(
    val id: String,
    val brand: String,
    val name: String,
    val isoTag: String,
    val accentColor: Int,
    val matrix: FloatArray,
    val defaultGrain: Float,
    val defaultNoise: Float,
    val vignette: Float,
    val disposableFlashLook: Boolean = false
)

data class VfxConfig(
    val sunFlareIntensity: Float = 0.0f,
    val lightLeakIntensity: Float = 0.0f,
    val anamorphicIntensity: Float = 0.0f,
    val halationIntensity: Float = 0.0f,
    val grainIntensity: Float = 0.22f,
    val noiseIntensity: Float = 0.08f,
    val sceneBlurBokeh: Float = 0.0f,
    val polaroidFrame: Boolean = false,
    val anamorphicWarmColor: Boolean = false
)

data class HighlightPoint(
    val xNorm: Float,
    val yNorm: Float,
    val luma: Float,
    val color: Int
)

data class SceneLightAnalysis(
    val avgLuma: Float = 0.45f,
    val isLowLight: Boolean = false,
    val sunX: Float = 0.78f,
    val sunY: Float = 0.22f,
    val sunStrength: Float = 0.65f,
    val highlights: List<HighlightPoint> = emptyList()
)

data class LensCameraInfo(
    val cameraId: String,
    val label: String,
    val badge: String,
    val isFront: Boolean,
    val focalLengthMm: Float,
    val aperture: Float,
    val hardwareLevel: String,
    val maxResolutionMp: String,
    val maxJpegSize: Size,
    val maxRawSize: Size?,
    val maxVideoSize: Size,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val physicalSizeMm: String,
    val isoRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val evRange: Range<Int>,
    val evStep: Float,
    val minFocusDistance: Float,
    val maxZoom: Float,
    val hasFlash: Boolean,
    val maxFlashStrengthLevel: Int
)

val BUILT_IN_PRESETS = listOf(
    FilmPreset(
        "std_neutral", "OPTICAL", "Standard Natural", "ISO 100",
        android.graphics.Color.rgb(180, 195, 210),
        floatArrayOf(
            1.00f, 0.00f, 0.00f, 0f, 0f,
            0.00f, 1.00f, 0.00f, 0f, 0f,
            0.00f, 0.00f, 1.00f, 0f, 0f,
            0f,    0f,    0f,    1f, 0f
        ),
        defaultGrain = 0.0f, defaultNoise = 0.0f, vignette = 0.05f
    ),
    FilmPreset(
        "fuji_cc", "FUJIFILM", "Classic Chrome", "ISO 200",
        android.graphics.Color.rgb(46, 139, 87),
        floatArrayOf(
            0.95f, 0.05f, 0.00f, 0f, -5f,
            0.02f, 0.92f, 0.06f, 0f, -5f,
            0.00f, 0.08f, 0.88f, 0f,  5f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.22f, defaultNoise = 0.08f, vignette = 0.22f
    ),
    FilmPreset(
        "fuji_velvia", "FUJIFILM", "Velvia 50", "ISO 50",
        android.graphics.Color.rgb(34, 165, 95),
        floatArrayOf(
            1.25f, -0.10f, -0.05f, 0f, -8f,
           -0.05f,  1.20f, -0.05f, 0f, -5f,
           -0.05f, -0.10f,  1.30f, 0f, -5f,
            0f,     0f,     0f,    1f,  0f
        ),
        defaultGrain = 0.12f, defaultNoise = 0.04f, vignette = 0.20f
    ),
    FilmPreset(
        "fuji_superia", "FUJIFILM", "Superia X-TRA", "ISO 400",
        android.graphics.Color.rgb(50, 180, 110),
        floatArrayOf(
            1.04f, 0.02f, 0.00f, 0f,  4f,
            0.00f, 1.08f, 0.02f, 0f,  6f,
            0.04f, 0.00f, 1.06f, 0f,  2f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.28f, defaultNoise = 0.12f, vignette = 0.24f
    ),
    FilmPreset(
        "fuji_acros", "FUJIFILM", "Neopan Acros", "ISO 100",
        android.graphics.Color.rgb(160, 160, 160),
        floatArrayOf(
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0.35f, 0.55f, 0.10f, 0f, -10f,
            0f,    0f,    0f,    1f,   0f
        ),
        defaultGrain = 0.30f, defaultNoise = 0.05f, vignette = 0.30f
    ),
    FilmPreset(
        "kodak_portra", "KODAK", "Portra 400", "ISO 400",
        android.graphics.Color.rgb(235, 175, 40),
        floatArrayOf(
            1.08f, 0.04f, -0.02f, 0f, 8f,
            0.02f, 1.02f,  0.00f, 0f, 4f,
           -0.02f, 0.02f,  0.92f, 0f, -4f,
            0f,    0f,     0f,    1f, 0f
        ),
        defaultGrain = 0.24f, defaultNoise = 0.10f, vignette = 0.18f
    ),
    FilmPreset(
        "kodak_gold", "KODAK", "Gold 200", "ISO 200",
        android.graphics.Color.rgb(245, 190, 30),
        floatArrayOf(
            1.14f, 0.05f, 0.00f, 0f, 12f,
            0.03f, 1.05f, 0.00f, 0f,  8f,
            0.00f, 0.00f, 0.85f, 0f, -8f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.32f, defaultNoise = 0.15f, vignette = 0.25f
    ),
    FilmPreset(
        "kodak_ektar", "KODAK", "Ektar 100", "ISO 100",
        android.graphics.Color.rgb(220, 75, 45),
        floatArrayOf(
            1.18f, -0.04f, -0.02f, 0f, -4f,
           -0.02f,  1.10f, -0.02f, 0f, -2f,
           -0.04f, -0.02f,  1.15f, 0f,  2f,
            0f,     0f,     0f,    1f,  0f
        ),
        defaultGrain = 0.15f, defaultNoise = 0.06f, vignette = 0.15f
    ),
    FilmPreset(
        "cinestill_800t", "CINESTILL", "800Tungsten", "ISO 800",
        android.graphics.Color.rgb(55, 165, 225),
        floatArrayOf(
            0.94f, 0.02f, 0.04f, 0f, -4f,
            0.00f, 0.98f, 0.06f, 0f,  2f,
            0.02f, 0.08f, 1.16f, 0f, 12f,
            0f,    0f,    0f,    1f,  0f
        ),
        defaultGrain = 0.34f, defaultNoise = 0.16f, vignette = 0.28f
    ),
    FilmPreset(
        "kodak_trix", "KODAK", "Tri-X 400 B&W", "ISO 400",
        android.graphics.Color.rgb(210, 165, 45),
        floatArrayOf(
            0.40f, 0.50f, 0.10f, 0f, -15f,
            0.40f, 0.50f, 0.10f, 0f, -15f,
            0.40f, 0.50f, 0.10f, 0f, -15f,
            0f,    0f,    0f,    1f,   0f
        ),
        defaultGrain = 0.42f, defaultNoise = 0.06f, vignette = 0.35f
    ),
    FilmPreset(
        "fuji_quicksnap", "DESECHABLE", "Fuji QuickSnap", "ISO 400",
        android.graphics.Color.rgb(40, 195, 130),
        floatArrayOf(
            0.98f, 0.08f, 0.02f, 0f, 6f,
            0.04f, 1.08f, 0.04f, 0f, 12f,
            0.02f, 0.06f, 0.90f, 0f, 8f,
            0f,    0f,    0f,    1f, 0f
        ),
        defaultGrain = 0.45f, defaultNoise = 0.25f, vignette = 0.50f, disposableFlashLook = true
    ),
    FilmPreset(
        "kodak_funsaver", "DESECHABLE", "Kodak FunSaver", "ISO 800",
        android.graphics.Color.rgb(250, 180, 20),
        floatArrayOf(
            1.15f, 0.06f, 0.00f, 0f, 14f,
            0.04f, 1.00f, 0.02f, 0f, 6f,
            0.00f, 0.04f, 0.82f, 0f, 10f,
            0f,    0f,    0f,    1f, 0f
        ),
        defaultGrain = 0.55f, defaultNoise = 0.32f, vignette = 0.55f, disposableFlashLook = true
    )
)

val BUILT_IN_ASSET_LUTS = listOf(
    "Fujifilm_Classic_Neg.cube",
    "Kodak_Vision3_500T.cube",
    "Kodak_TriX_400_BW.cube",
    "Disposable_Warm_Flash.cube"
)

/**
 * Genera una imagen representativa en miniatura para cada película (Film Preset)
 * mostrando un paisaje nocturno/atardecer con bokeh procesado con la matriz real del Film.
 */
object FilmThumbnailGenerator {
    private val cache = HashMap<String, Bitmap>()

    fun getRepresentativeThumbnail(preset: FilmPreset): Bitmap {
        cache[preset.id]?.let { return it }
        val w = 160
        val h = 110
        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)

        // 1. Cielo degradado de atardecer / ciudad nocturna
        val skyGrad = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(24, 52, 92),
                Color.rgb(185, 88, 62),
                Color.rgb(245, 175, 95)
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = skyGrad }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        // 2. Sol cálido + Silueta de edificios
        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 235, 170)
        }
        c.drawCircle(w * 0.72f, h * 0.34f, 16f, sunPaint)

        val cityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 22, 30)
        }
        c.drawRect(0f, h * 0.68f, w.toFloat(), h.toFloat(), cityPaint)
        c.drawRect(w * 0.12f, h * 0.46f, w * 0.28f, h.toFloat(), cityPaint)
        c.drawRect(w * 0.34f, h * 0.52f, w * 0.50f, h.toFloat(), cityPaint)
        c.drawRect(w * 0.56f, h * 0.42f, w * 0.70f, h.toFloat(), cityPaint)

        // 3. Luces Bokeh de ciudad (estilo referencia visual)
        val bokehColors = intArrayOf(
            Color.argb(190, 255, 170, 70),
            Color.argb(180, 255, 90, 65),
            Color.argb(175, 95, 210, 255),
            Color.argb(195, 255, 230, 140)
        )
        val bokehPositions = arrayOf(
            PointF(0.20f, 0.72f), PointF(0.38f, 0.65f), PointF(0.52f, 0.78f),
            PointF(0.68f, 0.68f), PointF(0.84f, 0.74f), PointF(0.15f, 0.56f),
            PointF(0.62f, 0.54f), PointF(0.80f, 0.58f)
        )
        bokehPositions.forEachIndexed { idx, pt ->
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bokehColors[idx % bokehColors.size]
            }
            c.drawCircle(pt.x * w, pt.y * h, (6 + (idx % 3) * 3).toFloat(), bp)
        }

        // 4. Aplicar la matriz de color real de esta película sobre la escena representativa
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)
        val matrixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(preset.matrix))
        }
        outCanvas.drawBitmap(base, 0f, 0f, matrixPaint)
        base.recycle()

        // 5. Banda lateral de carrete analógico
        val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = preset.accentColor }
        outCanvas.drawRect(0f, h - 6f, w.toFloat(), h.toFloat(), stripPaint)

        cache[preset.id] = out
        return out
    }
}
