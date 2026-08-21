package com.dav3.immichframe.data.sync

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PreviewAssetDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `downloads preview with header and no key in URL`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .body("preview-bytes")
                .build(),
        )
        val destination = temporaryFolder.newFile("preview")
        val downloader = downloader(maxBytes = 1024)

        val size = downloader.download(
            serverUrl = server.url("/").toString(),
            assetId = "asset-id",
            apiKey = "secret-key",
            destination = destination,
        )

        val request = server.takeRequest()
        assertEquals("secret-key", request.headers[PreviewAssetDownloader.API_KEY_HEADER])
        assertFalse(request.url.toString().contains("secret-key"))
        assertEquals("preview", request.url.queryParameter("size"))
        assertEquals("preview-bytes".length.toLong(), size)
        assertEquals("preview-bytes", destination.readText())
    }

    @Test
    fun `rejects redirect to original`() {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/api/assets/asset-id/original")
                .build(),
        )

        assertThrows(IOException::class.java) {
            downloader(maxBytes = 1024).download(
                serverUrl = server.url("/").toString(),
                assetId = "asset-id",
                apiKey = "secret-key",
                destination = temporaryFolder.newFile("redirect"),
            )
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `rejects streamed body above byte limit and preserves existing preview`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .setHeader("Transfer-Encoding", "chunked")
                .body("body-over-limit")
                .build(),
        )
        val destination = temporaryFolder.newFile("existing").apply {
            writeText("old-preview")
        }

        assertThrows(IOException::class.java) {
            downloader(maxBytes = 4).download(
                serverUrl = server.url("/").toString(),
                assetId = "asset-id",
                apiKey = "secret-key",
                destination = destination,
            )
        }
        assertEquals("old-preview", destination.readText())
    }

    private fun downloader(maxBytes: Long): PreviewAssetDownloader = PreviewAssetDownloader(
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
        maxBytes = maxBytes,
    )
}
