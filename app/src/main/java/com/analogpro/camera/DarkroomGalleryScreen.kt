package com.analogpro.camera

import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DarkroomGalleryScreen(
    persistedMedia: List<PersistedMediaItem>,
    onRefreshGallery: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Filtro simple: TODOS | FOTOS | VIDEOS
    var mediaFilter by remember { mutableStateOf(GalleryMediaFilter.ALL) }
    val allItems = persistedMedia.toList()
    val filteredMedia = remember(allItems, mediaFilter) {
        when (mediaFilter) {
            GalleryMediaFilter.ALL -> allItems
            GalleryMediaFilter.PHOTOS -> allItems.filter { !it.isVideo }
            GalleryMediaFilter.VIDEOS -> allItems.filter { it.isVideo }
        }
    }

    // Selección múltiple para Borrar y Compartir
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    // Índice actual del visor deslizable izquierda / derecha
    var activePagerIndex by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0B0E))
            .padding(12.dp)
    ) {
        // BARRA SUPERIOR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (activePagerIndex != null) {
                        activePagerIndex = null
                    } else if (isSelectionMode) {
                        isSelectionMode = false
                        selectedUris.clear()
                    } else {
                        onClose()
                    }
                }
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (activePagerIndex != null) "CUADRÍCULA" else "CÁMARA",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "GALERÍA (${filteredMedia.size})",
                color = Color(0xFFD4AF37),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            if (activePagerIndex == null) {
                TextButton(
                    onClick = {
                        isSelectionMode = !isSelectionMode
                        if (!isSelectionMode) selectedUris.clear()
                    }
                ) {
                    Text(
                        text = if (isSelectionMode) "LISTO" else "SELECCIONAR",
                        color = Color(0xFFD4AF37),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }

        // ====================================================================
        // MODO VISOR: DESLIZAR A IZQUIERDA Y DERECHA ENTRE IMÁGENES Y VIDEOS
        // ====================================================================
        if (activePagerIndex != null && filteredMedia.isNotEmpty()) {
            val initialIdx = activePagerIndex!!.coerceIn(0, (filteredMedia.size - 1).coerceAtLeast(0))
            val pagerState = rememberPagerState(
                initialPage = initialIdx,
                pageCount = { filteredMedia.size }
            )
            val currentIdx = pagerState.currentPage.coerceIn(0, (filteredMedia.size - 1).coerceAtLeast(0))
            val currentItem = filteredMedia[currentIdx]

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) { page ->
                if (page !in filteredMedia.indices) return@HorizontalPager
                val item = filteredMedia[page]
                var isZoomed by remember(page) { mutableStateOf(false) }
                var isPlayingVideo by remember(page) { mutableStateOf(false) }
                var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }

                LaunchedEffect(item.uri, isPlayingVideo) {
                    if (!item.isVideo) {
                        withContext(Dispatchers.IO) {
                            val loaded = MediaStoreRepository.loadFullBitmap(context, item.uri, 1600)
                            withContext(Dispatchers.Main) { bitmap = loaded }
                        }
                    } else if (!isPlayingVideo) {
                        withContext(Dispatchers.IO) {
                            val thumb = MediaStoreRepository.loadThumbnailBitmap(context, item, 480)
                            withContext(Dispatchers.Main) { bitmap = thumb }
                        }
                    }
                }

                // Si el usuario desliza hacia otra página, detener el reproductor de video de la página anterior
                LaunchedEffect(pagerState.currentPage) {
                    if (pagerState.currentPage != page && isPlayingVideo) {
                        isPlayingVideo = false
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .pointerInput(page) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (!item.isVideo) isZoomed = !isZoomed
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (item.isVideo && isPlayingVideo) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val controller = MediaController(ctx)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setVideoURI(item.uri)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        start()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        isPlayingVideo = false
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val safeBmp = bitmap
                        if (safeBmp != null && !safeBmp.isRecycled) {
                            Image(
                                bitmap = safeBmp.asImageBitmap(),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = if (isZoomed) 2.2f else 1f,
                                        scaleY = if (isZoomed) 2.2f else 1f
                                    )
                            )
                        } else {
                            CircularProgressIndicator(color = Color(0xFFD4AF37))
                        }

                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                    .border(2.dp, Color(0xFFD4AF37), CircleShape)
                                    .clickable { isPlayingVideo = true },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Reproducir Video",
                                    tint = Color(0xFFD4AF37),
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }

                    // Indicador superior de posición
                    Text(
                        text = "${page + 1} / ${filteredMedia.size}  •  Desliza ◀ ▶",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Botones inferiores para Compartir y Borrar el elemento actual
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { MediaStoreRepository.shareMediaItems(context, listOf(currentItem)) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222630))
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Compartir", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("COMPARTIR", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            MediaStoreRepository.deleteMediaItems(context, listOf(currentItem))
                            withContext(Dispatchers.Main) {
                                activePagerIndex = null
                                onRefreshGallery()
                                Toast.makeText(context, "Eliminado de la galería", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("BORRAR", color = Color.White, fontSize = 12.sp)
                }
            }
            return@Column
        }

        // ====================================================================
        // MODO CUADRÍCULA: FILTRO ENTRE TODOS / FOTOS / VIDEOS
        // ====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryMediaFilter.values().forEach { filter ->
                FilterChip(
                    selected = mediaFilter == filter,
                    onClick = {
                        activePagerIndex = null
                        mediaFilter = filter
                    },
                    label = { Text(filter.label, fontSize = 11.sp) }
                )
            }
        }

        // Barra de acciones cuando hay varios elementos seleccionados
        if (isSelectionMode && selectedUris.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF181B22), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${selectedUris.size} seleccionados", color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val items = allItems.filter { selectedUris.contains(it.uri) }
                            MediaStoreRepository.shareMediaItems(context, items)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF282D38))
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("COMPARTIR", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            val items = allItems.filter { selectedUris.contains(it.uri) }
                            scope.launch(Dispatchers.IO) {
                                val deleted = MediaStoreRepository.deleteMediaItems(context, items)
                                withContext(Dispatchers.Main) {
                                    selectedUris.clear()
                                    isSelectionMode = false
                                    onRefreshGallery()
                                    Toast.makeText(context, "$deleted eliminados", Toast.LENGTH_SHORT).show()
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
                Text("No hay archivos en ${mediaFilter.label}", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredMedia) { index, item ->
                    var thumb by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
                    val isSelected = selectedUris.contains(item.uri)

                    LaunchedEffect(item.uri) {
                        withContext(Dispatchers.IO) {
                            val loaded = MediaStoreRepository.loadThumbnailBitmap(context, item, 220)
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
                            .clickable {
                                if (isSelectionMode) {
                                    if (isSelected) selectedUris.remove(item.uri) else selectedUris.add(item.uri)
                                } else {
                                    activePagerIndex = index
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val safeThumb = thumb
                        if (safeThumb != null && !safeThumb.isRecycled) {
                            Image(
                                bitmap = safeThumb.asImageBitmap(),
                                contentDescription = item.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = if (item.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }

                        if (item.isVideo) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
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
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
