package com.nuvio.app.core.auth

import com.nuvio.app.desktop.DesktopConfigPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal actual object AuthStorage {
    private val authDir: Path by lazy {
        DesktopConfigPaths.nuvioConfigDir.resolve("auth").apply { createDirectories() }
    }
    private val anonymousUserIdFile: Path by lazy {
        authDir.resolve("anonymous_user_id")
    }

    actual fun loadAnonymousUserId(): String? =
        anonymousUserIdFile.takeIf { it.exists() }
            ?.readText(StandardCharsets.UTF_8)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    actual fun saveAnonymousUserId(userId: String) {
        anonymousUserIdFile.writeText(
            text = userId,
            charset = StandardCharsets.UTF_8,
            options = arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        )
    }

    actual fun clearAnonymousUserId() {
        anonymousUserIdFile.deleteIfExists()
    }
}
