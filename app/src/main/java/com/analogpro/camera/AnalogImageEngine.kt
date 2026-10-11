package com.analogpro.camera

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlin.math.*
import kotlin.random.Random

class CubeLut3D(val name: String, val size: Int, val table: FloatArray) {
    fun lookup(r: Int, g: Int, b: Int, strength: Float = 0.75f): Int {
        val rf = (r / 255f) * (size - 1)
        val gf = (g / 255f) * (size - 1)
        val bf = (b / 255f) * (size - 1)

        val ri = rf.toInt().coerceIn(0, size - 1)
        val gi = gf.toInt().coerceIn(0, size - 1)
        val bi = bf.toInt().coerceIn(0, size - 1)

        val idx = ((bi * size * size) + (gi * size) + ri) * 3
        if (idx + 2 >= table.size) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        val lutR = (table[idx] * 255f).toInt().coerceIn(0, 255)
        val lutG = (table[idx + 1] * 255f).toInt().coerceIn(0, 255)
        val lutB = (table[idx + 2] * 255f).toInt().coerceIn(0, 255)

        val outR = (r + (lutR - r) * strength).toInt().coerceIn(0, 255)
        val outG = (g + (lutG - g) * strength).toInt().coerceIn(0, 255)
        val outB = (b + (lutB - b) * strength).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }
}

object CubeLutParser {
    fun parseStream(stream: InputStream, customName: String): CubeLut3D? {
        return try {
            val reader = BufferedReader(InputStreamReader(stream))
            var lutSize = 17
            val values = ArrayList<Float>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("TITLE") || trimmed.startsWith("DOMAIN")) continue
                if (trimmed.startsWith("LUT_3D_SIZE")) {
                    lutSize = trimmed.split("\\s+".toRegex())[1].toInt()
                    continue
                }
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull()
                    val g = parts[1].toFloatOrNull()
                    val b = parts[2].toFloatOrNull()
                    if (r != null && g != null && b != null) {
                        values.add(r)
                        values.add(g)
                        values.add(b)
                    }
                }
            }
            if (values.isEmpty()) null else CubeLut3D(customName, lutSize, values.toFloatArray())
        } catch (e: Exception) {
            null
        }
    }

    fun parseUri(context: Context, uri: Uri, customName: String): CubeLut3D? {
        return context.contentResolver.openInputStream(uri)?.use { parseStream(it, customName) }
    }

    fun parseAsset(context: Context, assetFileName: String): CubeLut3D? {
        return context.assets.open("luts/$assetFileName").use {
            parseStream(it, assetFileName.removeSuffix(".cube").replace("_", " "))
        }
    }
}

/**
 * Receta de edición que se guarda dentro de la metadata EXIF de cada fotografía
 * para poder re-editarla posteriormente sin perder los parámetros.
 */
data class PhotoEditRecipe(
    val presetId: String = "none",
    val ev: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val grain: Float = 0.10f,
    val noise: Float = 0.03f,
    val flare: Float = 0f,
    val leak: Float = 0f,
    val anamorphic: Float = 0f,
    val halation: Float = 0f,
    val polaroid: Boolean = false,
    val aspectName: String = AspectRatioMode.RATIO_16_9.name
) {
    fun serialize(): String {
        return String.format(
            Locale.US,
            "ANALOGPRO_V3|preset=%s|ev=%.3f|temp=%.3f|tint=%.3f|grain=%.3f|noise=%.3f|flare=%.3f|leak=%.3f|anam=%.3f|hal=%.3f|pol=%b|asp=%s",
            presetId, ev, temperature, tint, grain, noise, flare, leak, anamorphic, halation, polaroid, aspectName
        )
    }

    companion object {
        fun deserialize(raw: String?): PhotoEditRecipe? {
            if (raw.isNullOrBlank() || !raw.startsWith("ANALOGPRO_V3|")) return null
            val map = HashMap<String, String>()
            raw.split("|").drop(1).forEach { token ->
                val kv = token.split("=")
                if (kv.size == 2) map[kv[0]] = kv[1]
            }
            return PhotoEditRecipe(
                presetId = map["preset"] ?: "none",
                ev = map["ev"]?.toFloatOrNull() ?: 0f,
                temperature = map["temp"]?.toFloatOrNull() ?: 0f,
                tint = map["tint"]?.toFloatOrNull() ?: 0f,
                grain = map["grain"]?.toFloatOrNull() ?: 0.10f,
                noise = map["noise"]?.toFloatOrNull() ?: 0.03f,
                flare = map["flare"]?.toFloatOrNull() ?: 0f,
                leak = map["leak"]?.toFloatOrNull() ?: 0f,
                anamorphic = map["anam"]?.toFloatOrNull() ?: 0f,
                halation = map["hal"]?.toFloatOrNull() ?: 0f,
                polaroid = map["pol"]?.toBooleanStrictOrNull() ?: false,
                aspectName = map["asp"] ?: AspectRatioMode.RATIO_16_9.name
            )
        }
    }
}

