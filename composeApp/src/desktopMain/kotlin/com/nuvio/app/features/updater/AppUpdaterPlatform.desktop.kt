package com.nuvio.app.features.updater

import com.nuvio.app.desktop.DesktopPreferences
import java.awt.Desktop
import java.net.URI

actual object AppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val ignoredTagKey = "ignored_release_tag"
    private const val nightlyBuildModeKey = "nightly_build_mode"

    actual val isSupported: Boolean = true
    actual val supportsAutoCheck: Boolean = false
    actual val supportsDownloadAndInstall: Boolean = false
    actual val gitHubOwner: String = "CreepsoOff"
    actual val gitHubRepo: String = "NuvioDesktop"
    actual val stableReleaseChannelBranch: String? = null
    actual val nightlyReleaseTag: String? = "pre"

    actual fun getSupportedAbis(): List<String> = emptyList()

    actual fun getIgnoredTag(): String? =
        DesktopPreferences.getString(preferencesName, ignoredTagKey)

    actual fun setIgnoredTag(tag: String?) {
        DesktopPreferences.putNullableString(preferencesName, ignoredTagKey, tag)
    }

    actual fun getNightlyBuildMode(): Boolean =
        DesktopPreferences.getBoolean(preferencesName, nightlyBuildModeKey) ?: false

    actual fun setNightlyBuildMode(enabled: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, nightlyBuildModeKey, enabled)
    }

    actual suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = Result.failure(IllegalStateException("In-app updates are unavailable on this build."))

    actual fun canRequestPackageInstalls(): Boolean = false

    actual fun openUnknownSourcesSettings() = Unit

    actual fun installDownloadedApk(path: String): Result<Unit> =
        Result.failure(IllegalStateException("In-app updates are unavailable on this build."))

    actual fun openReleasePage(url: String): Result<Unit> = runCatching {
        val desktop = checkNotNull(Desktop.getDesktop()) { "Desktop browser integration is unavailable." }
        check(desktop.isSupported(Desktop.Action.BROWSE)) { "Opening links is unavailable on this system." }
        desktop.browse(URI(url))
    }
}
