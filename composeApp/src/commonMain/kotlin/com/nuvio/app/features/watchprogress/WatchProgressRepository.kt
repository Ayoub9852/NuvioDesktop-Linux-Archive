package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktProgressRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.trakt.shouldUseTraktProgress as shouldUseTraktProgressSource
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.sync.ProgressSyncAdapter
import com.nuvio.app.features.watching.sync.SupabaseProgressSyncAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object WatchProgressRepository {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("WatchProgressRepository")
    private val continueLog = Logger.withTag("CONTINUE_WATCHING")
    private val traktMergeLog = Logger.withTag("TRAKT_MERGE")

    private val _uiState = MutableStateFlow(WatchProgressUiState())
    val uiState: StateFlow<WatchProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var entriesByVideoId: MutableMap<String, WatchProgressEntry> = mutableMapOf()
    private var metadataResolutionJob: Job? = null
    internal var syncAdapter: ProgressSyncAdapter = SupabaseProgressSyncAdapter

    init {
        syncScope.launch {
            TraktAuthRepository.isAuthenticated.collectLatest { authenticated ->
                if (shouldUseTraktProgressSource(
                        isAuthenticated = authenticated,
                        source = TraktSettingsRepository.uiState.value.watchProgressSource,
                    )
                ) {
                    runCatching { TraktProgressRepository.refreshNow() }
                        .onFailure { error -> log.w { "Failed to refresh Trakt progress after auth: ${error.message}" } }
                }
                publish()
            }
        }

        syncScope.launch {
            TraktSettingsRepository.uiState.collectLatest { settings ->
                if (shouldUseTraktProgressSource(
                        isAuthenticated = TraktAuthRepository.isAuthenticated.value,
                        source = settings.watchProgressSource,
                    )
                ) {
                    runCatching { TraktProgressRepository.refreshNow() }
                        .onFailure { error -> log.w { "Failed to refresh Trakt progress after source change: ${error.message}" } }
                }
                publish()
            }
        }

        syncScope.launch {
            TraktProgressRepository.uiState.collectLatest {
                if (shouldUseTraktProgress()) {
                    publish()
                }
            }
        }
    }

    fun ensureLoaded() {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        if (shouldUseTraktProgress()) {
            TraktProgressRepository.refreshAsync()
        }
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        TraktSettingsRepository.onProfileChanged()
        loadFromDisk(profileId)
        TraktProgressRepository.onProfileChanged()
        if (shouldUseTraktProgress()) {
            TraktProgressRepository.refreshAsync()
        }
    }

    fun clearLocalState() {
        metadataResolutionJob?.cancel()
        hasLoaded = false
        currentProfileId = 1
        entriesByVideoId.clear()
        TraktProgressRepository.clearLocalState()
        TraktSettingsRepository.clearLocalState()
        _uiState.value = WatchProgressUiState()
    }

    private fun loadFromDisk(profileId: Int) {
        currentProfileId = profileId
        hasLoaded = true
        entriesByVideoId.clear()

        val payload = WatchProgressStorage.loadPayload(profileId).orEmpty().trim()
        if (payload.isNotEmpty()) {
            entriesByVideoId = WatchProgressCodec.decodeEntries(payload)
                .associateBy { it.videoId }
                .toMutableMap()
        }
        continueLog.i {
            "local progress loaded profileId=$profileId count=${entriesByVideoId.size} " +
                "items=${entriesByVideoId.values.joinToString(limit = 8, transform = ::entrySummary)}"
        }
        publish()
        resolveRemoteMetadata()
    }

    suspend fun pullFromServer(profileId: Int) {
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        TraktProgressRepository.ensureLoaded()
        currentProfileId = profileId

        val useTraktProgress = shouldUseTraktProgress()

        if (useTraktProgress) {
            runCatching { TraktProgressRepository.refreshNow() }
                .onFailure { e -> log.e(e) { "Failed to pull Trakt progress" } }
            publish()
            return
        }

        runCatching {
            val serverEntries = syncAdapter.pull(profileId = profileId)

            val oldLocal = entriesByVideoId.toMap()
            val remoteEntries = serverEntries.map { entry ->
                val videoId = entry.videoId
                val cached = oldLocal[videoId]
                WatchProgressEntry(
                    contentType = entry.contentType,
                    parentMetaId = entry.contentId,
                    parentMetaType = cached?.parentMetaType ?: entry.contentType,
                    videoId = videoId,
                    title = cached?.title?.takeIf { it.isNotBlank() } ?: entry.contentId,
                    logo = cached?.logo,
                    poster = cached?.poster,
                    background = cached?.background,
                    seasonNumber = entry.season,
                    episodeNumber = entry.episode,
                    episodeTitle = cached?.episodeTitle,
                    episodeThumbnail = cached?.episodeThumbnail,
                    lastPositionMs = entry.position,
                    durationMs = entry.duration,
                    lastUpdatedEpochMs = entry.lastWatched,
                    providerName = cached?.providerName,
                    providerAddonId = cached?.providerAddonId,
                    lastStreamTitle = cached?.lastStreamTitle,
                    lastStreamSubtitle = cached?.lastStreamSubtitle,
                    pauseDescription = cached?.pauseDescription,
                    lastSourceUrl = cached?.lastSourceUrl,
                    isCompleted = isWatchProgressComplete(entry.position, entry.duration, false),
                )
            }

            entriesByVideoId = mergeLocalAndRemoteProgress(
                localEntries = oldLocal.values,
                remoteEntries = remoteEntries,
            ).associateBy { it.videoId }.toMutableMap()
            hasLoaded = true
            continueLog.i {
                "remote progress pull merged profileId=$profileId localCount=${oldLocal.size} " +
                    "remoteCount=${remoteEntries.size} mergedCount=${entriesByVideoId.size} " +
                    "items=${entriesByVideoId.values.joinToString(limit = 8, transform = ::entrySummary)}"
            }
            publish()
            persist()

            resolveRemoteMetadata()
        }.onFailure { e ->
            log.e(e) { "Failed to pull watch progress from server" }
            continueLog.w {
                "remote progress pull failed profileId=$profileId localFallback=true " +
                    "localCount=${entriesByVideoId.size} error=${e.message}"
            }
        }
    }

    private fun resolveRemoteMetadata() {
        val needsResolution = entriesByVideoId.values
            .filter { it.poster.isNullOrBlank() || it.background.isNullOrBlank() }
            .groupBy { it.parentMetaId to it.contentType }

        if (needsResolution.isEmpty()) return

        metadataResolutionJob?.cancel()
        metadataResolutionJob = syncScope.launch {
            withTimeoutOrNull(30_000L) {
                AddonRepository.awaitManifestsLoaded()
            } ?: run {
                log.w { "Timed out waiting for addon manifests" }
                return@launch
            }

            for ((key, entries) in needsResolution) {
                val (metaId, metaType) = key
                val meta = runCatching {
                    MetaDetailsRepository.fetch(metaType, metaId)
                }.getOrNull() ?: continue

                for (entry in entries) {
                    val episodeVideo = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                        meta.videos.find { v ->
                            v.season == entry.seasonNumber && v.episode == entry.episodeNumber
                        }
                    } else null

                    entriesByVideoId[entry.videoId] = entry.copy(
                        title = meta.name,
                        poster = meta.poster,
                        background = meta.background,
                        logo = meta.logo,
                        episodeTitle = episodeVideo?.title ?: entry.episodeTitle,
                        episodeThumbnail = episodeVideo?.thumbnail ?: entry.episodeThumbnail,
                        pauseDescription = episodeVideo?.overview
                            ?: meta.description
                            ?: entry.pauseDescription,
                    )
                }

                publish()
            }
            persist()
        }
    }

    fun upsertPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true, reason = "periodic")
    }

    fun flushPlaybackProgress(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
    ) {
        ensureLoaded()
        upsert(session = session, snapshot = snapshot, persist = true, reason = "flush")
    }

    fun clearProgress(videoId: String) {
        clearProgress(listOf(videoId))
    }

    fun clearProgress(videoIds: Collection<String>) {
        ensureLoaded()
        if (videoIds.isEmpty()) return

        if (shouldUseTraktProgress()) {
            videoIds.forEach(TraktProgressRepository::applyOptimisticRemoval)
            publish()
            return
        }

        val removedEntries = videoIds.mapNotNull { videoId ->
            entriesByVideoId.remove(videoId)
        }
        if (removedEntries.isNotEmpty()) {
            publish()
            persist()
            pushDeleteToServer(removedEntries)
        }
    }

    fun removeProgress(
        contentId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ) {
        ensureLoaded()
        val normalizedContentId = contentId.trim()
        if (normalizedContentId.isBlank()) return

        val entriesToRemove = currentEntries().filter { entry ->
            if (entry.parentMetaId != normalizedContentId) {
                false
            } else if (seasonNumber != null && episodeNumber != null) {
                entry.seasonNumber == seasonNumber && entry.episodeNumber == episodeNumber
            } else {
                true
            }
        }
        if (entriesToRemove.isEmpty()) return

        if (shouldUseTraktProgress()) {
            TraktProgressRepository.applyOptimisticRemoval(
                contentId = normalizedContentId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
            publish()
            syncScope.launch {
                runCatching {
                    TraktProgressRepository.removeProgress(
                        contentId = normalizedContentId,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                    )
                }.onFailure { error ->
                    log.e(error) { "Failed to remove Trakt watch progress" }
                }
            }
            return
        }

        entriesToRemove.forEach { entry ->
            entriesByVideoId.remove(entry.videoId)
        }
        publish()
        persist()
        pushDeleteToServer(entriesToRemove)
    }

    fun progressForVideo(videoId: String): WatchProgressEntry? {
        ensureLoaded()
        return currentEntries().firstOrNull { it.videoId == videoId }
    }

    fun progressForEpisode(
        parentMetaId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        fallbackVideoId: String? = null,
    ): WatchProgressEntry? {
        ensureLoaded()
        val canonicalVideoId = buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            fallbackVideoId = fallbackVideoId,
        )
        val candidateVideoIds = listOfNotNull(
            canonicalVideoId.takeIf { it.isNotBlank() },
            fallbackVideoId?.takeIf { it.isNotBlank() },
        ).distinct()
        val entries = currentEntries()
        val directMatch = candidateVideoIds.firstNotNullOfOrNull { candidateVideoId ->
            entries.firstOrNull { entry -> entry.videoId == candidateVideoId }
        }
        val logicalMatch = entries
            .filter { entry ->
                entry.parentMetaId == parentMetaId &&
                    entry.seasonNumber == seasonNumber &&
                    entry.episodeNumber == episodeNumber
            }
            .maxByOrNull { entry -> entry.lastUpdatedEpochMs }
        val selected = directMatch ?: logicalMatch
        continueLog.i {
            "resume lookup parentMetaId=$parentMetaId season=$seasonNumber episode=$episodeNumber " +
                "candidateVideoIds=$candidateVideoIds selected=${selected?.let(::entrySummary) ?: "none"}"
        }
        return selected
    }

    fun resumeEntryForSeries(metaId: String): WatchProgressEntry? {
        ensureLoaded()
        return currentEntries().resumeEntryForSeries(metaId)
    }

    fun continueWatching(): List<WatchProgressEntry> {
        ensureLoaded()
        val selected = currentEntries().continueWatchingEntries()
        continueLog.i {
            "repository continueWatching selectedCount=${selected.size} " +
                "selected=${selected.joinToString(limit = 8, transform = ::entrySummary)}"
        }
        return selected
    }

    private fun upsert(
        session: WatchProgressPlaybackSession,
        snapshot: PlayerPlaybackSnapshot,
        persist: Boolean,
        reason: String,
    ) {
        val positionMs = snapshot.positionMs.coerceAtLeast(0L)
        val durationMs = snapshot.durationMs.coerceAtLeast(0L)
        val progressPercent = if (durationMs > 0L) {
            ((positionMs.toFloat() / durationMs.toFloat()) * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }
        val isCompleted = isWatchProgressComplete(
            positionMs = positionMs,
            durationMs = durationMs,
            isEnded = snapshot.isEnded,
        )
        if (!isCompleted && !shouldStoreWatchProgress(positionMs = positionMs, durationMs = durationMs)) {
            continueLog.i {
                "progress save skipped reason=below-threshold saveReason=$reason videoId=${session.videoId} " +
                    "parentMetaId=${session.parentMetaId} seriesId=${session.parentMetaId} " +
                    "season=${session.seasonNumber} episode=${session.episodeNumber} " +
                    "positionMs=$positionMs durationMs=$durationMs percent=$progressPercent"
            }
            return
        }

        val entry = WatchProgressEntry(
            contentType = session.contentType,
            parentMetaId = session.parentMetaId,
            parentMetaType = session.parentMetaType,
            videoId = session.videoId,
            title = session.title,
            logo = session.logo,
            poster = session.poster,
            background = session.background,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            episodeTitle = session.episodeTitle,
            episodeThumbnail = session.episodeThumbnail,
            lastPositionMs = if (isCompleted && durationMs > 0L) durationMs else positionMs,
            durationMs = durationMs,
            lastUpdatedEpochMs = WatchProgressClock.nowEpochMs(),
            providerName = session.providerName,
            providerAddonId = session.providerAddonId,
            lastStreamTitle = session.lastStreamTitle,
            lastStreamSubtitle = session.lastStreamSubtitle,
            pauseDescription = session.pauseDescription,
            lastSourceUrl = session.lastSourceUrl,
            isCompleted = isCompleted,
        ).normalizedCompletion()

        if (entry.parentMetaType.equals("series", ignoreCase = true)) {
            ContinueWatchingPreferencesRepository.removeDismissedNextUpKeysForContent(entry.parentMetaId)
        }

        entriesByVideoId[session.videoId] = entry
        continueLog.i {
            "progress saved reason=$reason localKey=${progressKey(entry)} remoteKey=${progressKey(entry)} " +
                "videoId=${entry.videoId} parentMetaId=${entry.parentMetaId} seriesId=${entry.parentMetaId} " +
                "season=${entry.seasonNumber} episode=${entry.episodeNumber} positionMs=${entry.lastPositionMs} " +
                "durationMs=${entry.durationMs} percent=$progressPercent completed=${entry.isCompleted} " +
                "thresholdPercent=$WatchProgressCompletionPercentThreshold"
        }
        if (shouldUseTraktProgress()) {
            TraktProgressRepository.applyOptimisticProgress(entry)
        }
        publish()
        if (persist) persist()
        if (entry.poster.isNullOrBlank() || entry.background.isNullOrBlank()) {
            resolveRemoteMetadata()
        }
        pushScrobbleToServer(entry)
        WatchingActions.onProgressEntryUpdated(entry)
    }

    private fun pushScrobbleToServer(entry: WatchProgressEntry) {
        syncScope.launch {
            runCatching {
                val profileId = ProfileRepository.activeProfileId
                syncAdapter.push(profileId = profileId, entries = listOf(entry))
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress scrobble" }
            }
        }
    }

    private fun pushDeleteToServer(entries: Collection<WatchProgressEntry>) {
        if (shouldUseTraktProgress()) return
        syncScope.launch {
            runCatching {
                if (entries.isEmpty()) return@runCatching
                val profileId = ProfileRepository.activeProfileId
                syncAdapter.delete(profileId = profileId, entries = entries)
            }.onFailure { e ->
                log.e(e) { "Failed to push watch progress delete" }
            }
        }
    }

    private fun publish() {
        val entries = currentEntries()
        syncLocalCacheFromMergedTrakt(entries)
        val sortedEntries = entries.sortedByDescending { it.lastUpdatedEpochMs }
        _uiState.value = WatchProgressUiState(
            entries = sortedEntries,
        )
        continueLog.i {
            "ui state published count=${sortedEntries.size} " +
                "continueSelected=${sortedEntries.continueWatchingEntries().joinToString(limit = 8, transform = ::entrySummary)}"
        }
    }

    private fun persist() {
        WatchProgressStorage.savePayload(
            currentProfileId,
            WatchProgressCodec.encodeEntries(entriesByVideoId.values),
        )
    }

    private fun shouldUseTraktProgress(): Boolean =
        shouldUseTraktProgressSource(
            isAuthenticated = TraktAuthRepository.isAuthenticated.value,
            source = TraktSettingsRepository.uiState.value.watchProgressSource,
        )

    private fun currentEntries(): List<WatchProgressEntry> {
        return if (shouldUseTraktProgress()) {
            mergeTraktAndLocalProgress(
                localEntries = entriesByVideoId.values,
                traktEntries = TraktProgressRepository.uiState.value.entries,
            )
        } else {
            entriesByVideoId.values.toList()
        }
    }

    private fun mergeTraktAndLocalProgress(
        localEntries: Collection<WatchProgressEntry>,
        traktEntries: Collection<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        traktMergeLog.i {
            "merge start traktActive=true authenticated=${TraktAuthRepository.isAuthenticated.value} " +
                "localCount=${localEntries.size} traktCount=${traktEntries.size}"
        }
        if (traktEntries.isEmpty()) {
            traktMergeLog.w {
                "merge remoteEmpty=true keepingLocal=true localCount=${localEntries.size} " +
                    "reason=empty-or-unavailable-trakt-snapshot"
            }
            return localEntries.map(WatchProgressEntry::normalizedCompletion)
        }

        val latestCompletedBySeries = latestTraktCompletedBySeries(traktEntries)
        latestCompletedBySeries.forEach { (seriesKey, completed) ->
            traktMergeLog.i {
                "latestCompletedBySeries series=$seriesKey latestCompleted=${episodeCode(completed)} " +
                    "source=${completed.source} watchedAt=${completed.lastUpdatedEpochMs} " +
                    "entry=${entrySummary(completed)}"
            }
        }

        val candidates = buildList {
            localEntries.forEach { entry -> add(ProgressCandidate(kind = "local", entry = entry.normalizedCompletion())) }
            traktEntries.forEach { entry -> add(ProgressCandidate(kind = entry.source, entry = entry.normalizedCompletion())) }
        }.filter { candidate -> !shouldSuppressStaleInProgress(candidate.entry, latestCompletedBySeries) }

        val mergedByKey = candidates
            .groupBy { candidate -> progressKey(candidate.entry) }
            .map { (key, grouped) ->
                val selected = selectMergedCandidate(key = key, grouped = grouped)
                selected.entry
            }
        return mergedByKey.sortedByDescending { it.lastUpdatedEpochMs }
    }

    private fun selectMergedCandidate(
        key: String,
        grouped: List<ProgressCandidate>,
    ): ProgressCandidate {
        val selected = grouped.maxWith(
            compareBy<ProgressCandidate> { candidate -> candidate.entry.lastUpdatedEpochMs }
                .thenBy { candidate -> if (candidate.kind == "local") 1 else 0 }
                .thenBy { candidate -> metadataScore(candidate.entry) },
        )
        val localCandidate = grouped.filter { it.kind == "local" }.maxByOrNull { it.entry.lastUpdatedEpochMs }
        val traktPlaybackCandidate = grouped
            .filter { it.entry.source == WatchProgressSourceTraktPlayback }
            .maxByOrNull { it.entry.lastUpdatedEpochMs }
        val traktCompletedCandidate = grouped
            .filter {
                it.entry.source == WatchProgressSourceTraktHistory ||
                    it.entry.source == WatchProgressSourceTraktShowProgress
            }
            .maxByOrNull { it.entry.lastUpdatedEpochMs }
        val optimisticCandidate = localCandidate
            ?.takeIf { local -> grouped.any { it.kind != "local" } && local.entry.lastUpdatedEpochMs > (grouped - local).maxOf { it.entry.lastUpdatedEpochMs } }
        val reason = when {
            selected.kind == "local" && optimisticCandidate != null -> "local-newer-pending-push"
            selected.kind == "local" -> "local-newer-or-tie"
            selected.entry.source == WatchProgressSourceTraktPlayback -> "trakt-playback-newer"
            selected.entry.source == WatchProgressSourceTraktHistory ||
                selected.entry.source == WatchProgressSourceTraktShowProgress -> "trakt-completed-newer"
            else -> "freshest-canonical-candidate"
        }
        traktMergeLog.i {
            "canonicalKey=$key seriesKey=${seriesKey(selected.entry)} " +
                "localCandidate=${localCandidate?.entry?.let(::entrySummary) ?: "none"} " +
                "traktPlaybackCandidate=${traktPlaybackCandidate?.entry?.let(::entrySummary) ?: "none"} " +
                "traktHistoryWatchedCandidate=${traktCompletedCandidate?.entry?.let(::entrySummary) ?: "none"} " +
                "optimisticCandidate=${optimisticCandidate?.entry?.let(::entrySummary) ?: "none"} " +
                "winner=${entrySummary(selected.entry)} reason=$reason"
        }
        return selected
    }

    private fun latestTraktCompletedBySeries(
        traktEntries: Collection<WatchProgressEntry>,
    ): Map<String, WatchProgressEntry> =
        traktEntries
            .map(WatchProgressEntry::normalizedCompletion)
            .filter { entry -> entry.parentMetaType.isSeriesTypeForContinueWatching() }
            .filter { entry -> entry.shouldUseAsCompletedSeedForContinueWatching() }
            .filter { entry ->
                entry.source == WatchProgressSourceTraktHistory ||
                    entry.source == WatchProgressSourceTraktShowProgress
            }
            .groupBy(::seriesKey)
            .mapValues { (_, completedEntries) ->
                completedEntries.maxWith(
                    compareBy<WatchProgressEntry> { candidate -> episodeOrdinal(candidate) }
                        .thenBy { candidate -> candidate.lastUpdatedEpochMs },
                )
            }

    private fun shouldSuppressStaleInProgress(
        rawEntry: WatchProgressEntry,
        latestCompletedBySeries: Map<String, WatchProgressEntry>,
    ): Boolean {
        val entry = rawEntry.normalizedCompletion()
        val isSuppressibleInProgress = entry.parentMetaType.isSeriesTypeForContinueWatching() &&
            entry.shouldTreatAsInProgressForContinueWatching() &&
            entry.seasonNumber != null &&
            entry.episodeNumber != null
        if (!isSuppressibleInProgress) return false

        val latestCompleted = latestCompletedBySeries[seriesKey(entry)]
        val shouldSuppress = latestCompleted != null && isSameOrLaterEpisode(latestCompleted, entry)

        if (shouldSuppress) {
            traktMergeLog.i {
                "suppress stale in-progress series=${seriesKey(entry)} " +
                    "candidate=${episodeCode(entry)} latestCompleted=${episodeCode(latestCompleted!!)} " +
                    "reason=older-than-latest-completed suppressed=${entrySummary(entry)} " +
                    "completed=${entrySummary(latestCompleted)}"
            }
            return true
        }

        traktMergeLog.i {
            "no suppression series=${seriesKey(entry)} candidate=${episodeCode(entry)} " +
                "latestCompleted=${latestCompleted?.let(::episodeCode) ?: "none"} " +
                "reason=${if (latestCompleted == null) "no-completed-evidence" else "candidate-ahead-of-latest-completed"} " +
                "candidate=${entrySummary(entry)}"
        }
        return false
    }

    private fun syncLocalCacheFromMergedTrakt(entries: List<WatchProgressEntry>) {
        if (!shouldUseTraktProgress()) return
        var changed = false
        entries.forEach { rawEntry ->
            val entry = rawEntry.normalizedCompletion()
            if (!entry.source.startsWith("trakt_")) return@forEach
            val existing = entriesByVideoId.values
                .filter { local -> progressKey(local) == progressKey(entry) }
                .maxByOrNull { local -> local.lastUpdatedEpochMs }
            if (existing == null || entry.lastUpdatedEpochMs > existing.lastUpdatedEpochMs) {
                existing?.let { entriesByVideoId.remove(it.videoId) }
                entriesByVideoId[entry.videoId] = entry
                changed = true
                traktMergeLog.i {
                    "localCacheUpdated=true key=${progressKey(entry)} winner=${entrySummary(entry)} " +
                        "previous=${existing?.let(::entrySummary) ?: "none"}"
                }
            } else if (existing.lastUpdatedEpochMs > entry.lastUpdatedEpochMs) {
                traktMergeLog.i {
                    "localCacheUpdated=false key=${progressKey(entry)} reason=local-newer-pending-push " +
                        "local=${entrySummary(existing)} trakt=${entrySummary(entry)}"
                }
            }
        }
        if (changed) persist()
    }

    private fun mergeLocalAndRemoteProgress(
        localEntries: Collection<WatchProgressEntry>,
        remoteEntries: Collection<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val candidates = buildList {
            localEntries.forEach { entry -> add("local" to entry.normalizedCompletion()) }
            remoteEntries.forEach { entry -> add("remote" to entry.normalizedCompletion()) }
        }
        return candidates
            .groupBy { (_, entry) -> progressKey(entry) }
            .values
            .map { grouped ->
                val selected = grouped.maxWith(
                    compareBy<Pair<String, WatchProgressEntry>> { (_, entry) -> entry.lastUpdatedEpochMs }
                        .thenBy { (source, _) -> if (source == "local") 1 else 0 },
                )
                grouped.forEach { (source, entry) ->
                    if (entry.videoId != selected.second.videoId || entry.lastUpdatedEpochMs != selected.second.lastUpdatedEpochMs) {
                        continueLog.i {
                            "merge candidate source=$source key=${progressKey(entry)} selected=${entry == selected.second} " +
                                "candidate=${entrySummary(entry)} winner=${entrySummary(selected.second)}"
                        }
                    }
                }
                selected.second
            }
    }

    private fun progressKey(entry: WatchProgressEntry): String =
        if (entry.seasonNumber != null && entry.episodeNumber != null) {
            "${entry.parentMetaId}_s${entry.seasonNumber}e${entry.episodeNumber}"
        } else {
            entry.parentMetaId
        }

    private fun seriesKey(entry: WatchProgressEntry): String =
        if (entry.parentMetaType.isSeriesTypeForContinueWatching()) {
            entry.parentMetaId
        } else {
            progressKey(entry)
        }

    private fun episodeOrdinal(entry: WatchProgressEntry): Int =
        ((entry.seasonNumber ?: -1).coerceAtLeast(-1) * 100_000) + (entry.episodeNumber ?: -1)

    private fun episodeCode(entry: WatchProgressEntry): String =
        "S${entry.seasonNumber ?: "?"}E${entry.episodeNumber ?: "?"}"

    private fun isSameOrLaterEpisode(
        candidate: WatchProgressEntry,
        reference: WatchProgressEntry,
    ): Boolean {
        val candidateSeason = candidate.seasonNumber ?: return false
        val candidateEpisode = candidate.episodeNumber ?: return false
        val referenceSeason = reference.seasonNumber ?: return false
        val referenceEpisode = reference.episodeNumber ?: return false
        return candidateSeason > referenceSeason ||
            (candidateSeason == referenceSeason && candidateEpisode >= referenceEpisode)
    }

    private fun metadataScore(entry: WatchProgressEntry): Int {
        var score = 0
        if (!entry.logo.isNullOrBlank()) score += 1
        if (!entry.poster.isNullOrBlank()) score += 1
        if (!entry.background.isNullOrBlank()) score += 1
        if (!entry.episodeTitle.isNullOrBlank()) score += 1
        if (!entry.episodeThumbnail.isNullOrBlank()) score += 1
        if (!entry.pauseDescription.isNullOrBlank()) score += 1
        return score
    }

    private fun entrySummary(entry: WatchProgressEntry): String =
        "${entry.videoId}[parent=${entry.parentMetaId},s=${entry.seasonNumber},e=${entry.episodeNumber}," +
            "pos=${entry.lastPositionMs},dur=${entry.durationMs},pct=${entry.progressPercent ?: entry.progressFraction * 100f}," +
            "completed=${entry.isEffectivelyCompleted},updated=${entry.lastUpdatedEpochMs},source=${entry.source}]"

    private data class ProgressCandidate(
        val kind: String,
        val entry: WatchProgressEntry,
    )

}
