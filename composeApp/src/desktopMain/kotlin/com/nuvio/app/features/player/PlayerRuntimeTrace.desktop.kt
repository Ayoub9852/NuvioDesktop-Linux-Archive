package com.nuvio.app.features.player

import com.nuvio.app.desktop.DesktopRuntimeLog

internal actual object PlayerRuntimeTrace {
    actual val skipDebugEnabled: Boolean
        get() = System.getenv("NUVIO_SKIP_DEBUG").equals("1", ignoreCase = true) ||
            System.getProperty("nuvio.skip.debug").equals("true", ignoreCase = true)

    actual fun info(message: String) {
        DesktopRuntimeLog.info("PlayerScreen $message")
    }

    actual fun warn(message: String) {
        DesktopRuntimeLog.warn("PlayerScreen $message")
    }
}
