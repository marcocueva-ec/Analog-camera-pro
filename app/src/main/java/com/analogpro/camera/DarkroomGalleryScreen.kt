package com.analogpro.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DarkroomGalleryScreen(
    persistedMedia: List<PersistedMediaItem>,
    onRefreshGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. Organización automática: TODOS | FOTOS | VIDEOS
    var mediaFilter by remember { mutableStateOf(GalleryMediaFilter.ALL) }
    val filteredMedia = remember(persistedMedia, mediaFilter) {
        when (mediaFilter) {
            GalleryMediaFilter.ALL -> persistedMedia
            GalleryMediaFilter.PHOTOS -> persistedMedia.filter { !it.isVideo }
            GalleryMediaFilter.VIDEOS -> persistedMedia.filter { it.isVideo }
        }
    }

    // 2. Selección múltiple para Borrar / Compartir / Crear Collage
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    // 3. Visor deslizable izquierda/derecha (HorizontalPager) y Modo Editor
    var activePagerIndex by remember { mutableStateOf<Int?>(null) }
    var isEditingMode by remember { mutableStateOf(false) }
    var showCollageCreator by remember { mutableStateOf(false) }

    // Estado del Editor (restaurado desde la metadata EXIF cuando se abre una foto)
    var editingBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editingDisplayName by remember { mutableStateOf<String?>(null) }
    var editingTargetUri by remember { mutableStateOf<Uri?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var activePreset by remember { mutableStateOf(BUILT_IN_PRESETS[0]) }
    var activeLut by remember { mutableStateOf<CubeLut3D?>(null) }
    var editVfx by remember { mutableStateOf(VfxConfig()) }
    var editEv by remember { mutableStateOf(0f) }
    var editAspect by remember { mutableStateOf(AspectRatioMode.RATIO_16_9) }
    var customSunX by remember { mutableStateOf(0.75f) }
    var customSunY by remember { mutableStateOf(0.25f) }
    var editorSubTab by remember { mutableStateOf(0) } // 0=Films, 1=Temp/Tinte/EV, 2=VFX

    // Importar foto externa de la galería del teléfono para editarla
    val externalPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bmp = MediaStoreRepository.loadFullBitmap(context, uri, 2560)
                val recipe = MediaStoreRepository.loadRecipeFromUri(context, uri)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        editingBaseBitmap = bmp
                        editingDisplayName = "ANALOG_IMPORT_${System.currentTimeMillis()}.jpg"
                        editingTargetUri = null
                        if (recipe != null) {
                            activePreset = BUILT_IN_PRESETS.firstOrNull { it.id == recipe.presetId } ?: BUILT_IN_PRESETS[0]
                            editEv = recipe.ev
                            editVfx = VfxConfig(
                                sunFlareIntensity = recipe.flare,
                                lightLeakIntensity = recipe.leak,
                                anamorphicIntensity = recipe.anamorphic,
                                halationIntensity = recipe.halation,
                                grainIntensity = recipe.grain,
                                noiseIntensity = recipe.noise,
                                temperature = recipe.temperature,
                                tint = recipe.tint,
                                polaroidFrame = recipe.polaroid
                            )
                        } else {
                            activePreset = BUILT_IN_PRESETS[1]
                            editEv = 0f
                            editVfx = VfxConfig()
                        }
                        isEditingMode = true
                    }
                }
            }
        }
    }

    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val parsed = CubeLutParser.parseUri(context, uri, "Custom .CUBE")
                withContext(Dispatchers.Main) {
                    if (parsed != null) activeLut = parsed
                }
            }
        }
    }

    // Recalcular vista previa del Editor en tiempo real
    LaunchedEffect(
        editingBaseBitmap, isEditingMode, activePreset, activeLut,
        editVfx, editEv, editAspect, customSunX, customSunY
    ) {
        val src = editingBaseBitmap ?: return@LaunchedEffect
        if (!isEditingMode) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val scale = 960f / maxOf(src.width, src.height).coerceAtLeast(1)
            val sw = (src.width * scale).toInt().coerceAtLeast(120)
            val sh = (src.height * scale).toInt().coerceAtLeast(120)
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)

            val smallAnalyze = Bitmap.createScaledBitmap(scaled, 64, 64, true)
            val baseAnalysis = AnalogImageProcessor.analyzeSceneLight(smallAnalyze).copy(
                sunX = customSunX,
                sunY = customSunY
            )
            smallAnalyze.recycle()

            val result = AnalogImageProcessor.processImage(
                source = scaled,
                aspectMode = editAspect,
                preset = activePreset,
                customLut = activeLut,
                vfx = editVfx,
                light = baseAnalysis,
                exposureBias = editEv,
                disposableFlashActive = false,
                isFrontSensor = false,
                metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM"
            )
            withContext(Dispatchers.Main) {
                previewBitmap = result
            }
        }
    }

    // Si está abierto el Creador de Collages (8mm, 35mm, 75mm, 21:9, Película)
    if (showCollageCreator) {
        CollageStudioModal(
            appPhotos = persistedMedia.filter { !it.isVideo },
            preselectedUris = selectedUris.toList(),
            onSaved = {
                onRefreshGallery()
                showCollageCreator = false
            },
            onCancel = { showCollageCreator = false }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E))
            .padding(12.dp)
    ) {
        // BARRA SUPERIOR DE GALERÍA
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    when {
                        isEditingMode -> {
                            isEditingMode = false
                            editingBaseBitmap = null
                        }
                        activePagerIndex != null -> {
                            activePagerIndex = null
                        }
                        isSelectionMode -> {
                            isSelectionMode = false
                            selectedUris.clear()
                        }
                        else -> onClose()
                    }
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (isEditingMode || activePagerIndex != null) "GALERÍA" else "CÁMARA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { showCollageCreator = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.AutoAwesomeMosaic, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("CREAR COLLAGE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { externalPhotoPicker.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22252C)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("IMPORTAR", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // ====================================================================
        // MODO 1: EDITOR NO DESTRUCTIVO CON METADATA EXIF + TEMPERATURA Y TINTE
        // ====================================================================
        if (isEditingMode && editingBaseBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            customSunX = (pos.x / size.width.toFloat()).coerceIn(0.02f, 0.98f)
                            customSunY = (pos.y / size.height.toFloat()).coerceIn(0.02f, 0.98f)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Previsualización de edición",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = "Metadata cargada • Toca la imagen para orientar el Sol / Flare",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14171E), RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = editorSubTab == 0,
                        onClick = { editorSubTab = 0 },
                        label = { Text("FILMS & LUTS", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = editorSubTab == 1,
                        onClick = { editorSubTab = 1 },
                        label = { Text("TEMP / TINTE / EV", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = editorSubTab == 2,
                        onClick = { editorSubTab = 2 },
                        label = { Text("VFX / GRANO", fontSize = 11.sp) }
                    )
                }

                Spacer(Modifier.height(6.dp))

                when (editorSubTab) {
                    0 -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(BUILT_IN_PRESETS) { preset ->
                                val repBmp = remember(preset.id) { FilmThumbnailGenerator.getRepresentativeThumbnail(preset) }
                                val isSel = activePreset.id == preset.id
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(92.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) Color(0xFFD4AF37) else Color(0xFF22252A))
                                        .clickable {
                                            activePreset = preset
                                            editVfx = editVfx.copy(
                                                grainIntensity = preset.defaultGrain,
                                                noiseIntensity = preset.defaultNoise
                                            )
                                        }
                                        .padding(4.dp)
                                ) {
                                    Image(
                                        bitmap = repBmp.asImageBitmap(),
                                        contentDescription = preset.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(6.dp))
                                    )
                                    Text(
                                        preset.brand,
                                        color = if (isSel) Color.Black else Color.LightGray,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        preset.name,
                                        color = if (isSel) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(BUILT_IN_ASSET_LUTS) { lutFile ->
                                AssistChip(
                                    onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            val parsed = CubeLutParser.parseAsset(context, lutFile)
                                            withContext(Dispatchers.Main) { activeLut = parsed }
                                        }
                                    },
                                    label = { Text(lutFile.removeSuffix(".cube").replace("_", " "), fontSize = 10.sp) }
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = { lutPicker.launch("*/*") },
                                    label = { Text("+ .CUBE", fontSize = 10.sp) }
                                )
                            }
                            if (activeLut != null) {
                                item {
                                    TextButton(onClick = { activeLut = null }) {
                                        Text("Sin LUT", color = Color(0xFFE53935), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        Column(modifier = Modifier.heightIn(max = 175.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                "Temperatura de Color (Suave): ${String.format(Locale.US, "%+.2f", editVfx.temperature)}",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Slider(
                                value = editVfx.temperature,
                                onValueChange = { editVfx = editVfx.copy(temperature = it) },
                                valueRange = -1.0f..1.0f
                            )

                            Text(
                                "Tinte Verde / Magenta (Suave): ${String.format(Locale.US, "%+.2f", editVfx.tint)}",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Slider(
                                value = editVfx.tint,
                                onValueChange = { editVfx = editVfx.copy(tint = it) },
                                valueRange = -1.0f..1.0f
                            )

                            Text(
                                "Exposición: ${String.format(Locale.US, "%+.1f EV", editEv)}",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Slider(value = editEv, onValueChange = { editEv = it }, valueRange = -2.0f..2.0f)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AspectRatioMode.values().forEach { mode ->
                                    FilterChip(
                                        selected = editAspect == mode,
                                        onClick = { editAspect = mode },
                                        label = { Text(mode.label, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        Column(modifier = Modifier.heightIn(max = 175.dp).verticalScroll(rememberScrollState())) {
                            Text("Flare Solar Interactivo: ${(editVfx.sunFlareIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(value = editVfx.sunFlareIntensity, onValueChange = { editVfx = editVfx.copy(sunFlareIntensity = it) })

                            Text("Light Leaks Direccional: ${(editVfx.lightLeakIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(value = editVfx.lightLeakIntensity, onValueChange = { editVfx = editVfx.copy(lightLeakIntensity = it) })

                            Text("Destellos Anamórficos (Altas Luces): ${(editVfx.anamorphicIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(value = editVfx.anamorphicIntensity, onValueChange = { editVfx = editVfx.copy(anamorphicIntensity = it) })

                            Text("Grano Sutil: ${(editVfx.grainIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                            Slider(value = editVfx.grainIntensity, onValueChange = { editVfx = editVfx.copy(grainIntensity = it.coerceIn(0f, 0.45f)) })

                            FilterChip(
                                selected = editVfx.polaroidFrame,
                                onClick = { editVfx = editVfx.copy(polaroidFrame = !editVfx.polaroidFrame) },
                                label = { Text("Marco Polaroid", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { isEditingMode = false }) {
                        Text("CANCELAR", color = Color.Gray)
                    }
                    Button(
                        onClick = {
                            val orig = editingBaseBitmap ?: return@Button
                            scope.launch(Dispatchers.IO) {
                                val small = Bitmap.createScaledBitmap(orig, 64, 64, true)
                                val baseLight = AnalogImageProcessor.analyzeSceneLight(small).copy(
                                    sunX = customSunX,
                                    sunY = customSunY
                                )
                                small.recycle()

                                val finalBmp = AnalogImageProcessor.processImage(
                                    source = orig,
                                    aspectMode = editAspect,
                                    preset = activePreset,
                                    customLut = activeLut,
                                    vfx = editVfx,
                                    light = baseLight,
                                    exposureBias = editEv,
                                    disposableFlashActive = false,
                                    isFrontSensor = false,
                                    metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM"
                                )

                                val recipe = PhotoEditRecipe(
                                    presetId = activePreset.id,
                                    ev = editEv,
                                    temperature = editVfx.temperature,
                                    tint = editVfx.tint,
                                    grain = editVfx.grainIntensity,
                                    noise = editVfx.noiseIntensity,
                                    flare = editVfx.sunFlareIntensity,
                                    leak = editVfx.lightLeakIntensity,
                                    anamorphic = editVfx.anamorphicIntensity,
                                    halation = editVfx.halationIntensity,
                                    polaroid = editVfx.polaroidFrame,
                                    aspectName = editAspect.name
                                )

                                // Si estamos re-editando una foto de la app, actualizamos su archivo y su metadata EXIF
                                val existingUri = editingTargetUri
                                val dispName = editingDisplayName ?: "ANALOG_${System.currentTimeMillis()}.jpg"
                                if (existingUri != null) {
                                    context.contentResolver.openOutputStream(existingUri, "wt")?.use { out ->
                                        finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                    }
                                    MediaStoreRepository.saveOriginalBackupAndExifRecipe(context, existingUri, dispName, orig, recipe)
                                } else {
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, dispName)
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                                        }
                                    }
                                    val newUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (newUri != null) {
                                        context.contentResolver.openOutputStream(newUri)?.use { out ->
                                            finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                        }
                                        MediaStoreRepository.saveOriginalBackupAndExifRecipe(context, newUri, dispName, orig, recipe)
                                    }
                                }

                                finalBmp.recycle()
                                withContext(Dispatchers.Main) {
                                    onRefreshGallery()
                                    Toast.makeText(context, "Guardada con Metadata EXIF re-editable", Toast.LENGTH_SHORT).show()
                                    isEditingMode = false
                                    editingBaseBitmap = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                    ) {
                        Text("GUARDAR CON METADATA", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
            return@Column
        }

        // ====================================================================
        // MODO 2: VISOR DE PANTALLA COMPLETA DESLIZABLE IZQUIERDA/DERECHA
        // ====================================================================
        if (activePagerIndex != null && filteredMedia.isNotEmpty()) {
            val initialPage = activePagerIndex!!.coerceIn(0, filteredMedia.lastIndex)
            val pagerState = rememberPagerState(initialPage = initialPage) { filteredMedia.size }
            val currentItem = filteredMedia[pagerState.currentPage.coerceIn(0, filteredMedia.lastIndex)]
            var videoPreviewPreset by remember { mutableStateOf(BUILT_IN_PRESETS[0]) }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)
            ) { page ->
                val item = filteredMedia[page]
                var zoomScale by remember(page) { mutableStateOf(1f) }
                var offsetX by remember(page) { mutableStateOf(0f) }
                var offsetY by remember(page) { mutableStateOf(0f) }
                var fullBmp by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(item.uri) {
                    if (!item.isVideo) {
                        withContext(Dispatchers.IO) {
                            val b = MediaStoreRepository.loadFullBitmap(context, item.uri, 2048)
                            withContext(Dispatchers.Main) { fullBmp = b }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                                if (zoomScale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isVideo) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoURI(item.uri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                }
                            },
                            update = { view ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    val cm = AnalogImageProcessor.buildSensorAdaptedMatrix(videoPreviewPreset, false)
                                    view.setRenderEffect(
                                        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoomScale,
                                    scaleY = zoomScale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    } else if (fullBmp != null) {
                        Image(
                            bitmap = fullBmp!!.asImageBitmap(),
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoomScale,
                                    scaleY = zoomScale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    } else {
                        CircularProgressIndicator(color = Color(0xFFD4AF37))
                    }

                    Text(
                        text = "${page + 1} / ${filteredMedia.size}  •  Desliza ◀ ▶",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Si es un video, permitir probar filtros en tiempo real sobre el reproductor
            if (currentItem.isVideo) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    items(BUILT_IN_PRESETS) { preset ->
                        FilterChip(
                            selected = videoPreviewPreset.id == preset.id,
                            onClick = { videoPreviewPreset = preset },
                            label = { Text("${preset.brand} ${preset.name}", fontSize = 10.sp) }
                        )
                    }
                }
            }

            // Barra de acciones: Compartir, Editar (lee metadata guardada), Borrar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { MediaStoreRepository.shareMediaItems(context, listOf(currentItem)) }
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir", tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("COMPARTIR", color = Color.White, fontSize = 11.sp)
                }

                if (!currentItem.isVideo) {
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val cleanBmp = MediaStoreRepository.loadCleanOriginalOrFullBitmap(context, currentItem, 2560)
                                val savedRecipe = MediaStoreRepository.loadRecipeFromUri(context, currentItem.uri)
                                withContext(Dispatchers.Main) {
                                    if (cleanBmp != null) {
                                        editingBaseBitmap = cleanBmp
                                        editingDisplayName = currentItem.displayName
                                        editingTargetUri = currentItem.uri
                                        if (savedRecipe != null) {
                                            activePreset = BUILT_IN_PRESETS.firstOrNull { it.id == savedRecipe.presetId } ?: BUILT_IN_PRESETS[0]
                                            editEv = savedRecipe.ev
                                            editAspect = AspectRatioMode.values().firstOrNull { it.name == savedRecipe.aspectName } ?: AspectRatioMode.RATIO_16_9
                                            editVfx = VfxConfig(
                                                sunFlareIntensity = savedRecipe.flare,
                                                lightLeakIntensity = savedRecipe.leak,
                                                anamorphicIntensity = savedRecipe.anamorphic,
                                                halationIntensity = savedRecipe.halation,
                                                grainIntensity = savedRecipe.grain,
                                                noiseIntensity = savedRecipe.noise,
                                                temperature = savedRecipe.temperature,
                                                tint = savedRecipe.tint,
                                                polaroidFrame = savedRecipe.polaroid
                                            )
                                        }
                                        isEditingMode = true
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Editar", tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("RE-EDITAR", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            MediaStoreRepository.deleteMediaItems(context, listOf(currentItem))
                            withContext(Dispatchers.Main) {
                                onRefreshGallery()
                                Toast.makeText(context, "Archivo eliminado", Toast.LENGTH_SHORT).show()
                                if (filteredMedia.size <= 1) activePagerIndex = null
                            }
                        }
                    },
                    border = BorderStroke(1.dp, Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color(0xFFE53935), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("BORRAR", color = Color(0xFFE53935), fontSize = 11.sp)
                }
            }
            return@Column
        }

        // ====================================================================
        // MODO 3: CUADRÍCULA CON ORGANIZADOR FOTOS/VIDEOS Y SELECCIÓN MÚLTIPLE
        // ====================================================================
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botones para organizar automáticamente entre TODOS, FOTOS y VIDEOS
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GalleryMediaFilter.values().forEach { filter ->
                    FilterChip(
                        selected = mediaFilter == filter,
                        onClick = { mediaFilter = filter },
                        label = { Text(filter.label, fontSize = 11.sp) }
                    )
                }
            }

            // Botón para activar Selección Múltiple (Borrar / Compartir varios)
            TextButton(
                onClick = {
                    isSelectionMode = !isSelectionMode
                    if (!isSelectionMode) selectedUris.clear()
                }
            ) {
                Text(
                    if (isSelectionMode) "CANCELAR (${selectedUris.size})" else "SELECCIONAR",
                    color = Color(0xFFD4AF37),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Barra flotante cuando hay imágenes seleccionadas para Borrar o Compartir
        if (isSelectionMode && selectedUris.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1D24), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${selectedUris.size} seleccionados", color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val items = persistedMedia.filter { selectedUris.contains(it.uri) }
                            MediaStoreRepository.shareMediaItems(context, items)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B303C))
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("COMPARTIR", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            val items = persistedMedia.filter { selectedUris.contains(it.uri) }
                            scope.launch(Dispatchers.IO) {
                                val count = MediaStoreRepository.deleteMediaItems(context, items)
                                withContext(Dispatchers.Main) {
                                    selectedUris.clear()
                                    isSelectionMode = false
                                    onRefreshGallery()
                                    Toast.makeText(context, "$count eliminados", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("BORRAR", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (filteredMedia.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay elementos en ${mediaFilter.label}", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredMedia, key = { _, it -> it.uri.toString() }) { index, item ->
                    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                    val isSelected = selectedUris.contains(item.uri)

                    LaunchedEffect(item.uri) {
                        withContext(Dispatchers.IO) {
                            val loaded = MediaStoreRepository.loadThumbnailBitmap(context, item, 280)
                            withContext(Dispatchers.Main) { thumb = loaded }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF16181E))
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) Color(0xFFD4AF37) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (isSelectionMode) {
                                        if (isSelected) selectedUris.remove(item.uri) else selectedUris.add(item.uri)
                                    } else {
                                        activePagerIndex = index
                                    }
                                },
                                onLongClick = {
                                    isSelectionMode = true
                                    if (!isSelected) selectedUris.add(item.uri)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb!!.asImageBitmap(),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Text("VIDEO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (isSelectionMode) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) Color(0xFFD4AF37) else Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// ESTUDIO DE COLLAGE CINEMATOGRÁFICO (8MM, 35MM, 75MM, 21:9, ESCENA DE PELÍCULA)
// ============================================================================

@Composable
fun CollageStudioModal(
    appPhotos: List<PersistedMediaItem>,
    preselectedUris: List<Uri>,
    onSaved: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val chosenUris = remember { mutableStateListOf<Uri>().apply { addAll(preselectedUris.take(4)) } }
    var selectedTemplate by remember { mutableStateOf(CollageTemplate.FILM_35MM) }
    var selectedPreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) }
    var collageTemp by remember { mutableStateOf(0f) }
    var collageTint by remember { mutableStateOf(0f) }
    var collagePreview by remember { mutableStateOf<Bitmap?>(null) }

    // Selector para agregar múltiples fotos desde la galería del teléfono
    val phoneGalleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { u ->
            if (chosenUris.size < 6 && !chosenUris.contains(u)) chosenUris.add(u)
        }
    }

    LaunchedEffect(chosenUris.toList(), selectedTemplate, selectedPreset, collageTemp, collageTint) {
        if (chosenUris.isEmpty()) {
            collagePreview = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            val bitmaps = chosenUris.mapNotNull { MediaStoreRepository.loadFullBitmap(context, it, 1024) }
            if (bitmaps.isNotEmpty()) {
                val generated = AnalogImageProcessor.generateCinemaCollage(
                    bitmaps = bitmaps,
                    template = selectedTemplate,
                    preset = selectedPreset,
                    temperature = collageTemp,
                    tint = collageTint
                )
                withContext(Dispatchers.Main) { collagePreview = generated }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0A0B0E)).padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("CERRAR", color = Color.White)
            }
            Text("COLLAGE DE PELÍCULA", color = Color(0xFFD4AF37), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Button(
                onClick = { phoneGalleryPicker.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242832))
            ) {
                Text("+ FOTOS DEL TELÉFONO", color = Color.White, fontSize = 10.sp)
            }
        }

        // Tira para elegir fotos de la Galería de la App
        Text("Toca fotos de la app o del teléfono (2 a 5 fotos):", color = Color.Gray, fontSize = 11.sp)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 6.dp).height(64.dp)
        ) {
            items(appPhotos) { item ->
                var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                val isAdded = chosenUris.contains(item.uri)
                LaunchedEffect(item.uri) {
                    withContext(Dispatchers.IO) {
                        val b = MediaStoreRepository.loadThumbnailBitmap(context, item, 160)
                        withContext(Dispatchers.Main) { thumb = b }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(2.dp, if (isAdded) Color(0xFFD4AF37) else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable {
                            if (isAdded) chosenUris.remove(item.uri)
                            else if (chosenUris.size < 6) chosenUris.add(item.uri)
                        }
                ) {
                    thumb?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        // Vista previa del Collage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (collagePreview != null) {
                Image(
                    bitmap = collagePreview!!.asImageBitmap(),
                    contentDescription = "Collage Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("Selecciona al menos 1 foto arriba para generar la cinta", color = Color.Gray, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Selector de Formato de Cinta (8mm, 35mm, 75mm, 21:9, Película)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CollageTemplate.values()) { tpl ->
                FilterChip(
                    selected = selectedTemplate == tpl,
                    onClick = { selectedTemplate = tpl },
                    label = { Text(tpl.label, fontSize = 11.sp) }
                )
            }
        }

        // Selector de Film para el Collage
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(BUILT_IN_PRESETS) { preset ->
                FilterChip(
                    selected = selectedPreset.id == preset.id,
                    onClick = { selectedPreset = preset },
                    label = { Text("${preset.brand} ${preset.name}", fontSize = 10.sp) }
                )
            }
        }

        Button(
            onClick = {
                val bmp = collagePreview ?: return@Button
                scope.launch(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_COLLAGE_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                        }
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Collage guardado en Galería", Toast.LENGTH_SHORT).show()
                        onSaved()
                    }
                }
            },
            enabled = collagePreview != null,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37)),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) {
            Text("GUARDAR COLLAGE EN GALERÍA", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
