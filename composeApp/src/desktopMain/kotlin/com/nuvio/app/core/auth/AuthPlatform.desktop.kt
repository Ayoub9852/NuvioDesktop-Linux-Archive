package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.desktop.DesktopConfigPaths
import io.github.jan.supabase.auth.AuthConfig
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

internal actual object AuthPlatform {
    actual fun configureAuth(config: AuthConfig) {
        config.sessionManager = DesktopSupabaseSessionManager
        config.autoLoadFromStorage = true
        config.autoSaveToStorage = true
    }
}

private object DesktopSupabaseSessionManager : SessionManager {
    private val log = Logger.withTag("DesktopSupabaseSessionManager")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val sessionFile: Path by lazy {
        DesktopConfigPaths.nuvioConfigDir
            .resolve("auth")
            .apply { createDirectories() }
            .resolve("supabase_session.json")
    }

    override suspend fun saveSession(session: UserSession) {
        sessionFile.writeText(
            text = json.encodeToString(UserSession.serializer(), session),
            charset = StandardCharsets.UTF_8,
            options = arrayOf(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ),
        )
    }

    override suspend fun loadSession(): UserSession? {
        if (!sessionFile.exists()) return null
        return runCatching {
            json.decodeFromString(UserSession.serializer(), sessionFile.readText(StandardCharsets.UTF_8))
        }.onFailure { error ->
            log.e(error) { "Failed to load Supabase auth session from $sessionFile" }
            runCatching { sessionFile.deleteIfExists() }
        }.getOrNull()
    }

    override suspend fun deleteSession() {
        sessionFile.deleteIfExists()
    }
}
