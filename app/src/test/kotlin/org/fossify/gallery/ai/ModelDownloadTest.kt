package org.fossify.gallery.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class ModelDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val target get() = File(temp.root, "model")
    private val marker get() = File(temp.root, "verified")
    private val partial get() = File(temp.root, "model.part")

    private class Response(private val code: Int, private val content: ByteArray, private val range: String? = null,
        private val interrupted: Boolean = false) : HttpURLConnection(URL("https://example.invalid/model")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = content.size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
        override fun getInputStream(): InputStream = if (!interrupted) ByteArrayInputStream(content) else object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position >= 100) throw IOException("Connection interrupted")
                return content[position++].toInt() and 255
            }
        }
    }

    private fun transfer(response: Response, check: () -> Unit = {}) = AiModelStore.transfer(temp.root, target, marker,
        bytes.size.toLong(), hash, { response }, check) { _, _ -> }

    @Test fun installsOnlyAfterFullHashVerification() {
        transfer(Response(200, bytes))
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(hash, marker.readText())
        assertFalse(partial.exists())
    }

    @Test fun resumesAnInterruptedDownloadWithAnExactRange() {
        partial.writeBytes(bytes.copyOfRange(0, 300))
        val response = Response(206, bytes.copyOfRange(300, bytes.size), "bytes 300-4095/4096")
        transfer(response)
        assertEquals("bytes=300-", response.getRequestProperty("Range"))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun serverIgnoringRangeRestartsWithoutAppending() {
        partial.writeBytes(byteArrayOf(9, 9, 9))
        transfer(Response(200, bytes))
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun wrongChecksumNeverCreatesLiveModel() {
        val wrong = bytes.copyOf().also { it[0] = 99 }
        assertThrows(ModelDownloadException::class.java) { transfer(Response(200, wrong)) }
        assertFalse(target.exists())
        assertFalse(marker.exists())
        assertFalse(partial.exists())
    }

    @Test fun wrongResumeOffsetIsRejectedAndPartialDiscarded() {
        partial.writeBytes(bytes.copyOfRange(0, 300))
        assertThrows(ModelDownloadException::class.java) {
            transfer(Response(206, bytes.copyOfRange(200, bytes.size), "bytes 200-4095/4096"))
        }
        assertFalse(target.exists())
        assertFalse(partial.exists())
    }

    @Test fun interruptedAndCancelledDownloadsNeverPublishAnUnverifiedModel() {
        assertThrows(IOException::class.java) { transfer(Response(200, bytes, interrupted = true)) }
        assertTrue(partial.length() in 1 until bytes.size.toLong())
        assertFalse(target.exists())
        assertThrows(CancellationException::class.java) { transfer(Response(200, bytes)) { throw CancellationException() } }
        assertFalse(target.exists())
    }

    @Test fun completePartialIsVerifiedWithoutNetwork() {
        partial.writeBytes(bytes)
        AiModelStore.transfer(temp.root, target, marker, bytes.size.toLong(), hash,
            { throw AssertionError("Should not connect") }, {}) { _, _ -> }
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun hostErrorDoesNotDiscardPartialProgress() {
        partial.writeBytes(bytes.copyOfRange(0, 500))
        val exception = assertThrows(ModelDownloadException::class.java) { transfer(Response(503, byteArrayOf())) }
        assertTrue(exception.retryable)
        assertEquals(500L, partial.length())
        assertFalse(target.exists())
    }

    @Test fun crashAfterPromotionRecoversByVerifyingExistingBytes() {
        target.writeBytes(bytes)
        AiModelStore.transfer(temp.root, target, marker, bytes.size.toLong(), hash,
            { throw AssertionError("Should not connect") }, {}) { _, _ -> }
        assertEquals(hash, marker.readText())
        assertArrayEquals(bytes, target.readBytes())
    }
}
