package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.AuthConfig

internal expect object AuthPlatform {
    fun configureAuth(config: AuthConfig)
}
