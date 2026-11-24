package org.fossify.gallery.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.PriorityQueue

data class AiMedia(
    val uri: String,
    val path: String,
    val modified: Long,
    val size: Long,
    val video: Boolean,
    val durationMs: Long
) {
    val signature get() = "$modified:$size:$durationMs:${EmbeddingMath.INDEX_VERSION}"
}

data class AiIndexStats(val indexed: Int, val pending: Int, val failed: Int)
data class AiMatch(val path: String, val score: Float)

/** Own database: Gallery's Room schema and destructive migrations never touch the AI index. */
class AiIndexDatabase private constructor(context: Context) : SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "ai_search.db").absolutePath, null, 1) {
    companion object {
        @Volatile private var instance: AiIndexDatabase? = null
        fun get(context: Context): AiIndexDatabase = instance ?: synchronized(this) {
            instance ?: AiIndexDatabase(context.applicationContext).also { instance = it }
        }
    }

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE media (
            uri TEXT PRIMARY KEY, path TEXT NOT NULL, signature TEXT NOT NULL,
            state INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
            retry_at INTEGER NOT NULL DEFAULT 0, error TEXT, generation TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE frames (
            uri TEXT NOT NULL REFERENCES media(uri) ON DELETE CASCADE,
            frame INTEGER NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(uri, frame))""")
        db.execSQL("CREATE INDEX media_state ON media(state, retry_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Called only after a complete, successful MediaStore query. Never prune on query failure. */
    fun reconcile(media: List<AiMedia>) {
        val db = writableDatabase
        val generation = java.util.UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            for (item in media) {
                val existing = db.rawQuery("SELECT signature FROM media WHERE uri = ?", arrayOf(item.uri)).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (existing != item.signature) {
                    db.delete("media", "uri = ?", arrayOf(item.uri))
                    db.insertOrThrow("media", null, ContentValues().apply {
                        put("uri", item.uri); put("path", item.path)
                        put("signature", item.signature); put("generation", generation)
                    })
                } else {
                    db.update("media", ContentValues().apply {
                        put("path", item.path); put("generation", generation)
                    }, "uri = ?", arrayOf(item.uri))
                }
            }
            db.delete("media", "generation != ?", arrayOf(generation))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun needsIndexing(media: AiMedia, now: Long = System.currentTimeMillis()): Boolean =
        readableDatabase.rawQuery("SELECT state, retry_at FROM media WHERE uri = ? AND signature = ?", arrayOf(media.uri, media.signature)).use {
            it.moveToFirst() && it.getInt(0) != 1 && it.getLong(1) <= now
        }

    /** Commit all frames together. A killed process leaves the media pending, never half indexed. */
    fun save(media: AiMedia, vectors: List<FloatArray>) {
        require(vectors.isNotEmpty())
        require(vectors.map { it.size }.distinct().size == 1) { "Frame embedding dimensions do not match" }
        val encoded = vectors.map(EmbeddingMath::encode)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery("SELECT 1 FROM media WHERE uri = ? AND signature = ?", arrayOf(media.uri, media.signature)).use { it.moveToFirst() }
            if (!current) return
            db.delete("frames", "uri = ?", arrayOf(media.uri))
            encoded.forEachIndexed { frame, vector ->
                db.insertOrThrow("frames", null, ContentValues().apply {
                    put("uri", media.uri); put("frame", frame); put("vector", vector)
                })
            }
            db.update("media", ContentValues().apply {
                put("state", 1); put("attempts", 0); put("retry_at", 0); putNull("error")
            }, "uri = ? AND signature = ?", arrayOf(media.uri, media.signature))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun failed(media: AiMedia, error: String) {
        writableDatabase.execSQL("""UPDATE media SET state = 2, attempts = attempts + 1,
            retry_at = ?, error = ? WHERE uri = ? AND signature = ?""",
            arrayOf<Any>(System.currentTimeMillis() + 24 * 60 * 60 * 1000L, error.take(300), media.uri, media.signature))
    }

    fun retryFailures() {
        writableDatabase.execSQL("UPDATE media SET state = 0, attempts = 0, retry_at = 0 WHERE state = 2")
    }

    fun stats(): AiIndexStats {
        val counts = IntArray(3)
        readableDatabase.rawQuery("SELECT state, COUNT(*) FROM media GROUP BY state", null).use { cursor ->
            while (cursor.moveToNext()) counts[cursor.getInt(0)] = cursor.getInt(1)
        }
        return AiIndexStats(counts[1], counts[0], counts[2])
    }

    /** Streaming top-k: bounded heap and one vector in memory, irrespective of library size. */
    fun search(query: FloatArray, allowedPaths: Set<String>, limit: Int = 200, checkCancelled: () -> Unit = {}): List<AiMatch> {
        require(limit > 0)
        val normalized = EmbeddingMath.normalized(query)
        val heap = PriorityQueue<AiMatch>(compareBy { it.score })
        var currentPath: String? = null
        var best = Float.NEGATIVE_INFINITY
        fun flush() {
            val path = currentPath ?: return
            if (!best.isFinite()) return
            val match = AiMatch(path, best)
            if (heap.size < limit) heap.add(match)
            else if (match.score > heap.peek().score) { heap.poll(); heap.add(match) }
        }
        readableDatabase.rawQuery("""SELECT m.path, f.vector FROM frames f JOIN media m ON f.uri = m.uri
            WHERE m.state = 1 ORDER BY m.path, f.frame""", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkCancelled()
                val path = cursor.getString(0)
                if (path !in allowedPaths) continue
                if (path != currentPath) { flush(); currentPath = path; best = Float.NEGATIVE_INFINITY }
                EmbeddingMath.score(normalized, cursor.getBlob(1))?.let { best = maxOf(best, it) }
            }
            flush()
        }
        return heap.sortedWith(compareByDescending<AiMatch> { it.score }.thenBy { it.path })
    }

    fun clear() {
        writableDatabase.delete("media", null, null)
    }
}
