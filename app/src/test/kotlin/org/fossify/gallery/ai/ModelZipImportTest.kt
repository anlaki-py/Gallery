package org.fossify.gallery.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelZipImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val model = ByteArray(8192) { (it % 239).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
    private val name = AiModelZipImporter.MODEL_FILE
    private val revision = AiModelZipImporter.REVISION
    private val folder get() = File(temp.root, "private-model").apply { mkdirs() }

    private fun zip(vararg files: Pair<String, ByteArray>): File {
        val archive = temp.newFile("archive-${System.nanoTime()}.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            for ((path, data) in files) {
                output.putNextEntry(ZipEntry(path))
                output.write(data)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun prepare(archive: File, check: () -> Unit = {}) = AiModelZipImporter.prepare(
        archive, folder, model.size.toLong(), hash, check) { }

    @Test fun acceptsTheExactEdgeGalleryFolderLayoutAndIgnoresDatabasesAndCaches() {
        val archive = zip("EmbeddingGemma_2/$revision/$name" to model,
            "EmbeddingGemma_2/$revision/semantic_retrieval_gemma.db" to byteArrayOf(1, 2, 3),
            "EmbeddingGemma_2/$revision/cache/gpu.bin" to ByteArray(100))
        val result = prepare(archive)
        assertArrayEquals(model, result.readBytes())
        assertEquals(folder.canonicalFile, result.parentFile.canonicalFile)
        assertEquals(1, folder.listFiles()!!.size)
        assertFalse(File(folder, "EmbeddingGemma_2").exists())
    }

    @Test fun acceptsFlatModelZipAndAnAdditionalOuterFolder() {
        assertArrayEquals(model, prepare(zip(name to model)).readBytes())
        assertArrayEquals(model, prepare(zip("backup/Android/data/com.google.ai.edge.gallery/files/EmbeddingGemma_2/$revision/$name" to model)).readBytes())
        assertArrayEquals(model, prepare(zip("./EmbeddingGemma_2/$revision/$name" to model)).readBytes())
    }

    @Test fun choosesThePinnedRevisionWhenSeveralRevisionsAreArchived() {
        val archive = zip("EmbeddingGemma_2/older-revision/$name" to byteArrayOf(9),
            "EmbeddingGemma_2/$revision/$name" to model)
        assertArrayEquals(model, prepare(archive).readBytes())
    }

    @Test fun rejectsAmbiguousModelsInsteadOfChoosingAnArbitraryOne() {
        val archive = zip("a/$name" to model, "b/$name" to model)
        assertThrows(ModelImportException::class.java) { prepare(archive) }
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun rejectsMissingWrongSizedAndWrongHashModels() {
        val missing = zip("database.db" to model)
        val wrongSize = zip(name to byteArrayOf(1))
        val wrongHash = zip(name to model.copyOf().also { it[0] = 99 })
        for (archive in listOf(missing, wrongSize, wrongHash)) {
            assertThrows(ModelImportException::class.java) { prepare(archive) }
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun rejectsTraversalAbsoluteAndWindowsDrivePathsWithoutWritingThem() {
        for (unsafe in listOf("../outside", "/absolute/file", "C:/outside", "folder/../outside", "..\\outside")) {
            val archive = zip(unsafe to byteArrayOf(1), name to model)
            assertThrows(ModelImportException::class.java) { prepare(archive) }
            assertTrue(folder.listFiles()!!.isEmpty())
        }
        assertFalse(File(temp.root, "outside").exists())
    }

    @Test fun damagedArchivesAndInterruptedExtractionNeverReplaceAnExistingModel() {
        val target = File(folder, "installed-model").apply { writeText("existing working model") }
        val invalid = temp.newFile("invalid.zip").apply { writeText("not a zip") }
        assertThrows(ModelImportException::class.java) { prepare(invalid) }
        val archive = zip(name to model)
        assertThrows(CancellationException::class.java) {
            AiModelZipImporter.prepare(archive, folder, model.size.toLong(), hash, {}, { throw CancellationException() })
        }
        assertEquals("existing working model", target.readText())
        assertEquals(listOf(target.name), folder.listFiles()!!.map { it.name })
    }

    @Test fun verifiedModelIsPublishedAtomicallyWithItsMarker() {
        val target = File(folder, "installed-model").apply { writeText("old model") }
        val marker = File(folder, "verified.sha256").apply { writeText("old hash") }
        val candidate = prepare(zip(name to model))
        AiModelStore.publishVerified(candidate, target, marker, hash)
        assertArrayEquals(model, target.readBytes())
        assertEquals(hash, marker.readText())
        assertFalse(candidate.exists())
        assertFalse(File(folder, "verified.sha256.tmp").exists())
    }

    @Test fun copyingDocumentsHandlesUnknownSizeWithoutLoadingTheArchiveIntoMemory() {
        val data = zip(name to model).readBytes()
        val target = File(temp.root, "private-copy.zip")
        AiModelZipImporter.copyArchive(ByteArrayInputStream(data), target, null,
            modelSize = model.size.toLong(), checkCancelled = {}, progress = {})
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun oversizedOrInterruptedDocumentCopiesLeaveNoTemporaryZip() {
        val data = ByteArray(100)
        val target = File(temp.root, "private-copy.zip")
        assertThrows(ModelImportException::class.java) {
            AiModelZipImporter.copyArchive(ByteArrayInputStream(data), target, null,
                modelSize = 1, maxArchiveBytes = 50, checkCancelled = {}, progress = {})
        }
        assertFalse(target.exists())
        assertThrows(CancellationException::class.java) {
            AiModelZipImporter.copyArchive(ByteArrayInputStream(data), target, null,
                modelSize = 1, checkCancelled = { throw CancellationException() }, progress = {})
        }
        assertFalse(target.exists())
    }

    @Test fun storageAndDeclaredSizeErrorsLeaveNoTemporaryZip() {
        val target = File(temp.root, "private-copy.zip")
        assertThrows(ModelImportException::class.java) {
            AiModelZipImporter.copyArchive(ByteArrayInputStream(model), target, model.size.toLong(),
                modelSize = 1, freeBytes = { 0 }, checkCancelled = {}, progress = {})
        }
        assertFalse(target.exists())
        assertThrows(ModelImportException::class.java) {
            AiModelZipImporter.copyArchive(ByteArrayInputStream(model), target, model.size.toLong() + 1,
                modelSize = 1, checkCancelled = {}, progress = {})
        }
        assertFalse(target.exists())
    }
}
