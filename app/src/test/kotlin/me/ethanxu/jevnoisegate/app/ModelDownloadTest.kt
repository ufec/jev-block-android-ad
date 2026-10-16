package me.ethanxu.jevnoisegate.app

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadTest {
    @Test fun interruptedTransferRetainsPartialFileForResume() = inDirectory { dir ->
        try {
            ModelAssets.download(dir, listOf(asset), { response(bytes) }) {
                throw kotlinx.coroutines.CancellationException("pause")
            }
            fail("Transfer should stop")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(File(dir, asset.name).exists())
        assertArrayEquals(bytes, File(dir, "${asset.name}.part").readBytes())
        ModelAssets.download(dir, listOf(asset), { error("Complete partial file should not redownload") }) { }
        assertTrue(ModelAssets.valid(File(dir, asset.name), asset))
    }

    private val bytes = "model-test-content".toByteArray()
    private val asset = ModelAssets.Asset("model.bin", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun response(body: ByteArray, code: Int = 200, range: String? = null) =
        object : HttpURLConnection(URL("https://example.com/model")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode() = code
            override fun getInputStream() = ByteArrayInputStream(body)
            override fun getHeaderField(name: String?) = if (name == "Content-Range") range else null
        }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("laya-download-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun completeDownloadChecksHashAndInstalls() = inDirectory { dir ->
        ModelAssets.download(dir, listOf(asset), { response(bytes) }) { }
        assertTrue(ModelAssets.valid(File(dir, asset.name), asset))
        assertFalse(File(dir, "${asset.name}.part").exists())
    }

    @Test fun rangeResumeAppendsOnlyRemainingBytes() = inDirectory { dir ->
        File(dir, "${asset.name}.part").writeBytes(bytes.take(5).toByteArray())
        val connection = response(bytes.drop(5).toByteArray(), 206, "bytes 5-${bytes.lastIndex}/${bytes.size}")
        ModelAssets.download(dir, listOf(asset), { connection }) { }
        assertEquals("bytes=5-", connection.getRequestProperty("Range"))
        assertTrue(ModelAssets.valid(File(dir, asset.name), asset))
    }

    @Test fun serverIgnoringRangeRestartsInsteadOfAppending() = inDirectory { dir ->
        File(dir, "${asset.name}.part").writeBytes(bytes.take(5).toByteArray())
        ModelAssets.download(dir, listOf(asset), { response(bytes) }) { }
        assertTrue(ModelAssets.valid(File(dir, asset.name), asset))
    }

    @Test fun corruptedContentIsNeverInstalled() = inDirectory { dir ->
        try {
            ModelAssets.download(dir, listOf(asset), { response(ByteArray(bytes.size)) }) { }
            fail("Corrupt model accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(File(dir, asset.name).exists())
        assertFalse(File(dir, "${asset.name}.part").exists())
    }

    @Test fun validExistingFileSkipsNetwork() = inDirectory { dir ->
        File(dir, asset.name).writeBytes(bytes)
        ModelAssets.download(dir, listOf(asset), { error("Unexpected network request") }) { }
        assertTrue(ModelAssets.valid(File(dir, asset.name), asset))
    }
}
