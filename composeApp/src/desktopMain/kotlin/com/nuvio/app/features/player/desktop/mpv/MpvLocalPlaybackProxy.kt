package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors

internal class MpvLocalPlaybackProxy private constructor(
    private val originalUri: URI,
    private val originalHeaders: Map<String, String>,
    private val server: HttpServer,
    private val client: HttpClient,
) : AutoCloseable {
    val playbackUrl: String =
        "http://127.0.0.1:${server.address.port}/stream"

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nuvio-mpv-local-proxy").apply { isDaemon = true }
    }

    fun start(): MpvLocalPlaybackProxy {
        server.executor = executor
        server.createContext("/stream", ::handleStream)
        server.start()
        DesktopRuntimeLog.info(
            "MPV_NETWORK localProxy started bind=127.0.0.1 port=${server.address.port} " +
                "targetHost=${originalUri.host ?: "unknown"} headersPresent=${originalHeaders.isNotEmpty()}",
        )
        return this
    }

    override fun close() {
        runCatching { server.stop(0) }
        runCatching { executor.shutdownNow() }
        DesktopRuntimeLog.info("MPV_NETWORK localProxy stopped port=${server.address.port}")
    }

    private fun handleStream(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase(Locale.ROOT)
        if (method != "GET" && method != "HEAD") {
            exchange.sendText(405, "Method not allowed")
            return
        }

        val range = exchange.requestHeaders["Range"]?.firstOrNull()
        DesktopRuntimeLog.info(
            "MPV_NETWORK localProxy request method=$method rangePresent=${range != null} " +
                "targetHost=${originalUri.host ?: "unknown"}",
        )

        runCatching {
            val upstreamRequest = HttpRequest.newBuilder(originalUri)
                .timeout(Duration.ofSeconds(45))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .copyPlaybackHeaders(originalHeaders)
                .copyForwardedHeaders(exchange)
                .build()
            val response = client.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream())
            exchange.responseHeaders.copyResponseHeaders(response.headers().map())
            val contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L)
            if (method == "HEAD") {
                exchange.sendResponseHeaders(response.statusCode(), -1L)
                exchange.close()
                return
            }
            val responseLength = if (contentLength >= 0L) contentLength else 0L
            exchange.sendResponseHeaders(response.statusCode(), responseLength)
            response.body().use { input ->
                exchange.responseBody.use { output ->
                    input.copyTo(output)
                }
            }
        }.onFailure { throwable ->
            DesktopRuntimeLog.error(
                "MPV_NETWORK localProxy upstream failed method=$method targetHost=${originalUri.host ?: "unknown"}",
                throwable,
            )
            if (!exchange.responseHeaders.containsKey("Content-Type")) {
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            }
            runCatching { exchange.sendText(502, "Nuvio playback proxy upstream request failed") }
        }
    }

    private fun HttpRequest.Builder.copyPlaybackHeaders(headers: Map<String, String>): HttpRequest.Builder {
        headers.forEach { (rawName, rawValue) ->
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name.isNotEmpty() && value.isNotEmpty() && !name.isHopByHopHeader()) {
                header(name, value)
            }
        }
        return this
    }

    private fun HttpRequest.Builder.copyForwardedHeaders(exchange: HttpExchange): HttpRequest.Builder {
        exchange.requestHeaders.forEach { (rawName, values) ->
            val name = rawName.trim()
            if (!name.isForwardablePlaybackHeader()) return@forEach
            values.orEmpty().forEach { value ->
                val trimmed = value.trim()
                if (trimmed.isNotEmpty()) header(name, trimmed)
            }
        }
        return this
    }

    private fun com.sun.net.httpserver.Headers.copyResponseHeaders(headers: Map<String, List<String>>) {
        headers.forEach { (rawName, values) ->
            val name = rawName.trim()
            if (name.isEmpty() || name.isHopByHopHeader()) return@forEach
            values.orEmpty().forEach { value ->
                if (value.isNotBlank()) add(name, value)
            }
        }
    }

    private fun HttpExchange.sendText(status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        try {
            responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun String.isForwardablePlaybackHeader(): Boolean {
        if (isHopByHopHeader()) return false
        return equals("Range", ignoreCase = true) ||
            equals("If-Range", ignoreCase = true) ||
            equals("Accept", ignoreCase = true) ||
            equals("Accept-Encoding", ignoreCase = true) ||
            equals("User-Agent", ignoreCase = true) ||
            equals("Icy-MetaData", ignoreCase = true)
    }

    private fun String.isHopByHopHeader(): Boolean =
        equals("Connection", ignoreCase = true) ||
            equals("Keep-Alive", ignoreCase = true) ||
            equals("Proxy-Authenticate", ignoreCase = true) ||
            equals("Proxy-Authorization", ignoreCase = true) ||
            equals("TE", ignoreCase = true) ||
            equals("Trailer", ignoreCase = true) ||
            equals("Transfer-Encoding", ignoreCase = true) ||
            equals("Upgrade", ignoreCase = true) ||
            equals("Host", ignoreCase = true) ||
            equals("Content-Length", ignoreCase = true)

    companion object {
        fun create(originalUrl: String, originalHeaders: Map<String, String>): MpvLocalPlaybackProxy? {
            val uri = runCatching { URI(originalUrl) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            if (scheme != "http" && scheme != "https") return null
            val bindAddress = InetAddress.getByName("127.0.0.1")
            val server = HttpServer.create(InetSocketAddress(bindAddress, 0), 0)
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
            return MpvLocalPlaybackProxy(uri, originalHeaders, server, client)
        }
    }
}
