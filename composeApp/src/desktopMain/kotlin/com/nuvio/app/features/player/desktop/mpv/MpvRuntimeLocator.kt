package com.nuvio.app.features.player.desktop.mpv

import java.io.File

internal data class MpvRuntimeResolution(
    val directory: File?,
    val bridgeFile: File?,
    val systemMpvFile: File?,
    val checkedDirectories: List<String>,
    val diagnostics: String,
) {
    val libraryFile: File?
        get() = bridgeFile

    val available: Boolean get() = bridgeFile?.isFile == true
}

internal object MpvRuntimeLocator {
    private val osName: String
        get() = System.getProperty("os.name").orEmpty()
    private val isWindows: Boolean
        get() = osName.contains("Windows", ignoreCase = true)
    private val isLinux: Boolean
        get() = osName.contains("Linux", ignoreCase = true)
    private val isMac: Boolean
        get() = osName.contains("Mac", ignoreCase = true) || osName.contains("Darwin", ignoreCase = true)

    fun resolve(): MpvRuntimeResolution {
        if (isLinux) {
            return resolveLinux()
        }
        if (isMac) {
            return resolveMac()
        }
        if (!isWindows) {
            return MpvRuntimeResolution(
                directory = null,
                bridgeFile = null,
                systemMpvFile = null,
                checkedDirectories = emptyList(),
                diagnostics = "os=$osName unsupported desktop OS for MPV runtime lookup",
            )
        }

        return resolveWindows()
    }

    private fun resolveWindows(): MpvRuntimeResolution {
        val candidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) candidates.putIfAbsent(label, file)
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        val appDir = resourcesDir?.parentFile
        add("appDir/native", appDir?.resolve("native"))
        add("resourcesDir/native", resourcesDir?.resolve("native"))

