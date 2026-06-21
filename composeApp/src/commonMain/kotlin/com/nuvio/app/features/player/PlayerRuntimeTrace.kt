package com.nuvio.app.features.player

internal expect object PlayerRuntimeTrace {
    val skipDebugEnabled: Boolean
    fun info(message: String)
    fun warn(message: String)
}
