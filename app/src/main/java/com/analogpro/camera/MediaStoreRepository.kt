package com.analogpro.camera

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

data class PersistedPhotoItem(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long
)

object MediaStoreRepository {

    /**
     * Consulta la base de datos permanente MediaStore de Android para recuperar todas las
     * fotografías tomadas con AnalogPro (incluso después de cerrar la app o reiniciar el teléfono).
     */
    fun loadPersistedPhotos(context: Context): List<PersistedPhotoItem> {
        val list = ArrayList<PersistedPhotoItem>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
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
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        list.add(PersistedPhotoItem(contentUri, name, date))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun loadThumbnailBitmap(context: Context, uri: Uri, maxDim: Int = 256): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(maxDim, maxDim), null)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } catch (e: Exception) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun loadFullBitmap(context: Context, uri: Uri, maxPixelSide: Int = 2048): Bitmap? {
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
}