        add("env:NUVIO_MEDIAMP_RUNTIME_DIR", System.getenv("NUVIO_MEDIAMP_RUNTIME_DIR")?.toFileOrNull())
        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR", dir)
            add("env:NUVIO_MPV_DIR/bin", dir.resolve("bin"))
        }
        add("property:nuvio.mediamp.runtime.dir", System.getProperty("nuvio.mediamp.runtime.dir")?.toFileOrNull())
        System.getProperty("nuvio.mpv.dir")?.toFileOrNull()?.let { dir ->
            add("property:nuvio.mpv.dir", dir)
            add("property:nuvio.mpv.dir/bin", dir.resolve("bin"))
        }

        javaLibraryPathEntries().forEach { entry ->
            val dir = File(entry)
            add("java.library.path:${dir.safePath()}", dir)
            add("java.library.path/native:${dir.safePath()}", dir.resolve("native"))
        }

        pathEntries().forEach { entry ->
            add("PATH:${entry.safePath()}", entry)
        }

        if (devLookupEnabled()) {
            System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { userDir ->
                val base = File(userDir)
                add("dev:app/native", base.resolve("app/native"))
                add("dev:native", base.resolve("native"))
                add("dev:mediamp/build-ci", base.resolve("mediamp/mediamp-mpv/build-ci"))
                add("dev:mediamp/build-ci/Release", base.resolve("mediamp/mediamp-mpv/build-ci/Release"))
                add("dev:mediamp/libmpv", base.resolve("mediamp/mediamp-mpv/libmpv/lib/windows/x86_64"))
            }
        }

        val checked = candidates.map { (label, dir) ->
            "$label=${dir.safePath()} exists=${dir.isDirectory} mediampv=${dir.resolve("mediampv.dll").isFile}"
        }
        val selected = candidates.values.firstOrNull { it.resolve("mediampv.dll").isFile }
        val libraryFile = selected?.resolve("mediampv.dll")
        return MpvRuntimeResolution(
            directory = selected,
            bridgeFile = libraryFile,
            systemMpvFile = null,
            checkedDirectories = checked,
            diagnostics = "os=$osName bridge.name=mediampv.dll bridge.selected=${libraryFile?.safePath() ?: "none"} " +
                "systemLibmpv.selected=bundled-windows checked=${checked.joinToString(" | ")}",
        )
    }

    private fun resolveLinux(): MpvRuntimeResolution {
        val bridgeCandidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) bridgeCandidates.putIfAbsent(label, file)
        }

        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR/libmediampv.so", dir.resolve("libmediampv.so"))
        }

        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        add("appImage:lib/app/libmediampv.so", resourcesDir?.parentFile?.resolve("libmediampv.so"))
        add("property:nuvio.mediamp.runtime.dir/libmediampv.so", System.getProperty("nuvio.mediamp.runtime.dir")?.toFileOrNull()?.resolve("libmediampv.so"))

        System.getProperty("user.dir")?.takeIf { it.isNotBlank() }?.let { userDir ->
            add("dev:mediamp/build-ci/libmediampv.so", File(userDir).resolve("mediamp/mediamp-mpv/build-ci/libmediampv.so"))
        }

        javaLibraryPathEntries().forEach { entry ->
            val dir = File(entry)
            add("java.library.path:${dir.safePath()}/libmediampv.so", dir.resolve("libmediampv.so"))
        }

        val systemMpvCandidates = linuxSystemMpvCandidates()
        val checkedBridge = bridgeCandidates.map { (label, file) ->
            "$label=${file.safePath()} exists=${file.isFile}"
        }
        val checkedSystemMpv = systemMpvCandidates.map { (label, file) ->
            "$label=${file.safePath()} exists=${file.isFile}"
        }
        val selectedBridge = bridgeCandidates.values.firstOrNull { it.isFile }
        val selectedSystemMpv = systemMpvCandidates.values.firstOrNull { it.isFile }
        return MpvRuntimeResolution(
            directory = selectedBridge?.parentFile,
            bridgeFile = selectedBridge,
            systemMpvFile = selectedSystemMpv,
            checkedDirectories = checkedBridge,
            diagnostics = "os=$osName bridge.name=libmediampv.so bridge.selected=${selectedBridge?.safePath() ?: "none"} " +
                "systemLibmpv.selected=${selectedSystemMpv?.safePath() ?: "none"} " +
                "bridge.checked=${checkedBridge.joinToString(" | ")} " +
                "systemLibmpv.checked=${checkedSystemMpv.joinToString(" | ")}",
        )
    }

    private fun resolveMac(): MpvRuntimeResolution {
        val bridgeCandidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) bridgeCandidates.putIfAbsent(label, file)
        }

        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR/libmediampv.dylib", dir.resolve("libmediampv.dylib"))
        }
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
        add("appImage:lib/app/libmediampv.dylib", resourcesDir?.parentFile?.resolve("libmediampv.dylib"))
        System.getProperty("nuvio.mediamp.runtime.dir")?.toFileOrNull()?.let { dir ->
            add("property:nuvio.mediamp.runtime.dir/libmediampv.dylib", dir.resolve("libmediampv.dylib"))
        }
        javaLibraryPathEntries().forEach { entry ->
            val dir = File(entry)
            add("java.library.path:${dir.safePath()}/libmediampv.dylib", dir.resolve("libmediampv.dylib"))
        }

        val checkedBridge = bridgeCandidates.map { (label, file) ->
            "$label=${file.safePath()} exists=${file.isFile}"
        }
        val selectedBridge = bridgeCandidates.values.firstOrNull { it.isFile }
        return MpvRuntimeResolution(
            directory = selectedBridge?.parentFile,
            bridgeFile = selectedBridge,
            systemMpvFile = null,
            checkedDirectories = checkedBridge,
            diagnostics = "os=$osName bridge.name=libmediampv.dylib bridge.selected=${selectedBridge?.safePath() ?: "none"} " +
                "systemLibmpv.selected=platform-default bridge.checked=${checkedBridge.joinToString(" | ")}",
        )
    }

    private fun linuxSystemMpvCandidates(): LinkedHashMap<String, File> {
        val candidates = linkedMapOf<String, File>()
        fun add(label: String, file: File?) {
            if (file != null) candidates.putIfAbsent(label, file)
        }

        System.getenv("NUVIO_MPV_DIR")?.toFileOrNull()?.let { dir ->
            add("env:NUVIO_MPV_DIR/libmpv.so", dir.resolve("libmpv.so"))
            add("env:NUVIO_MPV_DIR/libmpv.so.2", dir.resolve("libmpv.so.2"))
        }
        add("system:/usr/lib/libmpv.so.2", File("/usr/lib/libmpv.so.2"))
        add("system:/usr/lib/libmpv.so", File("/usr/lib/libmpv.so"))
        add("system:/usr/local/lib/libmpv.so.2", File("/usr/local/lib/libmpv.so.2"))
        add("system:/usr/local/lib/libmpv.so", File("/usr/local/lib/libmpv.so"))
        add("system:/lib/libmpv.so.2", File("/lib/libmpv.so.2"))
        add("system:/lib/libmpv.so", File("/lib/libmpv.so"))
        return candidates
    }

    private fun devLookupEnabled(): Boolean =
        System.getenv("NUVIO_DEV_PLAYER_LOOKUP").equals("true", ignoreCase = true) ||
            System.getProperty("nuvio.dev.player.lookup").equals("true", ignoreCase = true)

    private fun javaLibraryPathEntries(): List<String> =
        System.getProperty("java.library.path")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()

    private fun pathEntries(): List<File> =
        System.getenv("PATH")
            ?.split(File.pathSeparatorChar)
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map(::File)
            .orEmpty()

    private fun String.toFileOrNull(): File? =
        takeIf { it.isNotBlank() }?.let(::File)
}

internal fun File.safePath(): String = absolutePath.replace("\\", "/")