object AnalogImageProcessor {

    /**
     * Construye una ColorMatrix adaptada al sensor activo (frontal vs trasero y área física)
     * junto con exposición, temperatura y tinte suaves para evitar sobresaturación o clipping de 8 bits.
     */
    fun buildSensorAdaptedMatrix(
        preset: FilmPreset,
        isFrontSensor: Boolean,
        sensorAreaMm2: Float = 25f,
        exposureBias: Float = 0f,
        temperature: Float = 0f,
        tint: Float = 0f
    ): ColorMatrix {
        val identity = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        // En cámara frontal o sensores pequeños, reducimos la agresividad del LUT para proteger piel y rango dinámico
        val sensorStrength = when {
            preset.isNoLut -> 0f
            isFrontSensor -> 0.56f
            sensorAreaMm2 < 18f -> 0.68f
            else -> 0.85f
        }

        val blended = FloatArray(20)
        for (i in 0 until 20) {
            blended[i] = identity[i] + (preset.matrix[i] - identity[i]) * sensorStrength
        }

        val resultMatrix = ColorMatrix(blended)

        // Exposición suave
        val expScale = 2.0f.pow(exposureBias.coerceIn(-2.5f, 2.5f))

        // Temperatura (-1 frío a +1 cálido) y Tinte (-1 verde a +1 magenta) muy sutiles y no agresivos
        val rGain = (1f + temperature * 0.08f + tint * 0.03f) * expScale
        val gGain = (1f - abs(temperature) * 0.015f - tint * 0.06f) * expScale
        val bGain = (1f - temperature * 0.08f + tint * 0.03f) * expScale

        val rOffset = temperature * 10f + tint * 4f
        val gOffset = -tint * 8f
        val bOffset = -temperature * 10f + tint * 4f

        val wbAndExpMatrix = ColorMatrix(
            floatArrayOf(
                rGain, 0f,    0f,    0f, rOffset,
                0f,    gGain, 0f,    0f, gOffset,
                0f,    0f,    bGain, 0f, bOffset,
                0f,    0f,    0f,    1f, 0f
            )
        )

        resultMatrix.preConcat(wbAndExpMatrix)
        return resultMatrix
    }

    fun analyzeSceneLight(smallBitmap: Bitmap): SceneLightAnalysis {
        val w = smallBitmap.width
        val h = smallBitmap.height
        if (w <= 0 || h <= 0) return SceneLightAnalysis()

        val pixels = IntArray(w * h)
        smallBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var totalLuma = 0f
        var weightedSunX = 0f
        var weightedSunY = 0f
        var brightWeightSum = 0f
        var maxLuma = 0f

        val candidates = ArrayList<HighlightPoint>()

        for (y in 0 until h step 2) {
            for (x in 0 until w step 2) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                totalLuma += luma

                if (luma > maxLuma) maxLuma = luma

                if (luma > 0.76f) {
                    val weight = (luma - 0.72f).pow(2) * 10f
                    weightedSunX += (x.toFloat() / w) * weight
                    weightedSunY += (y.toFloat() / h) * weight
                    brightWeightSum += weight

                    if (luma > 0.84f) {
                        candidates.add(
                            HighlightPoint(
                                xNorm = x.toFloat() / w,
                                yNorm = y.toFloat() / h,
                                luma = luma,
                                color = c
                            )
                        )
                    }
                }
            }
        }

