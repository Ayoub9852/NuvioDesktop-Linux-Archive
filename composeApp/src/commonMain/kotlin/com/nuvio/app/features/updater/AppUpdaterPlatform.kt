package com.nuvio.app.features.updater

expect object AppUpdaterPlatform {
    val isSupported: Boolean
    val supportsAutoCheck: Boolean
    val supportsDownloadAndInstall: Boolean
    val gitHubOwner: String
    val gitHubRepo: String
    val stableReleaseChannelBranch: String?
    val nightlyReleaseTag: String?

    fun getSupportedAbis(): List<String>

    fun getIgnoredTag(): String?

    fun setIgnoredTag(tag: String?)

    fun getNightlyBuildMode(): Boolean

    fun setNightlyBuildMode(enabled: Boolean)

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String>

    fun canRequestPackageInstalls(): Boolean

    fun openUnknownSourcesSettings()

    fun installDownloadedApk(path: String): Result<Unit>

    fun openReleasePage(url: String): Result<Unit>
}
