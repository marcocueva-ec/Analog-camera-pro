package com.analogpro.camera

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.*
import kotlin.random.Random

class CubeLut3D(val name: String, val size: Int, val table: FloatArray) {
    fun lookup(r: Int, g: Int, b: Int): Int {
        val rf = (r / 255f) * (size - 1)
        val gf = (g / 255f) * (size - 1)
        val bf = (b / 255f) * (size - 1)

        val ri = rf.toInt().coerceIn(0, size - 1)
        val gi = gf.toInt().coerceIn(0, size - 1)
        val bi = bf.toInt().coerceIn(0, size - 1)

        val idx = ((bi * size * size) + (gi * size) + ri) * 3
        if (idx + 2 >= table.size) return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        val outR = (table[idx] * 255f).toInt().coerceIn(0, 255)
        val outG = (table[idx + 1] * 255f).toInt().coerceIn(0, 255)
        val outB = (table[idx + 2] * 255f).toInt().coerceIn(0, 255)
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

object AnalogImageProcessor {

    /**
     * Analiza un frame en vivo de la cámara para detectar:
     * 1. Posición real del sol / fuente principal de luz (para que Flares y Light Leaks interactúen con la luz real).
     * 2. Puntos de altas luces (especulares) donde se anclan los destellos Anamórficos y Halation en tiempo real.
     * 3. Nivel de luz ambiente (para detectar Baja Luz y sugerir activar el Flash).
     */
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

        // Filtrar puntos destacados bien separados para que los Flares Anamórficos se vean limpios y cinematográficos
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

    /**
     * Dibuja los efectos ópticos interactivos (Flares solares, Light Leaks direccionales,
     * Flares Anamórficos en altas luces y Halation) tanto sobre el Canvas en tiempo real
     * como sobre la fotografía final.
     */
    fun drawInteractiveOpticalVfx(
        canvas: Canvas,
        width: Float,
        height: Float,
        vfx: VfxConfig,
        light: SceneLightAnalysis,
        focusXNorm: Float,
        focusYNorm: Float
    ) {
        val maxDim = max(width, height)
        val minDim = min(width, height)

        // 1. HALATION (Resplandor cálido rojizo alrededor de las altas luces reales)
        if (vfx.halationIntensity > 0.01f) {
            val halPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (hp in light.highlights) {
                val hx = hp.xNorm * width
                val hy = hp.yNorm * height
                val radius = minDim * (0.045f + 0.06f * vfx.halationIntensity)
                val alpha = (vfx.halationIntensity * hp.luma * 135).toInt().coerceIn(0, 220)
                halPaint.shader = RadialGradient(
                    hx, hy, radius,
                    intArrayOf(
                        Color.argb(alpha, 255, 55, 30),
                        Color.argb(alpha / 2, 235, 30, 20),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(hx, hy, radius, halPaint)
            }
        }

        // 2. ANAMORPHIC LIGHT LEAKS / STREAKS (Interactúan directamente con cada punto de alta luz)
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
                val streakHalfW = width * (0.28f + 0.48f * vfx.anamorphicIntensity * hp.luma)
                val streakHalfH = (minDim * 0.012f * (0.6f + vfx.anamorphicIntensity)).coerceAtLeast(3f)
                val alphaCore = (vfx.anamorphicIntensity * hp.luma * 210).toInt().coerceIn(0, 245)

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

                // Núcleo brillante elíptico en el punto de alta luz
                val bloomR = streakHalfH * 4.2f
                val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        hx, hy, bloomR,
                        intArrayOf(Color.argb((alphaCore * 0.8f).toInt(), 220, 245, 255), Color.TRANSPARENT),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(hx, hy, bloomR, bloomPaint)
            }
        }

        // 3. INTERACTIVE SUN FLARES (Eje óptico real desde la posición del sol pasando por el centro del lente)
        if (vfx.sunFlareIntensity > 0.01f) {
            val sx = light.sunX * width
            val sy = light.sunY * height
            val cx = width * 0.5f
            val cy = height * 0.5f
            val vx = cx - sx
            val vy = cy - sy

            val flareAlpha = (vfx.sunFlareIntensity * (0.55f + 0.45f * light.sunStrength) * 210).toInt().coerceIn(0, 240)

            // Halo solar principal en la coordenada de la fuente de luz
            val sunRadius = maxDim * (0.22f + 0.18f * vfx.sunFlareIntensity)
            val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    sx, sy, sunRadius,
                    intArrayOf(
                        Color.argb(flareAlpha, 255, 248, 220),
                        Color.argb((flareAlpha * 0.65f).toInt(), 255, 185, 95),
                        Color.argb((flareAlpha * 0.25f).toInt(), 255, 110, 50),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.22f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(sx, sy, sunRadius, sunPaint)

            // Fantasmas ópticos multicapa proyectados a lo largo del vector solar
            val ghostMultipliers = floatArrayOf(0.45f, 0.85f, 1.25f, 1.65f, 2.05f)
            val ghostRadii = floatArrayOf(0.04f, 0.07f, 0.032f, 0.095f, 0.055f)
            val ghostColors = intArrayOf(
                Color.rgb(255, 190, 110),
                Color.rgb(110, 225, 195),
                Color.rgb(255, 120, 165),
                Color.rgb(130, 180, 255),
                Color.rgb(255, 220, 130)
            )

            val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in ghostMultipliers.indices) {
                val gx = sx + vx * ghostMultipliers[i]
                val gy = sy + vy * ghostMultipliers[i]
                val gr = minDim * ghostRadii[i] * (0.7f + 0.5f * vfx.sunFlareIntensity)
                val baseCol = ghostColors[i]
                val ga = (flareAlpha * (0.55f - i * 0.07f)).toInt().coerceIn(20, 180)

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

        // 4. INTERACTIVE LIGHT LEAKS (Fugas de luz orgánicas que entran desde el borde más cercano al sol/luz)
        if (vfx.lightLeakIntensity > 0.01f) {
            val lx = if (light.sunX < 0.5f) 0f else width
            val ly = (light.sunY * height).coerceIn(height * 0.1f, height * 0.9f)
            val leakRadius = maxDim * (0.48f + 0.35f * vfx.lightLeakIntensity)
            val leakAlpha = (vfx.lightLeakIntensity * 195).toInt().coerceIn(0, 235)

            val leakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    lx, ly, leakRadius,
                    intArrayOf(
                        Color.argb(leakAlpha, 255, 95, 25),
                        Color.argb((leakAlpha * 0.7f).toInt(), 255, 165, 45),
                        Color.argb((leakAlpha * 0.35f).toInt(), 220, 40, 65),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width, height, leakPaint)

            // Segunda cortina orgánica en el extremo opuesto
            val lx2 = width - lx
            val ly2 = height - ly
            val leakPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    lx2, ly2, leakRadius * 0.72f,
                    intArrayOf(
                        Color.argb((leakAlpha * 0.65f).toInt(), 255, 180, 60),
                        Color.argb((leakAlpha * 0.25f).toInt(), 255, 70, 30),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width, height, leakPaint2)
        }
    }

    /**
     * Procesa la fotografía final (o del editor de galería) aplicando:
     * - Recorte exacto al Aspect Ratio elegido (16:9, 4:3, OpenGate) SIN deformar ni estirar jamás la imagen.
     * - Emulación de Película (Fujifilm / Kodak / CineStill / Desechables) + Exposición.
     * - 3D LUT (.cube).
     * - Desenfoque de Escena / Bokeh para objetos cercanos respetando el punto de enfoque (focusX, focusY).
     * - VFX Interactivos (Sun Flares, Light Leaks direccionales, Anamorphic Highlight Streaks, Halation).
     * - Grano de haluro de plata y ruido cromático.
     * - Marco Polaroid opcional.
     */
    fun processImage(
        source: Bitmap,
        aspectMode: AspectRatioMode,
        preset: FilmPreset,
        customLut: CubeLut3D?,
        vfx: VfxConfig,
        light: SceneLightAnalysis,
        exposureBias: Float,
        disposableFlashActive: Boolean,
        focusXNorm: Float = 0.5f,
        focusYNorm: Float = 0.5f,
        metadataStamp: String
    ): Bitmap {
        // 1. Recorte limpio según el formato (16:9, 4:3, OpenGate) sin deformación
        val croppedSource = cropToAspectRatio(source, aspectMode)
        val width = croppedSource.width
        val height = croppedSource.height

        val workingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(workingBitmap)

        // 2. Matriz de Color del Film + Compensación de Exposición
        val expScale = 2.0f.pow(exposureBias)
        val expMatrix = ColorMatrix(
            floatArrayOf(
                expScale, 0f, 0f, 0f, 0f,
                0f, expScale, 0f, 0f, 0f,
                0f, 0f, expScale, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val filmMatrix = ColorMatrix(preset.matrix)
        filmMatrix.preConcat(expMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filmMatrix)
        }
        canvas.drawBitmap(croppedSource, 0f, 0f, paint)

        // 3. Aplicar 3D LUT (.cube) si está seleccionado
        if (customLut != null) {
            val pixels = IntArray(width * height)
            workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                pixels[i] = customLut.lookup(r, g, b)
            }
            workingBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }

        // 4. Desenfoque de Escena (Bokeh Macro alrededor del punto de enfoque tocado)
        if (vfx.sceneBlurBokeh > 0.02f) {
            val downFactor = (4 + (vfx.sceneBlurBokeh * 10).toInt()).coerceIn(4, 16)
            val smallW = (width / downFactor).coerceAtLeast(32)
            val smallH = (height / downFactor).coerceAtLeast(32)
            val tiny = Bitmap.createScaledBitmap(workingBitmap, smallW, smallH, true)
            val blurred = Bitmap.createScaledBitmap(tiny, width, height, true)
            tiny.recycle()

            val fx = focusXNorm * width
            val fy = focusYNorm * height
            val sharpRadius = min(width, height) * (0.42f - 0.18f * vfx.sceneBlurBokeh)

            val maskBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBmp)
            maskCanvas.drawBitmap(blurred, 0f, 0f, null)

            val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                shader = RadialGradient(
                    fx, fy, sharpRadius,
                    intArrayOf(Color.BLACK, Color.argb(180, 0, 0, 0), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            maskCanvas.drawCircle(fx, fy, sharpRadius, erasePaint)
            canvas.drawBitmap(maskBmp, 0f, 0f, null)
            blurred.recycle()
            maskBmp.recycle()
        }

        // 5. Flash de Cámara Desechable (centro cálido + caída de luz)
        if (disposableFlashActive || preset.disposableFlashLook) {
            val flashGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.65f,
                intArrayOf(
                    Color.argb(55, 255, 248, 225),
                    Color.argb(0, 0, 0, 0),
                    Color.argb(150, 8, 18, 14)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val flashPaint = Paint().apply {
                shader = flashGradient
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        }

        // 6. Viñeteo Óptico del Film
        if (preset.vignette > 0.01f) {
            val vigAlpha = (preset.vignette * 185).toInt().coerceIn(0, 255)
            val vigGradient = RadialGradient(
                width / 2f, height / 2f, max(width, height) * 0.72f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(vigAlpha, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val vigPaint = Paint().apply { shader = vigGradient }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vigPaint)
        }

        // 7. Dibujar VFX Ópticos Interactivos (Sun Flares, Light Leaks, Anamorphic Streaks, Halation)
        drawInteractiveOpticalVfx(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            vfx = vfx,
            light = light,
            focusXNorm = focusXNorm,
            focusYNorm = focusYNorm
        )

        // 8. Grano de Película y Ruido Cromático
        if (vfx.grainIntensity > 0.01f || vfx.noiseIntensity > 0.01f) {
            val overlayW = (width / 2).coerceIn(256, 720)
            val overlayH = (height / 2).coerceIn(256, 720)
            val grainPixels = IntArray(overlayW * overlayH)
            val grainAmp = (vfx.grainIntensity * 95).toInt()
            val chromaAmp = (vfx.noiseIntensity * 85).toInt()
            val rnd = Random(System.currentTimeMillis())

            for (i in grainPixels.indices) {
                val mono = if (grainAmp > 0) rnd.nextInt(-grainAmp, grainAmp) else 0
                val cr = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cg = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0
                val cb = if (chromaAmp > 0) rnd.nextInt(-chromaAmp, chromaAmp) else 0

                val r = (128 + mono + cr).coerceIn(0, 255)
                val g = (128 + mono + cg).coerceIn(0, 255)
                val b = (128 + mono + cb).coerceIn(0, 255)
                val alpha = ((vfx.grainIntensity + vfx.noiseIntensity).coerceAtMost(1f) * 95).toInt()
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

        // 9. Marco Polaroid Opcional (Configurable en VFX)
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

    private fun cropToAspectRatio(source: Bitmap, aspectMode: AspectRatioMode): Bitmap {
        if (aspectMode == AspectRatioMode.OPEN_GATE) return source
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
}
