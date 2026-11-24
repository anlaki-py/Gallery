package org.fossify.gallery.ai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ModelImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Reads only the model entry. Archive paths are never used as extraction destinations. */
object AiModelZipImporter {
    const val MODEL_FILE = "embeddinggemma-2-740m.litertlm"
    const val REVISION = "86b903a293f96d042bb92e1b7af333d94114458f"
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val RESERVE = 64L * 1024 * 1024

    /** A private copy makes non-seekable document providers usable with ZipFile, across restarts. */
    internal fun copyArchive(
        input: InputStream,
        target: File,
        declaredSize: Long?,
        modelSize: Long = AiModelStore.SIZE,
        maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
        freeBytes: () -> Long = { target.parentFile?.usableSpace ?: 0L },
        checkCancelled: () -> Unit,
        progress: (Long) -> Unit
    ) {
        val knownSize = declaredSize?.takeIf { it > 0 }
        if (knownSize != null && knownSize > maxArchiveBytes) {
            throw ModelImportException("ZIP is too large. Zip only Edge Gallery's EmbeddingGemma_2 folder (maximum 2 GB).")
        }
        val required = (knownSize ?: 0L) + modelSize + RESERVE
        if (freeBytes() < required) {
            throw ModelImportException("Not enough storage to import this ZIP. Free space for a temporary ZIP copy and the 485 MB model, then try again.")
        }
        var complete = false
        try {
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                var nextSpaceCheck = 0L
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    checkCancelled()
                    if (count < 0) break
                    copied += count
                    if (copied > maxArchiveBytes) throw ModelImportException("ZIP exceeded the 2 GB import limit.")
                    if (copied >= nextSpaceCheck) {
                        if (freeBytes() < modelSize + RESERVE) {
                            throw ModelImportException("Storage is full. Free space for the model, then import the ZIP again.")
                        }
                        nextSpaceCheck = copied + 1024 * 1024
                    }
                    output.write(buffer, 0, count)
                    progress(copied)
                }
                if (knownSize != null && copied != knownSize) {
                    throw ModelImportException("ZIP changed or was interrupted while being read. Select the original ZIP again.")
                }
                output.fd.sync()
            }
            checkCancelled()
            complete = true
        } finally {
            if (!complete) target.delete()
        }
    }

    /** Returns a verified staging file; the caller atomically installs it in the model store. */
    internal fun prepare(
        archive: File,
        modelDirectory: File,
        expectedSize: Long = AiModelStore.SIZE,
        expectedHash: String = AiModelStore.SHA256,
        checkCancelled: () -> Unit,
        progress: (Long) -> Unit
    ): File {
        if (archive.length() > MAX_ARCHIVE_BYTES) throw ModelImportException("ZIP exceeded the 2 GB import limit.")
        if (modelDirectory.usableSpace < expectedSize + RESERVE) {
            throw ModelImportException("Not enough free storage for the imported model. Free at least 550 MB and try again.")
        }
        var staging: File? = null
        var complete = false
        try {
            ZipFile(archive).use { zip ->
                val candidates = ArrayList<java.util.zip.ZipEntry>()
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    if (++count > MAX_ENTRIES) throw ModelImportException("ZIP contains too many files. Zip only the EmbeddingGemma_2 folder.")
                    val name = entry.name.replace('\\', '/')
                    val parts = name.split('/')
                    if (name.length > 2048 || name.startsWith('/') || name.contains('\u0000') ||
                        parts.any { it == ".." } || Regex("^[A-Za-z]:").containsMatchIn(name)) {
                        throw ModelImportException("ZIP contains an unsafe file path. Create a new ZIP of the EmbeddingGemma_2 folder.")
                    }
                    if (!entry.isDirectory && parts.last().equals(MODEL_FILE, ignoreCase = true) && "__MACOSX" !in parts) {
                        candidates.add(entry)
                    }
                }
                val pinned = candidates.filter { REVISION in it.name.replace('\\', '/').split('/') }
                val eligible = pinned.ifEmpty { candidates }
                if (eligible.isEmpty()) {
                    throw ModelImportException("ZIP does not contain $MODEL_FILE. Zip Edge Gallery's EmbeddingGemma_2 folder, then select that ZIP.")
                }
                if (eligible.size != 1) {
                    throw ModelImportException("ZIP contains multiple matching models. Zip only the $REVISION revision folder.")
                }
                val entry = eligible.single()
                if (entry.size >= 0 && entry.size != expectedSize) {
                    throw ModelImportException("The model in this ZIP has the wrong size. Use EmbeddingGemma 2 revision $REVISION.")
                }
                val candidate = File.createTempFile("model-import-", ".part", modelDirectory)
                staging = candidate
                val digest = MessageDigest.getInstance("SHA-256")
                val crc = CRC32()
                FileOutputStream(candidate).use { output ->
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var copied = 0L
                        while (true) {
                            checkCancelled()
                            val read = input.read(buffer)
                            checkCancelled()
                            if (read < 0) break
                            copied += read
                            if (copied > expectedSize) throw ModelImportException("The model exceeded its expected size. The ZIP was not installed.")
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            crc.update(buffer, 0, read)
                            progress(copied)
                        }
                        if (copied != expectedSize) throw ModelImportException("The model is incomplete. Create the ZIP again from the complete Edge Gallery model.")
                    }
                    output.fd.sync()
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != expectedHash || (entry.crc >= 0 && crc.value != entry.crc)) {
                    throw ModelImportException("Model verification failed. This ZIP contains a different or damaged model. Use EmbeddingGemma 2 revision $REVISION.")
                }
                checkCancelled()
                complete = true
                return candidate
            }
        } catch (e: ZipException) {
            throw ModelImportException("Cannot read this ZIP. Create a normal, unencrypted ZIP of the EmbeddingGemma_2 folder and try again.", e)
        } finally {
            if (!complete) staging?.delete()
        }
    }
}
