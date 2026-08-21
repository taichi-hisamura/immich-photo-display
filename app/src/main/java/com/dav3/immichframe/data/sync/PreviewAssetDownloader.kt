package com.dav3.immichframe.data.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Downloads one bounded Immich preview without exposing the API key in the URL. */
internal class PreviewAssetDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun download(
        serverUrl: String,
        assetId: String,
        apiKey: String,
        destination: File,
    ): Long {
        val url = "${serverUrl.trimEnd('/')}/api/assets/$assetId/thumbnail?size=preview"
        val request = Request.Builder()
            .url(url)
            .header(API_KEY_HEADER, apiKey)
            .get()
            .build()
        val parent = destination.parentFile
            ?: throw IOException("Preview destination has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Failed to create preview cache directory")
        }
        val temporary = File(parent, "${destination.name}.${UUID.randomUUID()}.tmp")

        try {
            client.newCall(request).execute().use { response ->
                if (response.isRedirect || response.priorResponse != null) {
                    throw IOException("Preview request redirected; refusing possible original download")
                }
                if (!response.isSuccessful) {
                    throw IOException("Preview download failed: HTTP ${response.code}")
                }
                if (response.request.url.encodedPath.endsWith("/original")) {
                    throw IOException("Preview request resolved to the original endpoint")
                }

                val contentType = response.body.contentType()
                if (contentType != null && contentType.type != "image") {
                    throw IOException("Preview response is not an image: $contentType")
                }
                val declaredLength = response.body.contentLength()
                if (declaredLength > maxBytes) {
                    throw IOException("Preview exceeds byte limit: $declaredLength/$maxBytes")
                }

                response.body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > maxBytes) {
                                throw IOException("Preview exceeds byte limit while streaming: $total/$maxBytes")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }

                if (temporary.length() == 0L) {
                    throw IOException("Preview response was empty")
                }
                replaceAtomically(temporary, destination)
                return destination.length()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        internal const val API_KEY_HEADER = "x-api-key"
        internal const val DEFAULT_MAX_BYTES = 5L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
