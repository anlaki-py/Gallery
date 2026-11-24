package org.fossify.gallery.ai

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import org.fossify.gallery.extensions.config
import org.fossify.gallery.extensions.shouldFolderBeVisible
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class MediaDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

object AiMediaReader {
    private const val MAX_DIMENSION = 768

    private fun allFilesAccess(): Boolean = Build.VERSION.SDK_INT >= 30 &&
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun isProtected(context: Context, path: String): Boolean {
        var folder: File? = File(path)
        while (folder != null) {
            if (context.config.isFolderProtected(folder.path)) return true
            folder = folder.parentFile
        }
        return false
    }

    fun hasPermission(context: Context): Boolean {
        if (allFilesAccess()) return true
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED } ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    }

    /** Query both collections, including removable volumes through MediaStore's merged external view. */
    fun list(context: Context, checkCancelled: () -> Unit = {}): List<AiMedia> {
        check(hasPermission(context)) { "Allow Gallery to access photos and videos, then scan again." }
        val result = ArrayList<AiMedia>()
        val folderVisibility = HashMap<String, Boolean>()
        val noMedia = HashMap<String, Boolean>()
        val config = context.config
        // Never index password-protected hidden/excluded folders via a temporary UI unlock.
        val excluded = config.excludedFolders
        val included = config.includedFolders
        for (video in listOf(false, true)) {
            val permission = if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
            val allFiles = allFilesAccess()
            val selected = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 33 && !allFiles && !selected &&
                ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) continue
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
            if (video) projection.add(MediaStore.Video.Media.DURATION)
            val conditions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 29) conditions.add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) conditions.add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
            val cursor = context.contentResolver.query(collection, projection.toTypedArray(),
                conditions.joinToString(" AND ").ifBlank { null }, null, "${MediaStore.MediaColumns._ID} ASC")
                ?: throw IOException("Android's media library is unavailable. Try again.")
            cursor.use {
                while (it.moveToNext()) {
                    checkCancelled()
                    val path = it.getString(1) ?: continue
                    val file = File(path)
                    if (file.name.startsWith('.') || path.contains("/.")) continue
                    val visible = folderVisibility.getOrPut(file.parent ?: "") {
                        (file.parent ?: "").shouldFolderBeVisible(excluded, included, false, noMedia) { p, hidden -> noMedia[p] = hidden }
                    }
                    if (!visible || isProtected(context, file.parent ?: "") || it.getLong(3) <= 0) continue
                    result.add(AiMedia(ContentUris.withAppendedId(collection, it.getLong(0)).toString(), path,
                        it.getLong(2), it.getLong(3), video, if (video) it.getLong(4) else 0))
                }
            }
        }
        return result
    }

    fun unchanged(context: Context, media: AiMedia): Boolean {
        return context.contentResolver.query(Uri.parse(media.uri), arrayOf(MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            it.moveToFirst() && it.getLong(0) == media.modified && it.getLong(1) == media.size
        } ?: false
    }

    /** Decode a single image/frame at a time. Videos cannot allocate three full-resolution frames. */
    suspend fun embedMedia(context: Context, media: AiMedia, checkCancelled: () -> Unit, embed: suspend (ByteArray) -> FloatArray): List<FloatArray> {
        val uri = Uri.parse(media.uri)
        if (!media.video) {
            val bitmap = decodeImage(context, uri)
            return try { checkCancelled(); listOf(embed(jpeg(bitmap))) } finally { bitmap.recycle() }
        }
        val retriever = MediaMetadataRetriever()
        try {
            try { retriever.setDataSource(context, uri) }
            catch (e: Exception) { throw MediaDecodeException("Cannot read this video", e) }
            fun metadata(key: Int): String? = try { retriever.extractMetadata(key) } catch (_: Exception) { null }
            val duration = metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: media.durationMs
            if (duration <= 0) throw MediaDecodeException("Video has no readable duration")
            val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: MAX_DIMENSION
            val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: MAX_DIMENSION
            val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(width, height, 1))
            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            return EmbeddingMath.videoTimesUs(duration).map { time ->
                checkCancelled()
                val bitmap = try {
                    // CLOSEST decodes the actual frame near the requested position, not just a keyframe.
                    val frame = if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, targetHeight)
                    } else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame == null) throw MediaDecodeException("A video frame could not be decoded")
                    if (maxOf(frame.width, frame.height) > MAX_DIMENSION) {
                        val ratio = MAX_DIMENSION.toDouble() / maxOf(frame.width, frame.height)
                        Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt().coerceAtLeast(1),
                            (frame.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== frame) frame.recycle() }
                    } else frame
                } catch (e: MediaDecodeException) { throw e }
                catch (e: Exception) { throw MediaDecodeException("A video frame could not be decoded", e) }
                try { checkCancelled(); embed(jpeg(bitmap)) } finally { bitmap.recycle() }
            }
        } finally { try { retriever.release() } catch (_: Exception) { } }
    }

    private fun decodeImage(context: Context, uri: Uri): Bitmap {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = minOf(1.0, MAX_DIMENSION.toDouble() / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > MAX_DIMENSION) inSampleSize *= 2
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw MediaDecodeException("This image cannot be decoded")
            val rotation = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val flipped = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).isFlipped } ?: false
            if (rotation == 0 && !flipped) return bitmap
            val matrix = android.graphics.Matrix().apply { if (flipped) postScale(-1f, 1f); postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
        } catch (e: MediaDecodeException) { throw e }
        catch (e: Exception) { throw MediaDecodeException("This image cannot be decoded", e) }
    }

    private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw MediaDecodeException("Image conversion failed")
        it.toByteArray()
    }
}
