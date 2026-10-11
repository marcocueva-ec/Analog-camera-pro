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

    /**
     * Carga tanto las FOTOS (DCIM/AnalogPro) como los VIDEOS (Movies/AnalogPro)
     * ordenados del más reciente al más antiguo.
     */
    fun loadPersistedMedia(context: Context): List<PersistedMediaItem> {
        val list = ArrayList<PersistedMediaItem>()
        try {
            // 1. Consultar Fotografías
            val imgCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val imgProj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            context.contentResolver.query(
                imgCollection,
                imgProj,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext() && list.size < 150) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val relPath = if (pathCol >= 0) (cursor.getString(pathCol) ?: "") else ""
                    if (name.startsWith("ANALOG_") || relPath.contains("AnalogPro", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = false))
                    }
                }
            }

            // 2. Consultar Videos
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
                MediaStore.Video.Media.RELATIVE_PATH
            )
            context.contentResolver.query(
                vidCollection,
                vidProj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext() && list.size < 220) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val dur = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val relPath = if (pathCol >= 0) (cursor.getString(pathCol) ?: "") else ""
                    if (name.startsWith("ANALOG_") || relPath.contains("AnalogPro", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = true, durationMs = dur))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return list.sortedByDescending { it.dateAdded }
    }

    fun loadThumbnailBitmap(context: Context, item: PersistedMediaItem, maxDim: Int = 280): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(item.uri, Size(maxDim, maxDim), null)
            } else if (item.isVideo) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, item.uri)
                val frame = retriever.getFrameAtTime(0)
                retriever.release()
                frame
            } else {
                context.contentResolver.openInputStream(item.uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun loadFullBitmap(context: Context, uri: Uri, maxPixelSide: Int = 2560): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
            }
            var sampleSize = 1
            while (boundsOpts.outWidth / sampleSize > maxPixelSide || boundsOpts.outHeight / sampleSize > maxPixelSide) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Guarda una copia limpia del bitmap original sin filtros y escribe la receta en EXIF
     * para que cuando el usuario vuelva a entrar a la foto pueda cambiar el filtro sin acumular efectos.
     */
    fun saveOriginalBackupAndExifRecipe(
        context: Context,
        savedUri: Uri,
        displayName: String,
        cleanOriginalBitmap: Bitmap,
        recipe: PhotoEditRecipe
    ) {
        try {
            // 1. Escribir receta en el campo UserComment de ExifInterface
            context.contentResolver.openFileDescriptor(savedUri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, recipe.serialize())
                exif.saveAttributes()
            }
        } catch (_: Exception) {}

        try {
            // 2. Guardar respaldo base limpio en almacenamiento interno de la app
            val dir = File(context.filesDir, "clean_originals")
            if (!dir.exists()) dir.mkdirs()
            val backupFile = File(dir, displayName)
            FileOutputStream(backupFile).use { out ->
                cleanOriginalBitmap.compress(Bitmap.CompressFormat.JPEG, 96, out)
            }
        } catch (_: Exception) {}
    }

    fun loadRecipeFromUri(context: Context, uri: Uri): PhotoEditRecipe? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val comment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                PhotoEditRecipe.deserialize(comment)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadCleanOriginalOrFullBitmap(context: Context, item: PersistedMediaItem, maxPixelSide: Int = 2560): Bitmap? {
        return try {
            val backupFile = File(File(context.filesDir, "clean_originals"), item.displayName)
            if (backupFile.exists()) {
                BitmapFactory.decodeFile(backupFile.absolutePath)
            } else {
                loadFullBitmap(context, item.uri, maxPixelSide)
            }
        } catch (_: Exception) {
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
            } catch (_: Exception) {}
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
                context.startActivity(Intent.createChooser(intent, "Compartir en redes o contactos"))
            } else {
                val uris = ArrayList<Uri>(items.map { it.uri })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir (${items.size}) elementos"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
