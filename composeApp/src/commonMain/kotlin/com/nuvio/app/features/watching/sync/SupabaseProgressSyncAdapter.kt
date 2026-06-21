package com.nuvio.app.features.watching.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

object SupabaseProgressSyncAdapter : ProgressSyncAdapter {
    private val log = Logger.withTag("SupabaseProgressSyncAdapter")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun pull(profileId: Int): List<ProgressSyncRecord> {
        val serverEntries = runCatching {
            val params = buildJsonObject { put("p_profile_id", profileId) }
            SupabaseProvider.client.postgrest
                .rpc("sync_pull_watch_progress", params)
                .decodeList<WatchProgressSyncEntry>()
        }.getOrElse { error ->
            log.e(error) { "sync_pull_watch_progress RPC failed; trying watch_progress table fallback" }
            pullFromTableFallback(profileId)
        }
        val records = serverEntries.map { entry ->
            ProgressSyncRecord(
                contentId = entry.contentId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.season,
                episode = entry.episode,
                position = entry.position,
                duration = entry.duration,
                lastWatched = entry.lastWatched,
            )
        }
        return records
    }

    override suspend fun push(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        runCatching {
            val syncEntries = entries.map { entry ->
                WatchProgressSyncEntry(
                    contentId = entry.parentMetaId,
                    contentType = entry.contentType,
                    videoId = entry.videoId,
                    season = entry.seasonNumber,
                    episode = entry.episodeNumber,
                    position = entry.lastPositionMs,
                    duration = entry.durationMs,
                    lastWatched = entry.lastUpdatedEpochMs,
                )
            }
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_entries", json.encodeToJsonElement(syncEntries))
            }
            SupabaseProvider.client.postgrest.rpc("sync_push_watch_progress", params)
        }.onFailure { error ->
            log.e(error) { "sync_push_watch_progress RPC failed; trying watch_progress table fallback" }
            pushToTableFallback(profileId, entries)
        }
    }

    override suspend fun delete(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        runCatching {
            val progressKeys = entries.map(::progressKey)
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_keys", json.encodeToJsonElement(progressKeys))
            }
            SupabaseProvider.client.postgrest.rpc("sync_delete_watch_progress", params)
        }.onFailure { error ->
            log.e(error) { "sync_delete_watch_progress RPC failed; trying watch_progress table fallback" }
            deleteFromTableFallback(profileId, entries)
        }
    }

    private suspend fun pullFromTableFallback(profileId: Int): List<WatchProgressSyncEntry> {
        val userId = authenticatedUserId() ?: return emptyList()
        return SupabaseProvider.client.postgrest
            .from("watch_progress")
            .select {
                filter {
                    eq("user_id", userId)
                    eq("profile_id", profileId)
                }
                order("last_watched", Order.DESCENDING)
            }
            .decodeList<WatchProgressSyncEntry>()
    }

    private suspend fun pushToTableFallback(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        val userId = authenticatedUserId() ?: return
        val rows = entries.map { entry ->
            WatchProgressTableRow(
                userId = userId,
                profileId = profileId,
                contentId = entry.parentMetaId,
                contentType = entry.contentType,
                videoId = entry.videoId,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
                position = entry.lastPositionMs,
                duration = entry.durationMs,
                lastWatched = entry.lastUpdatedEpochMs,
                progressKey = progressKey(entry),
            )
        }
        if (rows.isEmpty()) return
        SupabaseProvider.client.postgrest
            .from("watch_progress")
            .upsert(rows) {
                onConflict = "user_id,profile_id,progress_key"
            }
    }

    private suspend fun deleteFromTableFallback(
        profileId: Int,
        entries: Collection<WatchProgressEntry>,
    ) {
        val userId = authenticatedUserId() ?: return
        val progressKeys = entries.map(::progressKey)
        if (progressKeys.isEmpty()) return
        SupabaseProvider.client.postgrest
            .from("watch_progress")
            .delete {
                filter {
                    eq("user_id", userId)
                    eq("profile_id", profileId)
                    isIn("progress_key", progressKeys)
                }
            }
    }

    private fun authenticatedUserId(): String? {
        val authState = AuthRepository.state.value as? AuthState.Authenticated ?: return null
        if (authState.isAnonymous) return null
        return authState.userId
    }

    private fun progressKey(entry: WatchProgressEntry): String =
        if (entry.seasonNumber != null && entry.episodeNumber != null) {
            "${entry.parentMetaId}_s${entry.seasonNumber}e${entry.episodeNumber}"
        } else {
            entry.parentMetaId
        }
}

@Serializable
private data class WatchProgressSyncEntry(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_id") val profileId: Int? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
    @SerialName("progress_key") val progressKey: String = "",
)

@Serializable
private data class WatchProgressTableRow(
    @SerialName("user_id") val userId: String,
    @SerialName("profile_id") val profileId: Int,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long = 0,
    val duration: Long = 0,
    @SerialName("last_watched") val lastWatched: Long = 0,
    @SerialName("progress_key") val progressKey: String,
)
