package com.nuvio.app.core.network

import com.nuvio.app.core.auth.AuthPlatform
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest

object SupabaseProvider {
    val isConfigured: Boolean
        get() = SupabaseConfig.URL.isNotBlank() && SupabaseConfig.ANON_KEY.isNotBlank()

    val client by lazy {
        check(isConfigured) {
            "Supabase is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties."
        }
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            install(Auth) {
                AuthPlatform.configureAuth(this)
            }
            install(Postgrest)
            install(Functions)
        }
    }
}