        val sampleCount = ((w / 2) * (h / 2)).coerceAtLeast(1)
        val avgLuma = (totalLuma / sampleCount).coerceIn(0f, 1f)

        val sunX = if (brightWeightSum > 0.001f) (weightedSunX / brightWeightSum).coerceIn(0.05f, 0.95f) else 0.78f
        val sunY = if (brightWeightSum > 0.001f) (weightedSunY / brightWeightSum).coerceIn(0.05f, 0.95f) else 0.22f
        val sunStrength = ((maxLuma - 0.5f) * 2f).coerceIn(0.25f, 1.0f)

        val topHighlights = ArrayList<HighlightPoint>()
        candidates.sortByDescending { it.luma }
        for (pt in candidates) {
            if (topHighlights.size >= 10) break
            val tooClose = topHighlights.any { existing ->
                hypot(existing.xNorm - pt.xNorm, existing.yNorm - pt.yNorm) < 0.11f
            }
            if (!tooClose) {
                topHighlights.add(pt)
            }
        }

        return SceneLightAnalysis(
            avgLuma = avgLuma,
            isLowLight = avgLuma < 0.17f,
            sunX = sunX,
            sunY = sunY,
            sunStrength = sunStrength,
            highlights = topHighlights
        )
    }

    fun drawInteractiveOpticalVfx(
        canvas: Canvas,
        width: Float,
        height: Float,
        vfx: VfxConfig,
        light: SceneLightAnalysis
    ) {
        val maxDim = max(width, height)
        val minDim = min(width, height)

        // 1. Halation en altas luces
        if (vfx.halationIntensity > 0.01f) {
            val halPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (hp in light.highlights) {
                val hx = hp.xNorm * width
                val hy = hp.yNorm * height
                val radius = minDim * (0.04f + 0.05f * vfx.halationIntensity)
                val alpha = (vfx.halationIntensity * hp.luma * 115).toInt().coerceIn(0, 190)
                halPaint.shader = RadialGradient(
                    hx, hy, radius,
                    intArrayOf(
                        Color.argb(alpha, 255, 60, 35),
                        Color.argb(alpha / 2, 235, 30, 20),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(hx, hy, radius, halPaint)
            }
        }

        // 2. Destellos Anamórficos en altas luces
        if (vfx.anamorphicIntensity > 0.01f) {
            val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val targets = if (light.highlights.isNotEmpty()) {
                light.highlights
            } else {
                listOf(HighlightPoint(light.sunX, light.sunY, 0.9f, Color.WHITE))
            }

            for (hp in targets) {
                val hx = hp.xNorm * width
                val hy = hp.yNorm * height
                val streakHalfW = width * (0.26f + 0.44f * vfx.anamorphicIntensity * hp.luma)
                val streakHalfH = (minDim * 0.010f * (0.6f + vfx.anamorphicIntensity)).coerceAtLeast(2.5f)
                val alphaCore = (vfx.anamorphicIntensity * hp.luma * 185).toInt().coerceIn(0, 225)

                val coreColor = if (vfx.anamorphicWarmColor) {
                    Color.argb(alphaCore, 255, 195, 95)
                } else {
                    Color.argb(alphaCore, 80, 205, 255)
                }
                val midColor = if (vfx.anamorphicWarmColor) {
                    Color.argb(alphaCore / 2, 255, 120, 40)
                } else {
                    Color.argb(alphaCore / 2, 35, 125, 255)
                }

                streakPaint.shader = LinearGradient(
                    hx - streakHalfW, hy, hx + streakHalfW, hy,
                    intArrayOf(Color.TRANSPARENT, midColor, coreColor, midColor, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawOval(
                    RectF(hx - streakHalfW, hy - streakHalfH, hx + streakHalfW, hy + streakHalfH),
                    streakPaint
                )
            }
        }

        // 3. Flares Solares Interactivos
        if (vfx.sunFlareIntensity > 0.01f) {
            val sx = light.sunX * width
            val sy = light.sunY * height
            val cx = width * 0.5f
            val cy = height * 0.5f
            val vx = cx - sx
            val vy = cy - sy

            val flareAlpha = (vfx.sunFlareIntensity * (0.5f + 0.5f * light.sunStrength) * 185).toInt().coerceIn(0, 220)
            val sunRadius = maxDim * (0.20f + 0.16f * vfx.sunFlareIntensity)
            val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    sx, sy, sunRadius,
                    intArrayOf(
                        Color.argb(flareAlpha, 255, 248, 220),
                        Color.argb((flareAlpha * 0.6f).toInt(), 255, 185, 95),
                        Color.argb((flareAlpha * 0.2f).toInt(), 255, 110, 50),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.22f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(sx, sy, sunRadius, sunPaint)

            val ghostMultipliers = floatArrayOf(0.45f, 0.85f, 1.25f, 1.65f)
            val ghostRadii = floatArrayOf(0.04f, 0.065f, 0.032f, 0.085f)
            val ghostColors = intArrayOf(
                Color.rgb(255, 190, 110),
                Color.rgb(110, 225, 195),
                Color.rgb(255, 120, 165),
                Color.rgb(130, 180, 255)
            )

            val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in ghostMultipliers.indices) {
                val gx = sx + vx * ghostMultipliers[i]
                val gy = sy + vy * ghostMultipliers[i]
                val gr = minDim * ghostRadii[i] * (0.7f + 0.5f * vfx.sunFlareIntensity)
                val baseCol = ghostColors[i]
                val ga = (flareAlpha * (0.50f - i * 0.08f)).toInt().coerceIn(15, 160)

                ghostPaint.shader = RadialGradient(
                    gx, gy, gr,
                    intArrayOf(
                        Color.argb(ga / 2, Color.red(baseCol), Color.green(baseCol), Color.blue(baseCol)),
                        Color.argb(ga, Color.red(baseCol), Color.green(baseCol), Color.blue(baseCol)),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(gx, gy, gr, ghostPaint)
            }
        }

        // 4. Light Leaks Interactivos
        if (vfx.lightLeakIntensity > 0.01f) {
            val lx = if (light.sunX < 0.5f) 0f else width
            val ly = (light.sunY * height).coerceIn(height * 0.1f, height * 0.9f)
            val leakRadius = maxDim * (0.45f + 0.30f * vfx.lightLeakIntensity)
            val leakAlpha = (vfx.lightLeakIntensity * 175).toInt().coerceIn(0, 215)

            val leakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    lx, ly, leakRadius,
                    intArrayOf(
                        Color.argb(leakAlpha, 255, 95, 25),
                        Color.argb((leakAlpha * 0.65f).toInt(), 255, 165, 45),
                        Color.argb((leakAlpha * 0.30f).toInt(), 220, 40, 65),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width, height, leakPaint)
        }
    }

    fun processImage(
        source: Bitmap,
        aspectMode: AspectRatioMode,
        preset: FilmPreset,
        customLut: CubeLut3D?,
        vfx: VfxConfig,
        light: SceneLightAnalysis,
        exposureBias: Float,
        disposableFlashActive: Boolean,
        isFrontSensor: Boolean = false,
        sensorAreaMm2: Float = 25f,
        metadataStamp: String
    ): Bitmap {
        val croppedSource = cropToAspectRatio(source, aspectMode)
        val width = croppedSource.width
        val height = croppedSource.height

        val workingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workingBitmap)

        // 1. Matriz de Color calibrada según el sensor + Exposición + Temperatura y Tinte suaves
        val adaptedMatrix = buildSensorAdaptedMatrix(
            preset = preset,
            isFrontSensor = isFrontSensor,
            sensorAreaMm2 = sensorAreaMm2,
            exposureBias = exposureBias,
            temperature = vfx.temperature,
            tint = vfx.tint
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(adaptedMatrix)
        }
        canvas.drawBitmap(croppedSource, 0f, 0f, paint)

        // 2. Aplicar 3D LUT (.cube) con mezcla adaptativa
        if (customLut != null && !preset.isNoLut) {
            val lutStrength = if (isFrontSensor) 0.55f else 0.80f
            val pixels = IntArray(width * height)
            workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                pixels[i] = customLut.lookup(r, g, b, lutStrength)
            }
            workingBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // 3. Flash de cámara desechable
        if (disposableFlashActive || preset.disposableFlashLook) {
            val flashGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.65f,
                intArrayOf(
                    Color.argb(42, 255, 248, 225),
                    Color.argb(0, 0, 0, 0),
                    Color.argb(120, 8, 18, 14)
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            val flashPaint = Paint().apply {
                shader = flashGradient
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }

        // 4. Viñeteo sutil si aplica
        if (preset.vignette > 0.01f && !preset.isNoLut) {
            val vigScale = if (isFrontSensor) 0.55f else 1.0f
            val vigAlpha = (preset.vignette * vigScale * 150).toInt().coerceIn(0, 220)
            val vigGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.74f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(vigAlpha, 0, 0, 0)),
                floatArrayOf(0f, 0.60f, 1f),
                Shader.TileMode.CLAMP
            )
            val vigPaint = Paint().apply { shader = vigGradient }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vigPaint)
        }

        // 5. VFX Ópticos Interactivos
        drawInteractiveOpticalVfx(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            vfx = vfx,
            light = light
        )

        // 6. Grano fino controlado (sin exceso de ruido en cámara frontal)
        val sensorGrainFactor = if (isFrontSensor) 0.55f else 0.85f
        val effectiveGrain = (vfx.grainIntensity * sensorGrainFactor).coerceIn(0f, 0.45f)
        val effectiveNoise = (vfx.noiseIntensity * sensorGrainFactor).coerceIn(0f, 0.25f)

        if ((effectiveGrain > 0.01f || effectiveNoise > 0.01f) && !preset.isNoLut) {
            val overlayW = (width / 2).coerceIn(256, 640)
            val overlayH = (height / 2).coerceIn(256, 640)
            val grainPixels = IntArray(overlayW * overlayH)
            val grainAmp = (effectiveGrain * 62).toInt()
            val chromaAmp = (effectiveNoise * 48).toInt()
            val rnd = Random(System.currentTimeMillis())

            for (i in grainPixels.indices) {
                val mono = if (grainAmp > 0) rnd.nextInt(-grainAmp, grainAmp) else 0
                val cr = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cg = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cb = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0

                val r = (128 + mono + cr).coerceIn(0, 255)
                val g = (128 + mono + cg).coerceIn(0, 255)
                val b = (128 + mono + cb).coerceIn(0, 255)
                val alpha = ((effectiveGrain + effectiveNoise).coerceAtMost(0.6f) * 68).toInt()
                grainPixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }

            val grainBmp = Bitmap.createBitmap(grainPixels, overlayW, overlayH, Bitmap.Config.ARGB_8888)
            val grainPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.OVERLAY)
            }
            canvas.drawBitmap(
                grainBmp,
                Rect(0, 0, overlayW, overlayH),
                Rect(0, 0, width, height),
                grainPaint
            )
            grainBmp.recycle()
        }

        if (croppedSource !== source) {
            croppedSource.recycle()
        }

        // 7. Marco Polaroid si está activo
        if (!vfx.polaroidFrame) return workingBitmap

        val sideBorder = (min(width, height) * 0.065f).toInt()
        val topBorder = sideBorder
        val bottomBorder = (min(width, height) * 0.22f).toInt()

        val polaroidW = width + sideBorder * 2
        val polaroidH = height + topBorder + bottomBorder
        val polaroidBitmap = Bitmap.createBitmap(polaroidW, polaroidH, Bitmap.Config.ARGB_8888)
        val pCanvas = Canvas(polaroidBitmap)

        pCanvas.drawColor(Color.rgb(247, 245, 238))

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 212, 204)
            style = Paint.Style.STROKE
            strokeWidth = (sideBorder * 0.08f).coerceAtLeast(2f)
        }
        pCanvas.drawBitmap(workingBitmap, sideBorder.toFloat(), topBorder.toFloat(), null)
        pCanvas.drawRect(
            sideBorder.toFloat(),
            topBorder.toFloat(),
            (sideBorder + width).toFloat(),
            (topBorder + height).toFloat(),
            shadowPaint
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 92, 86)
            textSize = bottomBorder * 0.15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
        }
        pCanvas.drawText(
            metadataStamp,
            polaroidW / 2f,
            topBorder + height + (bottomBorder * 0.58f),
            textPaint
        )

        workingBitmap.recycle()
        return polaroidBitmap
    }

    fun cropToAspectRatio(source: Bitmap, aspectMode: AspectRatioMode): Bitmap {
        val srcW = source.width
        val srcH = source.height
        val isSourceLandscape = srcW > srcH
        val targetRatio = if (isSourceLandscape) {
            1f / aspectMode.ratioPortrait
        } else {
            aspectMode.ratioPortrait
        }

        val currentRatio = srcW.toFloat() / srcH.toFloat()
        if (abs(currentRatio - targetRatio) < 0.02f) return source

        val cropW: Int
        val cropH: Int
        if (currentRatio > targetRatio) {
            cropH = srcH
            cropW = (srcH * targetRatio).toInt().coerceIn(1, srcW)
        } else {
            cropW = srcW
            cropH = (srcW / targetRatio).toInt().coerceIn(1, srcH)
        }
        val xOff = (srcW - cropW) / 2
        val yOff = (srcH - cropH) / 2
        return Bitmap.createBitmap(source, xOff, yOff, cropW, cropH)
    }

    /**
     * Generador de Collage Cinematográfico (Cinta 8mm, 35mm, 75mm, Película 21:9, Escena de Película)
     */
    fun generateCinemaCollage(
        bitmaps: List<Bitmap>,
        template: CollageTemplate,
        preset: FilmPreset,
        temperature: Float,
        tint: Float,
        movieSubtitle: String = "“Hay momentos que solo existen en celuloide.”"
    ): Bitmap {
        val count = bitmaps.size.coerceAtLeast(1)
        val outW = 1600
        val frameH = when (template) {
            CollageTemplate.SCOPE_21_9 -> 620
            CollageTemplate.MOVIE_SCENE -> 760
            CollageTemplate.FILM_75MM -> 720
            else -> 880
        }
        val totalH = (frameH * count).coerceIn(900, 4800)

        val outBmp = Bitmap.createBitmap(outW, totalH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outBmp)
        canvas.drawColor(Color.rgb(10, 10, 12))

        val colorMatrix = buildSensorAdaptedMatrix(
            preset = preset,
            isFrontSensor = false,
            exposureBias = 0f,
            temperature = temperature,
            tint = tint
        )
        val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(238, 236, 230)
        }
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(235, 165, 45)
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        bitmaps.forEachIndexed { index, bmp ->
            val topY = index * frameH.toFloat()
            val botY = topY + frameH

            val padLeft: Float
            val padRight: Float
            val padTop: Float
            val padBot: Float

            when (template) {
                CollageTemplate.FILM_8MM -> {
                    // Cinta Super 8mm: perforaciones grandes sólo en el borde izquierdo y cálido marco analógico
                    padLeft = 175f
                    padRight = 55f
                    padTop = 32f
                    padBot = 32f
                    val holeY1 = topY + frameH * 0.25f
                    val holeY2 = topY + frameH * 0.75f
                    canvas.drawRoundRect(RectF(48f, holeY1 - 42f, 118f, holeY1 + 42f), 14f, 14f, holePaint)
                    canvas.drawRoundRect(RectF(48f, holeY2 - 42f, 118f, holeY2 + 42f), 14f, 14f, holePaint)
                    canvas.drawText("SUPER 8MM", 28f, topY + frameH * 0.52f, codePaint.apply { textSize = 20f })
                }

                CollageTemplate.FILM_35MM -> {
                    // Cinta 35mm: doble hilera de perforaciones a ambos lados + numeración de fotograma
                    padLeft = 150f
                    padRight = 150f
                    padTop = 26f
                    padBot = 26f
                    val numHoles = 8
                    val step = frameH.toFloat() / numHoles
                    for (hIdx in 0 until numHoles) {
                        val hy = topY + hIdx * step + step * 0.22f
                        canvas.drawRoundRect(RectF(42f, hy, 96f, hy + step * 0.55f), 8f, 8f, holePaint)
                        canvas.drawRoundRect(RectF(outW - 96f, hy, outW - 42f, hy + step * 0.55f), 8f, 8f, holePaint)
                    }
                    codePaint.textSize = 24f
                    canvas.drawText("${index + 24}A", 104f, topY + frameH - 36f, codePaint)
                    canvas.drawText(preset.brand, outW - 142f, topY + 52f, codePaint)
                }

                CollageTemplate.FILM_75MM -> {
                    // Cinta 75mm IMAX: formato horizontal ancho con perforaciones finas superiores/laterales y marcas doradas
                    padLeft = 110f
                    padRight = 110f
                    padTop = 48f
                    padBot = 48f
                    val numHoles = 10
                    val step = frameH.toFloat() / numHoles
                    for (hIdx in 0 until numHoles) {
                        val hy = topY + hIdx * step + step * 0.25f
                        canvas.drawRoundRect(RectF(32f, hy, 72f, hy + step * 0.5f), 6f, 6f, holePaint)
                        canvas.drawRoundRect(RectF(outW - 72f, hy, outW - 32f, hy + step * 0.5f), 6f, 6f, holePaint)
                    }
                    codePaint.textSize = 22f
                    canvas.drawText("75MM LARGE FORMAT  •  CELL #${index + 1}", 115f, topY + 34f, codePaint)
                }

                CollageTemplate.SCOPE_21_9 -> {
                    // Película 21:9 Anamórfica apilada con finos bordes negros
                    padLeft = 40f
                    padRight = 40f
                    padTop = 18f
                    padBot = 18f
                }

                CollageTemplate.MOVIE_SCENE -> {
                    // Escena de Película con barras Letterbox 2.35:1 y subtítulo cinematográfico
                    padLeft = 36f
                    padRight = 36f
                    padTop = 75f
                    padBot = 95f
                }
            }

            val destRect = RectF(padLeft, topY + padTop, outW - padRight, botY - padBot)
            drawCenterCropBitmap(canvas, bmp, destRect, photoPaint)

            if (template == CollageTemplate.MOVIE_SCENE && index == bitmaps.lastIndex) {
                val subStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 42f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    textAlign = Paint.Align.CENTER
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                val subFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 232, 110)
                    textSize = 42f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    textAlign = Paint.Align.CENTER
                }
                val subY = destRect.bottom - 38f
                canvas.drawText(movieSubtitle, outW / 2f, subY, subStroke)
                canvas.drawText(movieSubtitle, outW / 2f, subY, subFill)
            }
        }

        return outBmp
    }

    private fun drawCenterCropBitmap(canvas: Canvas, bmp: Bitmap, dest: RectF, paint: Paint) {
        val destW = dest.width()
        val destH = dest.height()
        val srcRatio = bmp.width.toFloat() / bmp.height.toFloat()
        val dstRatio = destW / destH

        val srcRect = if (srcRatio > dstRatio) {
            val cropW = (bmp.height * dstRatio).toInt()
            val xOff = (bmp.width - cropW) / 2
            Rect(xOff, 0, xOff + cropW, bmp.height)
        } else {
            val cropH = (bmp.width / dstRatio).toInt()
            val yOff = (bmp.height - cropH) / 2
            Rect(0, yOff, bmp.width, yOff + cropH)
        }
        canvas.drawBitmap(bmp, srcRect, dest, paint)
    }
}
