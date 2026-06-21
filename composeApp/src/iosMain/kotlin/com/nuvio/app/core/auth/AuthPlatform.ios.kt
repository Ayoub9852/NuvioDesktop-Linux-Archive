package com.nuvio.app.core.auth

import io.github.jan.supabase.auth.AuthConfig

internal actual object AuthPlatform {
    actual fun configureAuth(config: AuthConfig) = Unit
}
