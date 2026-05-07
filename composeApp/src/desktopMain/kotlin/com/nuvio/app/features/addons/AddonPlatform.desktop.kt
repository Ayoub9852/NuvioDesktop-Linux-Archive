package com.nuvio.app.features.addons

import com.nuvio.app.desktop.DesktopPreferences
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual object AddonStorage {
    private const val preferencesName = "nuvio_addons"
    private const val addonUrlsKey = "installed_manifest_urls"

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        DesktopPreferences.getString(preferencesName, "${addonUrlsKey}_$profileId")
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        DesktopPreferences.putString(
            preferencesName,
            "${addonUrlsKey}_$profileId",
            urls.joinToString(separator = "\n"),
        )
    }
}

private const val maxRawResponseBodyChars = 1024 * 1024
private const val truncationSuffix = "\n...[truncated]"

private const val addonRequestTimeoutMs = 60_000

private data class DesktopAddonHttpResponse(
    val statusCode: Int,
    val statusText: String,
    val url: String,
    val body: String,
    val headers: Map<String, List<String>>,
)

// Stremio/Torrentio addons embed configuration in the URL path using `|` as a
// separator. Torrentio's router requires the literal pipe — replacing it with
// `%7C` returns 404. HttpURLConnection accepts `|` in the URL, while the
// stricter java.net.http.HttpClient + URI.create() does not. Re-decode any
// `%7C` to `|` here so URLs that arrived already-encoded (e.g. synced from
// Android via Supabase or pasted from a configurator) still hit the right
// route on Desktop.
private fun normalizeDesktopAddonRequestUrl(url: String): String =
    url.trim()
        .replace("%7C", "|", ignoreCase = true)

// `URL(String)` is deprecated since JDK 20 in favor of `URI.create(...).toURL()`,
// but URI is the strict RFC 3986 parser that rejects `|` (and that mismatch is
// exactly the bug we are working around for Torrentio-style addon URLs). We
// keep the legacy permissive parser on purpose.
@Suppress("DEPRECATION")
private fun openAddonRequestUrl(rawUrl: String): URL =
    URL(normalizeDesktopAddonRequestUrl(rawUrl))

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private suspend fun executeRequest(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
): DesktopAddonHttpResponse = withContext(Dispatchers.IO) {
    val connection = (openAddonRequestUrl(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method.uppercase()
        connectTimeout = addonRequestTimeoutMs
        readTimeout = addonRequestTimeoutMs
        instanceFollowRedirects = true

        headers.withoutAcceptEncoding().forEach { (key, value) ->
            setRequestProperty(key, value)
        }

        if (requestAllowsBody(method)) {
            doOutput = true
            outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
        }
    }

    try {
        val statusCode = connection.responseCode
        val statusText = connection.responseMessage.orEmpty()
        val responseBody = readResponseBody(connection, statusCode)

        DesktopAddonHttpResponse(
            statusCode = statusCode,
            statusText = statusText,
            url = connection.url.toString(),
            body = responseBody,
            headers = connection.headerFields
                .filterKeys { key -> key != null }
                .mapKeys { (key, _) -> key!!.lowercase() },
        )
    } finally {
        connection.disconnect()
    }
}

private fun readResponseBody(
    connection: HttpURLConnection,
    statusCode: Int,
): String {
    val stream = if (statusCode in 200..299) {
        runCatching { connection.inputStream }.getOrNull()
    } else {
        connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
    }

    return stream
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        .orEmpty()
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String {
    val response = executeRequest(method, url, headers, body)
    val payload = response.body
    if (response.statusCode !in 200..299) {
        error("Request failed with HTTP ${response.statusCode}")
    }
    if (payload.isBlank()) {
        throw IllegalStateException("Empty response body")
    }
    return payload
}

actual suspend fun httpGetText(url: String): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
): RawHttpResponse {
    val response = executeRequest(method, url, headers, body)
    val payload = response.body
    val limitedPayload = if (payload.length > maxRawResponseBodyChars) {
        payload.take(maxRawResponseBodyChars) + truncationSuffix
    } else {
        payload
    }
    return RawHttpResponse(
        status = response.statusCode,
        statusText = response.statusText,
        url = response.url,
        body = limitedPayload,
        headers = response.headers.entries.associate { (key, values) ->
            key.lowercase() to values.joinToString(",")
        },
    )
}
