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

data class PersistedMediaItem(
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val isVideo: Boolean
)

object MediaStoreRepository {

    fun loadPersistedMedia(context: Context): List<PersistedMediaItem> {
        val list = ArrayList<PersistedMediaItem>()

        // 1. Cargar Fotos de AnalogPro
        try {
            val imgUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )
            context.contentResolver.query(
                imgUri,
                proj,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext() && list.size < 100) {
                    if (idCol < 0 || nameCol < 0 || dateCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 1024L

                    if (size > 0L && name.startsWith("ANALOG_", ignoreCase = true) && !name.endsWith(".dng", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = false))
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. Cargar Videos de AnalogPro
        try {
            val vidUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.SIZE
            )
            context.contentResolver.query(
                vidUri,
                proj,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)

                while (cursor.moveToNext() && list.size < 150) {
                    if (idCol < 0 || nameCol < 0 || dateCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val date = cursor.getLong(dateCol)
                    val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 1024L

                    if (size > 0L && name.startsWith("ANALOG_", ignoreCase = true)) {
                        val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        list.add(PersistedMediaItem(contentUri, name, date, isVideo = true))
                    }
                }
            }
        } catch (_: Throwable) {}

        return list.distinctBy { it.uri.toString() }.sortedByDescending { it.dateAdded }
    }

    fun loadThumbnailBitmap(context: Context, item: PersistedMediaItem, maxDim: Int = 220): Bitmap? {
        return try {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
            if (bmp != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bmp.config == Bitmap.Config.HARDWARE) {
                val soft = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.recycle()
                soft
            } else {
                bmp
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun loadFullBitmap(context: Context, uri: Uri, maxPixelSide: Int = 1600): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxPixelSide || bounds.outHeight / sampleSize > maxPixelSide) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun deleteMediaItems(context: Context, items: List<PersistedMediaItem>): Int {
        var count = 0
        for (item in items) {
            try {
                if (context.contentResolver.delete(item.uri, null, null) > 0) count++
            } catch (_: Throwable) {}
        }
        return count
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
                context.startActivity(
                    Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                val uris = ArrayList<Uri>(items.map { it.uri })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(intent, "Compartir (${items.size})").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Throwable) {}
    }
}
