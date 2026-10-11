package com.analogpro.camera

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

data class PersistedMediaItem(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val isVideo: Boolean,
    val durationMs: Long = 0L
)

object MediaStoreRepository {

    private fun ensureSoftwareBitmap(bmp: Bitmap?): Bitmap? {
        if (bmp == null) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.recycle()
                copy
            } else {
                bmp
            }
        } catch (_: Throwable) {
            bmp
        }
    }

    /**
     * Carga FOTOS (DCIM/AnalogPro) y VIDEOS (Movies/AnalogPro) ignorando archivos vacíos (0 bytes)
     * o temporales que puedan cerrar la galería.
     */
    fun loadPersistedMedia(context: Context): List<PersistedMediaItem> {
        val list = ArrayList<PersistedMediaItem>()

        // 1. Consultar Fotografías válidas
        try {
            val imgCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val imgProj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            context.contentResolver.query(
                imgCollection,
                imgProj,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext() && list.size < 120) {
                    if (idCol < 0 || nameCol < 0 || dateCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val fileSize = if (sizeCol >= 0) cursor.getLong(sizeCol) else 1024L

                    // Ignorar archivos de 0 bytes o RAW .dng puros en el visor de bitmaps
                    if (fileSize > 0L && name.startsWith("ANALOG_", ignoreCase = true) && !name.endsWith(".dng", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = false))
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        // 2. Consultar Videos válidos
        try {
            val vidCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val vidProj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE
            )
            context.contentResolver.query(
                vidCollection,
                vidProj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext() && list.size < 180) {
                    if (idCol < 0 || nameCol < 0 || dateCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val dur = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else 0L
                    val fileSize = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 1024L

                    if (fileSize > 0L && name.startsWith("ANALOG_", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = true, durationMs = dur))
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }

        return list.distinctBy { it.uri.toString() }.sortedByDescending { it.dateAdded }
    }

    fun loadThumbnailBitmap(context: Context, item: PersistedMediaItem, maxDim: Int = 260): Bitmap? {
        return try {
            val rawBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(maxDim, maxDim), null)
            } else if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    retriever.getFrameAtTime(0)
                } finally {
                    try { retriever.release() } catch (_: Throwable) {}
                }
            } else {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 4
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
            ensureSoftwareBitmap(rawBmp)
        } catch (_: Throwable) {
            // Fallback seguro si loadThumbnail falla en algún video o imagen
            try {
                if (item.isVideo) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, item.uri)
                        ensureSoftwareBitmap(retriever.getFrameAtTime(0))
                    } finally {
                        try { retriever.release() } catch (_: Throwable) {}
                    }
                } else {
                    context.contentResolver.openInputStream(item.uri)?.use { stream ->
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = 4
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        ensureSoftwareBitmap(BitmapFactory.decodeStream(stream, null, opts))
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun loadFullBitmap(context: Context, uri: Uri, maxPixelSide: Int = 1920): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxPixelSide || boundsOpts.outHeight / sampleSize > maxPixelSide) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
            ensureSoftwareBitmap(bmp)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveOriginalBackupAndExifRecipe(
        context: Context,
        savedUri: Uri,
        displayName: String,
        cleanOriginalBitmap: Bitmap,
        recipe: PhotoEditRecipe
    ) {
        try {
            context.contentResolver.openFileDescriptor(savedUri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, recipe.serialize())
                exif.saveAttributes()
            }
        } catch (_: Throwable) {}

        try {
            val dir = File(context.filesDir, "clean_originals")
            if (!dir.exists()) dir.mkdirs()
            val backupFile = File(dir, displayName)
            FileOutputStream(backupFile).use { out ->
                cleanOriginalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        } catch (_: Throwable) {}
    }

    fun loadRecipeFromUri(context: Context, uri: Uri): PhotoEditRecipe? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                PhotoEditRecipe.deserialize(comment)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun loadCleanOriginalOrFullBitmap(context: Context, item: PersistedMediaItem, maxPixelSide: Int = 1920): Bitmap? {
        return try {
            val backupFile = File(File(context.filesDir, "clean_originals"), item.displayName)
            if (backupFile.exists()) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(backupFile.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxPixelSide || bounds.outHeight / sample > maxPixelSide) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                ensureSoftwareBitmap(BitmapFactory.decodeFile(backupFile.absolutePath, opts))
            } else {
                loadFullBitmap(context, item.uri, maxPixelSide)
            }
        } catch (_: Throwable) {
            loadFullBitmap(context, item.uri, maxPixelSide)
        }
    }

    fun deleteMediaItems(context: Context, items: List<PersistedMediaItem>): Int {
        var deletedCount = 0
        for (item in items) {
            try {
                val rows = context.contentResolver.delete(item.uri, null, null)
                if (rows > 0) deletedCount++
                val backupFile = File(File(context.filesDir, "clean_originals"), item.displayName)
                if (backupFile.exists()) backupFile.delete()
            } catch (_: Throwable) {}
        }
        return deletedCount
    }

    fun shareMediaItems(context: Context, items: List<PersistedMediaItem>) {
        if (items.isEmpty()) return
        try {
            if (items.size == 1) {
                val single = items.first()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = if (single.isVideo) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, single.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Compartir").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } else {
                val uris = ArrayList<Uri>(items.map { it.uri })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Compartir (${items.size})").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}
