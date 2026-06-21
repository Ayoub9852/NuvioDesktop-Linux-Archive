package com.nuvio.app.features.player.desktop

import com.nuvio.app.desktop.DesktopRuntimeLog
import com.nuvio.app.features.player.desktop.mpv.MpvDesktopPlayerBackend
import com.nuvio.app.features.player.desktop.mpv.MpvRuntimeBootstrap
import com.nuvio.app.features.player.desktop.mpv.MpvRuntimeLocator
import com.nuvio.app.features.player.desktop.nativebridge.NativeBridgeDesktopPlayerBackend
import com.nuvio.app.features.player.desktop.nativebridge.NativeBridgeRuntimeLocator

internal object DesktopPlayerBackendFactory {
    private const val DESKTOP_BACKEND_PROPERTY = "nuvio.desktop.player.backend"
    private const val DESKTOP_BACKEND_ENV = "NUVIO_DESKTOP_PLAYER_BACKEND"
    private const val LEGACY_WINDOWS_BACKEND_PROPERTY = "nuvio.windows.player.backend"
    private const val LEGACY_WINDOWS_BACKEND_ENV = "NUVIO_WINDOWS_PLAYER_BACKEND"

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.contains("Windows", ignoreCase = true) == true
    private val osName: String
        get() = System.getProperty("os.name").orEmpty()

    fun createDesktopBackend(): DesktopPlayerBackend {
        val selection = DesktopPlayerBackendSelection.resolve()
        DesktopRuntimeLog.info("Selected desktop player backend os=$osName request=${selection.value} source=${selection.source}")
        return when (selection.backend) {
            DesktopPlayerBackendKind.None -> unavailable(
                backendName = "desktop-none",
                technicalMessage = "Desktop player backend disabled by configuration.",
                selection = selection,
            )
            DesktopPlayerBackendKind.ExternalMpv -> unavailable(
                backendName = "desktop-external-mpv",
                technicalMessage = "External mpv mode was explicitly requested, but no external mpv backend is enabled in this build.",
                selection = selection,
            )
            DesktopPlayerBackendKind.Mpv -> createMpvOrUnavailable(selection)
            DesktopPlayerBackendKind.Auto -> createMpvOrUnavailable(selection)
            DesktopPlayerBackendKind.Native -> {
                if (isWindows) createNativeWithMpvFallback(selection) else createMpvOrUnavailable(selection)
            }
        }
    }

    fun createWindowsBackend(): DesktopPlayerBackend = createDesktopBackend()

    private fun createMpvOrUnavailable(selection: DesktopPlayerBackendSelection): DesktopPlayerBackend =
        createMpvOrNull(selection) ?: unavailable(
            backendName = "desktop-mediamp-mpv",
            technicalMessage = "MPV backend is unavailable.",
            selection = selection,
        )

    private fun createNativeWithMpvFallback(selection: DesktopPlayerBackendSelection): DesktopPlayerBackend {
        val nativeRuntime = NativeBridgeRuntimeLocator.resolve()
        if (!nativeRuntime.available) {
            DesktopRuntimeLog.warn(
                "Windows native bridge unavailable before playback; trying MPV fallback diagnostics=${nativeRuntime.diagnostics}",
            )
            return createMpvOrUnavailable(selection)
        }
        return NativeBridgeDesktopPlayerBackend.create()
            .onSuccess {
                DesktopRuntimeLog.info("Selected player backend=${it.backendName} (source=${selection.source} request=${selection.value})")
            }
            .getOrElse { throwable ->
                DesktopRuntimeLog.error("Windows native bridge init failed; trying MPV fallback", throwable)
                createMpvOrUnavailable(selection)
            }
    }

    private fun createMpvOrNull(selection: DesktopPlayerBackendSelection): DesktopPlayerBackend? {
        val runtime = MpvRuntimeLocator.resolve()
        DesktopRuntimeLog.info(
            "MPV runtime lookup os=$osName selectedBackend=${selection.value} playbackMode=embedded externalProcess=false " +
                "diagnostics=${runtime.diagnostics}",
        )
        val bootstrap = MpvRuntimeBootstrap.apply(runtime)
        if (!bootstrap.success) {
            DesktopRuntimeLog.error("MPV runtime bootstrap failed diagnostics=${bootstrap.diagnostics}", bootstrap.error)
            return null
        }
        return MpvDesktopPlayerBackend.create(runtime)
            .onSuccess {
                DesktopRuntimeLog.info(
                    "Selected player backend=${it.backendName} playbackMode=embedded externalProcess=false " +
                        "(source=${selection.source} request=${selection.value})",
                )
            }
            .onFailure { DesktopRuntimeLog.error("MPV backend init failed", it) }
            .getOrNull()
    }

    private fun unavailable(
        backendName: String,
        technicalMessage: String,
        selection: DesktopPlayerBackendSelection,
    ): DesktopPlayerBackend {
        DesktopRuntimeLog.warn("Selected player backend=$backendName (source=${selection.source} request=${selection.value})")
        return UnavailableDesktopPlayerBackend(
            backendName = backendName,
            error = DesktopPlayerError.RuntimeUnavailable(
                backendName = backendName,
                technicalMessage = technicalMessage,
                suggestedAction = "Check the MPV runtime files and restart the app.",
            ),
        )
    }

    private enum class DesktopPlayerBackendKind {
        Auto,
        Mpv,
        Native,
        ExternalMpv,
        None,
    }

    private data class DesktopPlayerBackendSelection(
        val backend: DesktopPlayerBackendKind,
        val value: String,
        val source: String,
    ) {
        companion object {
            fun resolve(): DesktopPlayerBackendSelection {
                val property = System.getProperty(DESKTOP_BACKEND_PROPERTY)?.trim()?.lowercase()
                if (!property.isNullOrBlank()) return fromValue(property, "system-property:$DESKTOP_BACKEND_PROPERTY")
                val env = System.getenv(DESKTOP_BACKEND_ENV)?.trim()?.lowercase()
                if (!env.isNullOrBlank()) return fromValue(env, "env:$DESKTOP_BACKEND_ENV")
                val legacyProperty = System.getProperty(LEGACY_WINDOWS_BACKEND_PROPERTY)?.trim()?.lowercase()
                if (!legacyProperty.isNullOrBlank()) {
                    return fromValue(legacyProperty, "system-property:$LEGACY_WINDOWS_BACKEND_PROPERTY")
                }
                val legacyEnv = System.getenv(LEGACY_WINDOWS_BACKEND_ENV)?.trim()?.lowercase()
                if (!legacyEnv.isNullOrBlank()) return fromValue(legacyEnv, "env:$LEGACY_WINDOWS_BACKEND_ENV")
                return DesktopPlayerBackendSelection(DesktopPlayerBackendKind.Auto, "auto", "default")
            }

            private fun fromValue(value: String, source: String): DesktopPlayerBackendSelection =
                DesktopPlayerBackendSelection(
                    backend = when (value) {
                        "mpv" -> DesktopPlayerBackendKind.Mpv
                        "native" -> DesktopPlayerBackendKind.Native
                        "external-mpv" -> DesktopPlayerBackendKind.ExternalMpv
                        "none" -> DesktopPlayerBackendKind.None
                        else -> DesktopPlayerBackendKind.Auto
                    },
                    value = value,
                    source = source,
                )
        }
    }
}
