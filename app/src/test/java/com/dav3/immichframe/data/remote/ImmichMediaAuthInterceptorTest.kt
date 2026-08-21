package com.dav3.immichframe.data.remote

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ImmichMediaAuthInterceptorTest {
    private lateinit var immich: MockWebServer
    private lateinit var other: MockWebServer

    @Before
    fun setUp() {
        immich = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        immich.close()
        other.close()
    }

    @Test
    fun `adds key to configured Immich media request`() {
        immich.enqueue(MockResponse.Builder().code(200).build())
        client().newCall(
            Request.Builder()
                .url(immich.url("/api/assets/id/thumbnail?size=preview"))
                .build(),
        ).execute().close()

        assertEquals(
            "secret-key",
            immich.takeRequest().headers[ImmichMediaAuthInterceptor.API_KEY_HEADER],
        )
    }

    @Test
    fun `does not leak key to a different origin`() {
        other.enqueue(MockResponse.Builder().code(200).build())
        client().newCall(
            Request.Builder().url(other.url("/api/assets/id/thumbnail")).build(),
        ).execute().close()

        assertNull(other.takeRequest().headers[ImmichMediaAuthInterceptor.API_KEY_HEADER])
    }

    @Test
    fun `does not add key to non-media path on Immich origin`() {
        immich.enqueue(MockResponse.Builder().code(200).build())
        client().newCall(
            Request.Builder().url(immich.url("/unrelated/image.jpg")).build(),
        ).execute().close()

        assertNull(immich.takeRequest().headers[ImmichMediaAuthInterceptor.API_KEY_HEADER])
    }

    @Test
    fun `does not follow redirect to a different origin`() {
        immich.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", other.url("/capture"))
                .build(),
        )
        other.enqueue(MockResponse.Builder().code(200).build())

        val response = client().newCall(
            Request.Builder()
                .url(immich.url("/api/assets/id/thumbnail?size=preview"))
                .build(),
        ).execute()

        assertEquals(302, response.use { it.code })
        assertEquals(0, other.requestCount)
    }

    private fun client(): OkHttpClient = buildImmichMediaClient(
        serverUrl = { immich.url("/").toString() },
        apiKey = { "secret-key" },
    )
}
