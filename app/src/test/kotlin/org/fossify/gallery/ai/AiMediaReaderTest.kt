package org.fossify.gallery.ai

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.fossify.gallery.extensions.config
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [AiMediaReaderTest.TestEnvironment::class])
class AiMediaReaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: MediaProvider

    @Implements(Environment::class)
    class TestEnvironment : ShadowEnvironment() {
        companion object {
            @JvmStatic @Implementation(minSdk = 30)
            fun isExternalStorageManager() = false
        }
    }

    class MediaProvider : ContentProvider() {
        var imageRows: List<Array<Any?>> = emptyList()
        var videoRows: List<Array<Any?>> = emptyList()
        var fail = false
        val selections = mutableListOf<String?>()
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (fail) throw SecurityException("Access revoked")
            selections.add(selection)
            val rows = if (uri.path.orEmpty().contains("video")) videoRows else imageRows
            return MatrixCursor(projection).apply { rows.forEach(::addRow) }
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        provider = MediaProvider()
        ShadowContentResolver.registerProviderInternal("media", provider)
    }

    private fun image(id: Long, path: String) = arrayOf<Any?>(id, path, 100L, 1024L)

    @Test fun includesPhotosAndVideosAndExcludesPendingAndTrashedInQuery() {
        provider.imageRows = listOf(image(1, "/storage/emulated/0/Pictures/photo.jpg"))
        provider.videoRows = listOf(arrayOf<Any?>(2L, "/storage/emulated/0/Movies/video.mp4", 101L, 2048L, 5000L))
        val media = AiMediaReader.list(context)
        assertEquals(2, media.size)
        assertFalse(media[0].video)
        assertTrue(media[1].video)
        assertEquals(5000L, media[1].durationMs)
        assertTrue(provider.selections.all { it.orEmpty().contains(MediaStore.MediaColumns.IS_PENDING) && it.orEmpty().contains(MediaStore.MediaColumns.IS_TRASHED) })
    }

    @Test fun hiddenExcludedAndProtectedMediaCannotEnterIndex() {
        context.config.excludedFolders = hashSetOf("/storage/emulated/0/Excluded")
        context.config.addFolderProtection("/storage/emulated/0/Private", "hash", 1)
        provider.imageRows = listOf(
            image(1, "/storage/emulated/0/.hidden/photo.jpg"),
            image(2, "/storage/emulated/0/Pictures/.private.jpg"),
            image(3, "/storage/emulated/0/Excluded/Child/photo.jpg"),
            image(4, "/storage/emulated/0/Private/Child/photo.jpg"),
            image(5, "/storage/emulated/0/Pictures/public.jpg")
        )
        assertEquals(listOf("/storage/emulated/0/Pictures/public.jpg"), AiMediaReader.list(context).map { it.path })
    }

    @Test fun queryFailureIsNotAnEmptySuccessfulSnapshot() {
        provider.fail = true
        assertThrows(SecurityException::class.java) { AiMediaReader.list(context) }
    }

    @Test fun cancellationStopsEnumerationBeforeReconciliation() {
        provider.imageRows = listOf(image(1, "/storage/emulated/0/Pictures/photo.jpg"))
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            AiMediaReader.list(context) { throw java.util.concurrent.CancellationException() }
        }
    }
}
