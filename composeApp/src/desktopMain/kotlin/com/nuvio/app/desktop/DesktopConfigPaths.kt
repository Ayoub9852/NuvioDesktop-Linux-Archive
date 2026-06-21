package com.nuvio.app.desktop

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories

internal object DesktopConfigPaths {
    val nuvioConfigDir: Path by lazy {
        Paths.get(System.getProperty("user.home"), ".config", "Nuvio")
            .apply { createDirectories() }
    }
}
