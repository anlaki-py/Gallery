package org.fossify.gallery.ai

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModelDownloadException(message: String, val retryable: Boolean = false) : IOException(message)

object AiModelStore {
    // Pinned to Edge Gallery model_allowlists/1_0_23.json; hash verified against HF's LFS metadata.
    const val SIZE = 484622336L
    const val SHA256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c"
    const val MODEL_URL = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/86b903a293f96d042bb92e1b7af333d94114458f/embeddinggemma-2-740m.litertlm"
    private const val RESERVE = 64L * 1024 * 1024

    fun directory(context: Context) = File(context.noBackupFilesDir, "ai_model").apply {
        check(isDirectory || mkdirs()) { "Cannot create model folder. Check available storage." }
    }
    fun file(context: Context) = File(directory(context), "embeddinggemma-2-740m.litertlm")
    private fun marker(context: Context) = File(directory(context), "verified.sha256")
    fun ready(context: Context): Boolean = try {
        file(context).length() == SIZE && marker(context).readText() == SHA256
    } catch (_: Exception) { false }

    fun remove(context: Context) {
        marker(context).delete()
        val model = file(context)
        if (model.exists() && !model.delete()) throw IOException("Cannot remove the model. Restart Gallery and try again.")
        File(directory(context), "model.part").delete()
    }

    /** App-private .part is resumable; only a full size and SHA-256 match can become the live model. */
    @Synchronized
    fun download(context: Context, checkCancelled: () -> Unit, progress: (Long, Boolean) -> Unit) {
        checkCancelled()
        if (ready(context)) return
        transfer(directory(context), file(context), marker(context), SIZE, SHA256,
            { URL(MODEL_URL).openConnection() as HttpURLConnection }, checkCancelled, progress)
    }

    @Synchronized
    fun importZip(context: Context, archive: File, checkCancelled: () -> Unit, progress: (Long) -> Unit) {
        val folder = directory(context)
        // A previous killed import may leave a private staging file; no other import owns this lock.
        folder.listFiles()?.filter { it.name.startsWith("model-import-") && it.name.endsWith(".part") }?.forEach { it.delete() }
        val candidate = AiModelZipImporter.prepare(archive, folder, checkCancelled = checkCancelled, progress = progress)
        try {
            checkCancelled()
            publishVerified(candidate, file(context), marker(context), SHA256)
        } finally { candidate.delete() }
    }

    internal fun publishVerified(candidate: File, target: File, verified: File, hash: String) {
        // Same-filesystem atomic replacement keeps an existing working model intact on failure.
        Files.move(candidate.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val pendingMarker = File(verified.parentFile, "${verified.name}.tmp")
        try {
            java.io.FileOutputStream(pendingMarker).use { output ->
                output.write(hash.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(pendingMarker.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pendingMarker.delete() }
    }

    internal fun transfer(folder: File, target: File, verified: File, expectedSize: Long, expectedHash: String,
        connectionFactory: () -> HttpURLConnection, checkCancelled: () -> Unit, progress: (Long, Boolean) -> Unit) {
        val partial = File(folder, "model.part")
        // Recover a kill between atomic promotion and writing the verification marker.
        if (!partial.exists() && target.length() == expectedSize) {
            if (!target.renameTo(partial)) throw IOException("Cannot verify the existing model")
        }
        if (partial.length() > expectedSize) partial.delete()
        var offset = partial.length()
        if (folder.usableSpace < expectedSize - offset + RESERVE) {
            throw ModelDownloadException("Not enough storage. Free at least 550 MB, then retry the download.")
        }
        if (offset < expectedSize) {
            val connection = connectionFactory()
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val response = connection.responseCode
                when {
                    response == 401 || response == 403 -> throw ModelDownloadException("Model host denied the download. Check your connection and retry later.")
                    response == 404 -> throw ModelDownloadException("The pinned model is unavailable. Try again later.")
                    response == 429 || response >= 500 -> throw ModelDownloadException("Model host is busy. Download will retry.", true)
                    response != 200 && response != 206 -> throw ModelDownloadException("Download failed (HTTP $response). Retry later.")
                }
                if (response == 206) {
                    val range = connection.getHeaderField("Content-Range") ?: ""
                    if (range != "bytes $offset-${expectedSize - 1}/$expectedSize") {
                        partial.delete()
                        throw ModelDownloadException("Invalid download resume response. Please retry.")
                    }
                } else offset = 0 // Server ignored Range. Safely start over rather than appending.
                val contentLength = connection.contentLengthLong
                if (contentLength >= 0 && contentLength != expectedSize - offset) {
                    throw ModelDownloadException("Unexpected model size. The download was not installed.")
                }
                RandomAccessFile(partial, "rw").use { output ->
                    output.setLength(offset)
                    output.seek(offset)
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = offset
                        var lastReport = 0L
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > expectedSize) { partial.delete(); throw ModelDownloadException("Model exceeded its expected size.") }
                            output.write(buffer, 0, count)
                            val now = System.nanoTime() / 1_000_000
                            if (now - lastReport > 1000) {
                                if (folder.usableSpace < RESERVE) throw ModelDownloadException("Storage is full. Free some space, then resume the download.")
                                progress(copied, false)
                                lastReport = now
                            }
                        }
                    }
                    output.fd.sync()
                }
            } finally { connection.disconnect() }
        }
        if (partial.length() != expectedSize) throw ModelDownloadException("Download interrupted. It will resume automatically.", true)
        progress(expectedSize, true)
        val digest = MessageDigest.getInstance("SHA-256")
        partial.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedHash) {
            partial.delete()
            throw ModelDownloadException("Model verification failed. The damaged download was removed. Please download again.")
        }
        checkCancelled()
        publishVerified(partial, target, verified, expectedHash)
    }
}
