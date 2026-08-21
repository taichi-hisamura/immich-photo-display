package com.dav3.immichframe.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** Adds the API key only to media requests for the configured Immich origin. */
internal class ImmichMediaAuthInterceptor(
    private val serverUrl: () -> String,
    private val apiKey: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val configured = serverUrl().trimEnd('/').toHttpUrlOrNull()
        val configuredPath = configured?.encodedPath?.trimEnd('/').orEmpty()
        val isImmichMediaRequest = configured != null &&
            request.url.scheme == configured.scheme &&
            request.url.host == configured.host &&
            request.url.port == configured.port &&
            request.url.encodedPath.startsWith("$configuredPath/api/assets/")
        val key = if (isImmichMediaRequest) apiKey() else ""

        val authenticated = if (key.isNotBlank()) {
            request.newBuilder().header(API_KEY_HEADER, key).build()
        } else {
            request
        }
        return chain.proceed(authenticated)
    }

    companion object {
        const val API_KEY_HEADER = "x-api-key"
    }
}

internal fun buildImmichMediaClient(
    serverUrl: () -> String,
    apiKey: () -> String,
): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor(
        ImmichMediaAuthInterceptor(
            serverUrl = serverUrl,
            apiKey = apiKey,
        ),
    )
    .build()
