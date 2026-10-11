package com.analogpro.camera

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

@Composable
fun DarkroomGalleryScreen(
    persistedPhotos: List<PersistedPhotoItem>,
    onRefreshGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isEditingMode by remember { mutableStateOf(false) }

    // Estado de Zoom con gestos en el visor de galería
    var zoomScale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Controles del Modo Editor (Cuarto Oscuro)
    var activePreset by remember { mutableStateOf(BUILT_IN_PRESETS[1]) }
    var activeLut by remember { mutableStateOf<CubeLut3D?>(null) }
    var editVfx by remember { mutableStateOf(VfxConfig(grainIntensity = 0.22f, noiseIntensity = 0.08f)) }
    var editEv by remember { mutableStateOf(0f) }
    var editAspect by remember { mutableStateOf(AspectRatioMode.OPEN_GATE) }
    var customSunX by remember { mutableStateOf(0.75f) }
    var customSunY by remember { mutableStateOf(0.25f) }
    var editorSubTab by remember { mutableStateOf(0) } // 0 = Films, 1 = VFX, 2 = Exposición/Formato

    // Importador de cualquier foto externa del teléfono
    val externalPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val bmp = MediaStoreRepository.loadFullBitmap(context, uri, 2048)
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        selectedBitmap = bmp
                        previewBitmap = bmp
                        isEditingMode = true
                        zoomScale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
        }
    }

    // Importador de LUT .cube dentro del editor
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

    // Recalcular vista previa en tiempo real en el Editor
    LaunchedEffect(
        selectedBitmap, isEditingMode, activePreset, activeLut,
        editVfx, editEv, editAspect, customSunX, customSunY
    ) {
        val src = selectedBitmap ?: return@LaunchedEffect
        if (!isEditingMode) {
            previewBitmap = src
            return@LaunchedEffect
        }
        withContext(Dispatchers.Default) {
            val previewScale = 960f / maxOf(src.width, src.height).coerceAtLeast(1)
            val sw = (src.width * previewScale).toInt().coerceAtLeast(120)
            val sh = (src.height * previewScale).toInt().coerceAtLeast(120)
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)

            val smallAnalyze = Bitmap.createScaledBitmap(scaled, 64, 64, true)
            val baseAnalysis = AnalogImageProcessor.analyzeSceneLight(smallAnalyze)
            smallAnalyze.recycle()

            val effectiveAnalysis = baseAnalysis.copy(sunX = customSunX, sunY = customSunY)

            val result = AnalogImageProcessor.processImage(
                source = scaled,
                aspectMode = editAspect,
                preset = activePreset,
                customLut = activeLut,
                vfx = editVfx,
                light = effectiveAnalysis,
                exposureBias = editEv,
                disposableFlashActive = false,
                focusXNorm = 0.5f,
                focusYNorm = 0.5f,
                metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM STUDIO"
            )
            withContext(Dispatchers.Main) {
                previewBitmap = result
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C0E))
            .padding(12.dp)
    ) {
        // BARRA SUPERIOR DE GALERÍA & EDITOR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (selectedBitmap != null) {
                        selectedBitmap = null
                        isEditingMode = false
                        zoomScale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        onClose()
                    }
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(if (selectedBitmap != null) "GALERÍA" else "CÁMARA", color = Color.White, fontWeight = FontWeight.Bold)
            }

            Text(
                text = if (selectedBitmap == null) "GALERÍA (${persistedPhotos.size})" else if (isEditingMode) "EDITOR CUARTO OSCURO" else "VISOR (ZOOM TÁCTIL)",
                color = Color(0xFFD4AF37),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Button(
                onClick = { externalPhotoPicker.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22252A)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, tint = Color(0xFFD4AF37), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("IMPORTAR", color = Color.White, fontSize = 11.sp)
            }
        }

        if (selectedBitmap == null) {
            // CUADRÍCULA DE FOTOS PERSISTENTES
            if (persistedPhotos.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoAlbum, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Aún no hay fotos en DCIM/AnalogPro", color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Toma una foto o toca IMPORTAR para editar cualquier imagen de tu galería.", color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp)
                ) {
                    items(persistedPhotos, key = { it.uri.toString() }) { item ->
                        var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(item.uri) {
                            withContext(Dispatchers.IO) {
                                val loaded = MediaStoreRepository.loadThumbnailBitmap(context, item.uri, 300)
                                withContext(Dispatchers.Main) { thumb = loaded }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF181A1F))
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        val full = MediaStoreRepository.loadFullBitmap(context, item.uri, 2048)
                                        withContext(Dispatchers.Main) {
                                            if (full != null) {
                                                selectedBitmap = full
                                                previewBitmap = full
                                                isEditingMode = false
                                                zoomScale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb!!.asImageBitmap(),
                                    contentDescription = item.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color(0xFFD4AF37))
                            }
                        }
                    }
                }
            }
        } else {
            // VISOR CON PINCH-TO-ZOOM + MODO EDITOR COMPLETO
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF050505))
                    .pointerInput(isEditingMode) {
                        if (!isEditingMode) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1f, 6f)
                                if (zoomScale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        } else {
                            // En Modo Editor, tocar en la imagen permite posicionar el Sol/Flares interactivamente
                            detectTapGestures { pos ->
                                customSunX = (pos.x / size.width.toFloat()).coerceIn(0.02f, 0.98f)
                                customSunY = (pos.y / size.height.toFloat()).coerceIn(0.02f, 0.98f)
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Foto seleccionada",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = if (isEditingMode) 1f else zoomScale,
                                scaleY = if (isEditingMode) 1f else zoomScale,
                                translationX = if (isEditingMode) 0f else offsetX,
                                translationY = if (isEditingMode) 0f else offsetY
                            )
                    )
                }

                // Indicador en esquina
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (!isEditingMode) "Pellizca para hacer Zoom (${String.format(Locale.US, "%.1fx", zoomScale)})"
                        else "Toca la foto para mover la posición del Sol / Flare",
                        color = Color.White,
                        fontSize = 10.sp
                    )
                }
            }

            if (!isEditingMode) {
                // Botón para entrar al Modo Editor sobre esta foto
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            zoomScale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    ) {
                        Text("RESTABLECER ZOOM", color = Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { isEditingMode = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("EDITAR CON FILMS & VFX", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // PANEL DE EDICIÓN COMPLETO (FILMS CON MINIATURAS + VFX INTERACTIVOS + FORMATO)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF15171C), RoundedCornerShape(12.dp))
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
                            label = { Text("VFX / FLARES / GRANO", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = editorSubTab == 2,
                            onClick = { editorSubTab = 2 },
                            label = { Text("EXPOSICIÓN & RATIO", fontSize = 11.sp) }
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    when (editorSubTab) {
                        0 -> {
                            // Carrusel de Películas con su imagen representativa
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
                                            modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(6.dp))
                                        )
                                        Spacer(Modifier.height(2.dp))
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
                                        label = { Text("+ Importar .CUBE", fontSize = 10.sp) }
                                    )
                                }
                                if (activeLut != null) {
                                    item {
                                        TextButton(onClick = { activeLut = null }) {
                                            Text("Quitar LUT", color = Color(0xFFE53935), fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                        1 -> {
                            Column(modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                                Text("Flare Solar Interactivo: ${(editVfx.sunFlareIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                Slider(value = editVfx.sunFlareIntensity, onValueChange = { editVfx = editVfx.copy(sunFlareIntensity = it) })

                                Text("Light Leaks Direccional: ${(editVfx.lightLeakIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                Slider(value = editVfx.lightLeakIntensity, onValueChange = { editVfx = editVfx.copy(lightLeakIntensity = it) })

                                Text("Destellos Anamórficos (Altas Luces): ${(editVfx.anamorphicIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                Slider(value = editVfx.anamorphicIntensity, onValueChange = { editVfx = editVfx.copy(anamorphicIntensity = it) })

                                Text("Grano Analógico: ${(editVfx.grainIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                Slider(value = editVfx.grainIntensity, onValueChange = { editVfx = editVfx.copy(grainIntensity = it) })

                                Text("Desenfoque de Escena (Bokeh): ${(editVfx.sceneBlurBokeh * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
                                Slider(value = editVfx.sceneBlurBokeh, onValueChange = { editVfx = editVfx.copy(sceneBlurBokeh = it) })

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = editVfx.polaroidFrame,
                                        onClick = { editVfx = editVfx.copy(polaroidFrame = !editVfx.polaroidFrame) },
                                        label = { Text("Marco Polaroid", fontSize = 11.sp) }
                                    )
                                    FilterChip(
                                        selected = editVfx.anamorphicWarmColor,
                                        onClick = { editVfx = editVfx.copy(anamorphicWarmColor = !editVfx.anamorphicWarmColor) },
                                        label = { Text(if (editVfx.anamorphicWarmColor) "Anamórfico Ámbar" else "Anamórfico Azul", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                        2 -> {
                            Text("Compensación de Exposición: ${String.format(Locale.US, "%+.1f EV", editEv)}", color = Color.White, fontSize = 11.sp)
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

                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { isEditingMode = false }) {
                            Text("CANCELAR", color = Color.LightGray)
                        }
                        Button(
                            onClick = {
                                val orig = selectedBitmap ?: return@Button
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
                                        focusXNorm = 0.5f,
                                        focusYNorm = 0.5f,
                                        metadataStamp = "${activePreset.brand} ${activePreset.name}  •  DARKROOM STUDIO"
                                    )

                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.DISPLAY_NAME, "ANALOG_EDIT_${System.currentTimeMillis()}.jpg")
                                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/AnalogPro")
                                        }
                                    }
                                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    if (uri != null) {
                                        context.contentResolver.openOutputStream(uri)?.use { out ->
                                            finalBmp.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                        }
                                    }
                                    finalBmp.recycle()
                                    withContext(Dispatchers.Main) {
                                        onRefreshGallery()
                                        Toast.makeText(context, "Guardada en DCIM/AnalogPro", Toast.LENGTH_SHORT).show()
                                        isEditingMode = false
                                        selectedBitmap = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD4AF37))
                        ) {
                            Text("GUARDAR COPIA EN GALERÍA", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
