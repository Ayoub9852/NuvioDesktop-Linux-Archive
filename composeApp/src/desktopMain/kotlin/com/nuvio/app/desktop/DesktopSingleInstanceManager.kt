package com.nuvio.app.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object DesktopSingleInstanceManager {
    private const val Host = "127.0.0.1"
    private const val Port = 45822

    sealed interface StartResult {
        data class Primary(val close: () -> Unit) : StartResult
        data object Secondary : StartResult
    }

    fun startPrimaryReceiver(
        onUrlReceived: (String) -> Unit,
        onFocusRequested: () -> Unit,
    ): StartResult {
        val serverSocket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(Host), Port))
            }
        } catch (_: BindException) {
            return StartResult.Secondary
        }

        val running = AtomicBoolean(true)
        val worker = thread(
            isDaemon = true,
            name = "nuvio-single-instance-ipc",
        ) {
            while (running.get()) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                socket.use { acceptedSocket ->
                    handleIncomingConnection(
                        socket = acceptedSocket,
                        onUrlReceived = onUrlReceived,
                        onFocusRequested = onFocusRequested,
                    )
                }
            }
        }

        val closer = {
            running.set(false)
            runCatching { serverSocket.close() }
            worker.interrupt()
        }
        return StartResult.Primary(close = closer)
    }

    fun forwardToPrimary(urlArgs: List<String>): Boolean {
        val payload = if (urlArgs.isEmpty()) listOf("FOCUS") else urlArgs.map { "URL|$it" } + "FOCUS"
        return runCatching {
            Socket(Host, Port).use { socket ->
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                payload.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
                writer.flush()
            }
            true
        }.getOrDefault(false)
    }

    private fun handleIncomingConnection(
        socket: Socket,
        onUrlReceived: (String) -> Unit,
        onFocusRequested: () -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: return
            when {
                line == "FOCUS" -> onFocusRequested()
                line.startsWith("URL|") -> onUrlReceivedSafe(line.removePrefix("URL|"), onUrlReceived)
            }
        }
    }

    private fun onUrlReceivedSafe(
        url: String,
        onUrlReceived: (String) -> Unit,
    ) {
        if (!url.startsWith("nuvio://", ignoreCase = true)) return
        onUrlReceived(url)
    }
}
