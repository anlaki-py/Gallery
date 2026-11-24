package org.fossify.gallery.ai

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AiIndexDatabaseTest {
    private lateinit var db: AiIndexDatabase
    private fun media(id: Int) = AiMedia("content://media/external/images/media/$id", "/storage/emulated/0/Pictures/$id.jpg", 100, 1000, false, 0)
    private fun vector(axis: Int) = FloatArray(768).also { it[axis] = 1f }

    @Before fun setup() {
        db = AiIndexDatabase.get(RuntimeEnvironment.getApplication())
        db.clear()
    }

    @Test fun checkpointSurvivesReconciliationWithoutDuplicatingFrames() {
        val item = media(1)
        db.reconcile(listOf(item))
        assertTrue(db.needsIndexing(item))
        db.save(item, listOf(vector(0)))
        db.reconcile(listOf(item))
        assertFalse(db.needsIndexing(item))
        assertEquals(AiIndexStats(1, 0, 0), db.stats())
        assertEquals(listOf(item.path), db.search(vector(0), setOf(item.path)).map { it.path })
    }

    @Test fun changedAndDeletedMediaLoseStaleEmbeddings() {
        val first = media(1)
        val second = media(2)
        db.reconcile(listOf(first, second))
        db.save(first, listOf(vector(0)))
        db.save(second, listOf(vector(0)))
        val changed = first.copy(modified = 200)
        db.reconcile(listOf(changed))
        assertEquals(AiIndexStats(0, 1, 0), db.stats())
        assertTrue(db.needsIndexing(changed))
        assertTrue(db.search(vector(0), setOf(first.path, second.path)).isEmpty())
    }

    @Test fun bestVideoFrameDeterminesRankAndVideoAppearsOnce() {
        val video = media(1).copy(video = true, durationMs = 20_000)
        val photo = media(2)
        db.reconcile(listOf(video, photo))
        db.save(video, listOf(vector(1), vector(0), vector(2)))
        db.save(photo, listOf(FloatArray(768).also { it[0] = 0.5f; it[1] = 0.5f }))
        val results = db.search(vector(0), setOf(video.path, photo.path))
        assertEquals(listOf(video.path, photo.path), results.map { it.path })
        assertEquals(1f, results[0].score, 1e-6f)
    }

    @Test fun searchHonorsVisibilityAndLimit() {
        val media = (1..10).map(::media)
        db.reconcile(media)
        media.forEach { db.save(it, listOf(vector(0))) }
        assertEquals(3, db.search(vector(0), media.map { it.path }.toSet(), limit = 3).size)
        assertEquals(listOf(media[4].path), db.search(vector(0), setOf(media[4].path)).map { it.path })
        assertTrue(db.search(vector(0), emptySet()).isEmpty())
    }

    @Test fun unreadableMediaWaitsForRetryAndCanBeRetriedManually() {
        val item = media(1)
        db.reconcile(listOf(item))
        db.failed(item, "Corrupt JPEG")
        assertFalse(db.needsIndexing(item))
        assertEquals(AiIndexStats(0, 0, 1), db.stats())
        db.retryFailures()
        assertTrue(db.needsIndexing(item))
        assertEquals(AiIndexStats(0, 1, 0), db.stats())
    }

    @Test fun invalidFrameCannotPublishAHalfIndexedVideo() {
        val item = media(1).copy(video = true)
        db.reconcile(listOf(item))
        assertThrows(IllegalArgumentException::class.java) { db.save(item, listOf(vector(0), FloatArray(2))) }
        assertTrue(db.needsIndexing(item))
        assertTrue(db.search(vector(0), setOf(item.path)).isEmpty())
    }

    @Test fun staleInFlightSaveCannotOverwriteAChangedOrClearedRecord() {
        val item = media(1)
        db.reconcile(listOf(item))
        db.reconcile(listOf(item.copy(size = 2000)))
        db.save(item, listOf(vector(0)))
        assertEquals(AiIndexStats(0, 1, 0), db.stats())
        db.clear()
        db.save(item, listOf(vector(0)))
        assertEquals(AiIndexStats(0, 0, 0), db.stats())
    }
}
