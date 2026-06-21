package com.nuvio.app.features.player.skip

import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerRuntimeTrace

object SkipIntroRepository {

    private val cache = HashMap<String, List<SkipInterval>>()
    private val imdbEntriesCache = HashMap<String, List<ArmEntry>>()
    private val animeSkipShowIdCache = HashMap<String, String>()
    private const val NO_ID = "__none__"

    private val introDbConfigured: Boolean
        get() = IntroDbConfig.URL.isNotBlank()

    private fun log(message: String) {
        PlayerRuntimeTrace.info("SKIP_LOOKUP $message")
    }

    private fun warn(message: String) {
        PlayerRuntimeTrace.warn("SKIP_LOOKUP $message")
    }

    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> {
        if (imdbId == null) {
            log("lookup skipped path=imdb reason=no-imdb-id season=$season episode=$episode")
            return emptyList()
        }
        val settings = PlayerSettingsRepository.uiState.value
        log(
            "lookup start path=imdb imdbId=$imdbId season=$season episode=$episode " +
                "skipIntroEnabled=${settings.skipIntroEnabled} animeSkipEnabled=${settings.animeSkipEnabled} " +
                "animeSkipClientIdPresent=${settings.animeSkipClientId.isNotBlank()} introDbConfigured=$introDbConfigured",
        )
        if (!settings.skipIntroEnabled) {
            log("lookup end path=imdb reason=settings-disabled")
            return emptyList()
        }
        if (!settings.animeSkipEnabled && settings.animeSkipClientId.isNotBlank()) {
            log("animeSkip disabled although clientId is present")
        }

        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let {
            log("lookup cache-hit path=imdb count=${it.size} intervals=${it.intervalSummary()}")
            return it
        }

        if (introDbConfigured) {
            val result = fetchFromIntroDb(imdbId, season, episode)
            if (result.isNotEmpty()) return result.also {
                cache[cacheKey] = it
                log("lookup end path=imdb provider=introdb count=${it.size} intervals=${it.intervalSummary()}")
            }
        }

        val entries = resolveImdbEntries(imdbId)
        log("ARM mapping request/result path=imdb imdbId=$imdbId count=${entries.size} entries=${entries.armSummary()}")
        val malId = entries.getOrNull(season - 1)?.myanimelist?.toString()
            ?: entries.firstOrNull()?.myanimelist?.toString()
        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also {
                cache[cacheKey] = it
                log("lookup end path=imdb provider=aniskip malId=$malId count=${it.size} intervals=${it.intervalSummary()}")
            }
        }

        val seasonAnilistId = entries.getOrNull(season - 1)?.anilist?.toString()
        val fallbackAnilistId = entries.firstOrNull()?.anilist?.toString()
        for ((anilistId, seasonFilter) in listOfNotNull(
            seasonAnilistId?.let { it to season },
            if (fallbackAnilistId != null && fallbackAnilistId != seasonAnilistId) fallbackAnilistId to season else null
        )) {
            val result = fetchFromAnimeSkip(anilistId, episode, season = seasonFilter)
            if (result.isNotEmpty()) return result.also {
                cache[cacheKey] = it
                log("lookup end path=imdb provider=animeskip anilistId=$anilistId seasonFilter=$seasonFilter count=${it.size} intervals=${it.intervalSummary()}")
            }
        }

        return emptyList<SkipInterval>().also {
            cache[cacheKey] = it
            log("lookup end path=imdb reason=no-intervals")
        }
    }

    suspend fun getSkipIntervalsForMal(malId: String, episode: Int): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        log(
            "lookup start path=mal malId=$malId episode=$episode " +
                "skipIntroEnabled=${settings.skipIntroEnabled} animeSkipEnabled=${settings.animeSkipEnabled} " +
                "animeSkipClientIdPresent=${settings.animeSkipClientId.isNotBlank()}",
        )
        if (!settings.skipIntroEnabled) {
            log("lookup end path=mal reason=settings-disabled")
            return emptyList()
        }
        if (!settings.animeSkipEnabled && settings.animeSkipClientId.isNotBlank()) {
            log("animeSkip disabled although clientId is present")
        }

        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let {
            log("lookup cache-hit path=mal count=${it.size} intervals=${it.intervalSummary()}")
            return it
        }

        val aniSkipResult = fetchFromAniSkip(malId, episode)
        if (aniSkipResult.isNotEmpty()) return aniSkipResult.also {
            cache[cacheKey] = it
            log("lookup end path=mal provider=aniskip count=${it.size} intervals=${it.intervalSummary()}")
        }

        val imdbId = try {
            SkipIntroApi.resolveMalToImdb(malId)?.imdb
        } catch (error: Exception) {
            warn("ARM mapping failed path=mal-to-imdb malId=$malId error=${error.message}")
            null
        }
        log("ARM mapping request/result path=mal-to-imdb malId=$malId imdbId=$imdbId")

        if (imdbId != null) {
            val entries = resolveImdbEntries(imdbId)
            log("ARM mapping request/result path=imdb-from-mal imdbId=$imdbId count=${entries.size} entries=${entries.armSummary()}")
            val season = entries.indexOfFirst { it.myanimelist == malId.toIntOrNull() } + 1

            if (introDbConfigured && season > 0) {
                val result = fetchFromIntroDb(imdbId, season, episode)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=mal provider=introdb resolvedSeason=$season count=${it.size} intervals=${it.intervalSummary()}")
                }
            } else if (season <= 0) {
                log("introdb skipped path=mal reason=resolved-season-not-found malId=$malId imdbId=$imdbId")
            }
            val seasonAnilistId = entries.getOrNull(season - 1)?.anilist?.toString()
            val fallbackAnilistId = entries.firstOrNull()?.anilist?.toString()
            for ((anilistId, seasonFilter) in listOfNotNull(
                seasonAnilistId?.let { it to season.takeIf { resolvedSeason -> resolvedSeason > 0 } },
                if (fallbackAnilistId != null && fallbackAnilistId != seasonAnilistId) fallbackAnilistId to season else null
            )) {
                val result = fetchFromAnimeSkip(anilistId, episode, season = seasonFilter)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=mal provider=animeskip anilistId=$anilistId seasonFilter=$seasonFilter count=${it.size} intervals=${it.intervalSummary()}")
                }
            }
        } else {
            val anilistId = try {
                SkipIntroApi.resolveMalToAnilist(malId)?.anilist?.toString()
            } catch (error: Exception) {
                warn("ARM mapping failed path=mal-to-anilist malId=$malId error=${error.message}")
                null
            }
            log("ARM mapping request/result path=mal-to-anilist malId=$malId anilistId=$anilistId")
            if (anilistId != null) {
                val result = fetchFromAnimeSkip(anilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=mal provider=animeskip anilistId=$anilistId count=${it.size} intervals=${it.intervalSummary()}")
                }
            }
        }

        return emptyList<SkipInterval>().also {
            cache[cacheKey] = it
            log("lookup end path=mal reason=no-intervals")
        }
    }

    suspend fun getSkipIntervalsForKitsu(kitsuId: String, episode: Int): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        log(
            "lookup start path=kitsu kitsuId=$kitsuId episode=$episode " +
                "skipIntroEnabled=${settings.skipIntroEnabled} animeSkipEnabled=${settings.animeSkipEnabled} " +
                "animeSkipClientIdPresent=${settings.animeSkipClientId.isNotBlank()}",
        )
        if (!settings.skipIntroEnabled) {
            log("lookup end path=kitsu reason=settings-disabled")
            return emptyList()
        }
        if (!settings.animeSkipEnabled && settings.animeSkipClientId.isNotBlank()) {
            log("animeSkip disabled although clientId is present")
        }

        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let {
            log("lookup cache-hit path=kitsu count=${it.size} intervals=${it.intervalSummary()}")
            return it
        }

        val malId = try {
            SkipIntroApi.resolveKitsuToMal(kitsuId)?.myanimelist?.toString()
        } catch (error: Exception) {
            warn("ARM mapping failed path=kitsu-to-mal kitsuId=$kitsuId error=${error.message}")
            null
        }
        log("ARM mapping request/result path=kitsu-to-mal kitsuId=$kitsuId malId=$malId")

        if (malId != null) {
            val result = fetchFromAniSkip(malId, episode)
            if (result.isNotEmpty()) return result.also {
                cache[cacheKey] = it
                log("lookup end path=kitsu provider=aniskip malId=$malId count=${it.size} intervals=${it.intervalSummary()}")
            }
        }

        val imdbId = try {
            SkipIntroApi.resolveKitsuToImdb(kitsuId)?.imdb
        } catch (error: Exception) {
            warn("ARM mapping failed path=kitsu-to-imdb kitsuId=$kitsuId error=${error.message}")
            null
        }
        log("ARM mapping request/result path=kitsu-to-imdb kitsuId=$kitsuId imdbId=$imdbId")

        if (imdbId != null) {
            val entries = resolveImdbEntries(imdbId)
            log("ARM mapping request/result path=imdb-from-kitsu imdbId=$imdbId count=${entries.size} entries=${entries.armSummary()}")
            val season = entries.indexOfFirst { it.kitsu == kitsuId.toIntOrNull() } + 1

            if (introDbConfigured && season > 0) {
                val result = fetchFromIntroDb(imdbId, season, episode)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=kitsu provider=introdb resolvedSeason=$season count=${it.size} intervals=${it.intervalSummary()}")
                }
            } else if (season <= 0) {
                log("introdb skipped path=kitsu reason=resolved-season-not-found kitsuId=$kitsuId imdbId=$imdbId")
            }
            val seasonAnilistId = entries.getOrNull(season - 1)?.anilist?.toString()
            val fallbackAnilistId = entries.firstOrNull()?.anilist?.toString()
            for ((anilistId, seasonFilter) in listOfNotNull(
                seasonAnilistId?.let { it to season.takeIf { resolvedSeason -> resolvedSeason > 0 } },
                if (fallbackAnilistId != null && fallbackAnilistId != seasonAnilistId) fallbackAnilistId to season else null
            )) {
                val result = fetchFromAnimeSkip(anilistId, episode, season = seasonFilter)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=kitsu provider=animeskip anilistId=$anilistId seasonFilter=$seasonFilter count=${it.size} intervals=${it.intervalSummary()}")
                }
            }
        } else {
            val anilistId = try {
                SkipIntroApi.resolveKitsuToAnilist(kitsuId)?.anilist?.toString()
            } catch (error: Exception) {
                warn("ARM mapping failed path=kitsu-to-anilist kitsuId=$kitsuId error=${error.message}")
                null
            }
            log("ARM mapping request/result path=kitsu-to-anilist kitsuId=$kitsuId anilistId=$anilistId")
            if (anilistId != null) {
                val result = fetchFromAnimeSkip(anilistId, episode, season = null)
                if (result.isNotEmpty()) return result.also {
                    cache[cacheKey] = it
                    log("lookup end path=kitsu provider=animeskip anilistId=$anilistId count=${it.size} intervals=${it.intervalSummary()}")
                }
            }
        }

        return emptyList<SkipInterval>().also {
            cache[cacheKey] = it
            log("lookup end path=kitsu reason=no-intervals")
        }
    }

    suspend fun getSkipIntervalsForAnilist(anilistId: String, season: Int?, episode: Int): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        log(
            "lookup start path=anilist anilistId=$anilistId season=$season episode=$episode " +
                "skipIntroEnabled=${settings.skipIntroEnabled} animeSkipEnabled=${settings.animeSkipEnabled} " +
                "animeSkipClientIdPresent=${settings.animeSkipClientId.isNotBlank()}",
        )
        if (!settings.skipIntroEnabled) {
            log("lookup end path=anilist reason=settings-disabled")
            return emptyList()
        }
        if (!settings.animeSkipEnabled && settings.animeSkipClientId.isNotBlank()) {
            log("animeSkip disabled although clientId is present")
        }

        val cacheKey = "anilist:$anilistId:${season ?: "any"}:$episode"
        cache[cacheKey]?.let {
            log("lookup cache-hit path=anilist count=${it.size} intervals=${it.intervalSummary()}")
            return it
        }

        val result = fetchFromAnimeSkip(anilistId, episode, season)
        return result.also {
            cache[cacheKey] = it
            if (it.isEmpty()) {
                log("lookup end path=anilist reason=no-intervals")
            } else {
                log("lookup end path=anilist provider=animeskip count=${it.size} intervals=${it.intervalSummary()}")
            }
        }
    }

    private suspend fun fetchFromIntroDb(imdbId: String, season: Int, episode: Int): List<SkipInterval> {
        return try {
            val data = SkipIntroApi.getIntroDbSegments(imdbId, season, episode)
            if (data == null) {
                log("provider=introdb result=null imdbId=$imdbId season=$season episode=$episode")
                return emptyList()
            }
            listOfNotNull(
                data.intro.toSkipIntervalOrNull("intro"),
                data.recap.toSkipIntervalOrNull("recap"),
                data.outro.toSkipIntervalOrNull("outro"),
            ).also { log("provider=introdb returned segmentCount=${it.size} types=${it.typesSummary()}") }
        } catch (error: Exception) {
            warn("provider=introdb exception imdbId=$imdbId season=$season episode=$episode error=${error.message}")
            emptyList()
        }
    }

    private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
        if (this == null) return null
        val start = startSec ?: startMs?.let { it / 1000.0 }
        val end = endSec ?: endMs?.let { it / 1000.0 }
        if (start == null || end == null || end <= start) return null
        return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val response = SkipIntroApi.getAniSkipTimes(malId, episode)
            if (response == null) {
                log("provider=aniskip result=null malId=$malId episode=$episode")
                return emptyList()
            }
            if (!response.found) {
                log("provider=aniskip found=false malId=$malId episode=$episode")
                return emptyList()
            }
            response.results?.map { result ->
                SkipInterval(
                    startTime = result.interval.startTime,
                    endTime = result.interval.endTime,
                    type = result.skipType,
                    provider = "aniskip",
                )
            }?.also { log("provider=aniskip returned segmentCount=${it.size} types=${it.typesSummary()}") } ?: emptyList()
        } catch (error: Exception) {
            warn("provider=aniskip exception malId=$malId episode=$episode error=${error.message}")
            emptyList()
        }
    }

    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val settings = PlayerSettingsRepository.uiState.value
        val clientId = settings.animeSkipClientId.trim()
        if (clientId.isBlank()) {
            log("provider=animeskip skipped reason=missing-client-id anilistId=$anilistId episode=$episode season=$season")
            return emptyList()
        }
        if (!settings.animeSkipEnabled) {
            log("provider=animeskip skipped reason=settings-disabled anilistId=$anilistId episode=$episode season=$season")
            return emptyList()
        }

        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            log("AnimeSkip request/result phase=show-resolution anilistId=$anilistId showCount=${showIds.size}")
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val query = "{ findEpisodesByShowId(showId: \"$showId\") { id season number name baseDuration timestamps { id at type { name } } } }"
                val response = SkipIntroApi.queryAnimeSkip(clientId, query) ?: continue
                val episodes = response.data?.findEpisodesByShowId ?: continue
                log("AnimeSkip request/result phase=episodes showId=$showId episodeCount=${episodes.size} targetSeason=$season targetEpisode=$episode")

                val targetEpisodes = selectAnimeSkipEpisodeCandidates(episodes, season, episode)
                if (targetEpisodes.isEmpty()) {
                    log("AnimeSkip target episode not found showId=$showId targetSeason=$season targetEpisode=$episode available=${episodes.take(12).joinToString { "S${it.season}E${it.number}" }}")
                    continue
                }

                for (targetEpisode in targetEpisodes) {
                    log(
                        "AnimeSkip selected episode id=${targetEpisode.id ?: "null"} " +
                            "season=${targetEpisode.season ?: "null"} number=${targetEpisode.number ?: "null"} " +
                            "title=${targetEpisode.name ?: "null"} baseDuration=${targetEpisode.baseDuration ?: "null"}",
                    )
                    val rawTimestamps = targetEpisode.timestamps.orEmpty()
                    rawTimestamps.forEachIndexed { index, ts ->
                        log(
                            "AnimeSkip raw timestamp episodeId=${targetEpisode.id ?: "null"} index=$index " +
                                "id=${ts.id ?: "null"} type=${ts.type.name} at=${ts.at} " +
                                "rawStart=null rawEnd=null rawValue=${ts.at} duration=${targetEpisode.baseDuration ?: "null"} offset=null",
                        )
                    }
                    val sorted = rawTimestamps.sortedBy { it.at }
                    val result = normalizeAnimeSkipIntervals(targetEpisode, sorted)
                    log(
                        "AnimeSkip request/result phase=timestamps showId=$showId episodeId=${targetEpisode.id ?: "null"} " +
                            "rawCount=${sorted.size} normalizedCount=${result.size} normalizedTypes=${result.typesSummary()}",
                    )
                    if (result.isNotEmpty()) return result
                }
            }
            log("lookup end provider=animeskip reason=invalid-intervals-or-empty anilistId=$anilistId season=$season episode=$episode")
            emptyList()
        } catch (error: Exception) {
            warn("provider=animeskip exception anilistId=$anilistId episode=$episode season=$season error=${error.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
        val showIds = try {
            SkipIntroApi.queryAnimeSkip(clientId, query)
                ?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (error: Exception) {
            warn("AnimeSkip show resolution failed anilistId=$anilistId error=${error.message}")
            emptyList()
        }

        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    private fun selectAnimeSkipEpisodeCandidates(
        episodes: List<AnimeSkipEpisode>,
        season: Int?,
        episode: Int,
    ): List<AnimeSkipEpisode> {
        val byEpisode = episodes.filter { it.number?.toIntOrNull() == episode }
        if (byEpisode.isEmpty()) return emptyList()
        if (season == null) {
            return byEpisode.sortedWith(compareByDescending<AnimeSkipEpisode> { !it.season.isNullOrBlank() }
                .thenByDescending { it.timestamps.orEmpty().size })
        }

        val exactSeason = byEpisode.filter { it.season?.toIntOrNull() == season }
        if (exactSeason.isNotEmpty()) {
            return exactSeason.sortedByDescending { it.timestamps.orEmpty().size }
        }

        log(
            "AnimeSkip exact season not found targetSeason=$season targetEpisode=$episode " +
                "candidateSeasons=${byEpisode.map { it.season ?: "null" }}",
        )
        return byEpisode.sortedWith(compareByDescending<AnimeSkipEpisode> { it.season != null }
            .thenByDescending { it.timestamps.orEmpty().size })
    }

    private fun normalizeAnimeSkipIntervals(
        episode: AnimeSkipEpisode,
        sorted: List<AnimeSkipTimestamp>,
    ): List<SkipInterval> {
        val intervals = mutableListOf<SkipInterval>()
        sorted.forEachIndexed { index, timestamp ->
            val type = animeSkipTypeToIntervalType(timestamp.type.name) ?: return@forEachIndexed
            val next = sorted.drop(index + 1).firstOrNull()
            if (next == null) {
                log("dropped invalid interval reason=no-end-marker raw=${timestamp.rawSummary(episode)}")
                return@forEachIndexed
            }
            val startTime = normalizeTimestampValue(timestamp.at)
            val endTime = normalizeTimestampValue(next.at)
            if (startTime == null || endTime == null || endTime <= startTime) {
                log(
                    "dropped invalid interval reason=negative-or-zero-length " +
                        "raw=${timestamp.rawSummary(episode)} next=${next.rawSummary(episode)} " +
                        "start=$startTime end=$endTime",
                )
                return@forEachIndexed
            }
            intervals += SkipInterval(
                startTime = startTime,
                endTime = endTime,
                type = type,
                provider = "animeskip",
            )
        }
        if (sorted.isNotEmpty() && intervals.isEmpty()) {
            log("AnimeSkip normalized no valid intervals reason=invalid-intervals episodeId=${episode.id ?: "null"} rawCount=${sorted.size}")
        }
        return intervals
    }

    private fun normalizeTimestampValue(value: Double): Double? {
        if (value.isNaN() || value.isInfinite()) return null
        if (value < 0.0) {
            return if (value > -0.5) 0.0 else null
        }
        return value
    }

    private fun animeSkipTypeToIntervalType(name: String): String? =
        when (name.trim().lowercase()) {
            "intro", "new intro", "mixed intro" -> "op"
            "credits", "new credits", "mixed credits" -> "ed"
            "recap" -> "recap"
            else -> null
        }

    private fun AnimeSkipTimestamp.rawSummary(episode: AnimeSkipEpisode): String =
        "episodeId=${episode.id ?: "null"},episodeSeason=${episode.season ?: "null"}," +
            "episodeNumber=${episode.number ?: "null"},episodeTitle=${episode.name ?: "null"}," +
            "baseDuration=${episode.baseDuration ?: "null"},timestampId=${id ?: "null"}," +
            "type=${type.name},at=$at,rawStart=null,rawEnd=null,rawValue=$at,offset=null"

    private suspend fun resolveImdbEntries(imdbId: String): List<ArmEntry> {
        imdbEntriesCache[imdbId]?.let {
            log("ARM mapping cache-hit imdbId=$imdbId count=${it.size} entries=${it.armSummary()}")
            return it
        }
        return try {
            SkipIntroApi.resolveImdbToAll(imdbId)
        } catch (error: Exception) {
            warn("ARM mapping failed imdbId=$imdbId error=${error.message}")
            emptyList()
        }.also { imdbEntriesCache[imdbId] = it }
    }

    suspend fun submitIntro(
        imdbId: String,
        season: Int,
        episode: Int,
        startSec: Double,
        endSec: Double,
        segmentType: String,
    ): Boolean {
        val settings = PlayerSettingsRepository.uiState.value
        val apiKey = settings.introDbApiKey.trim()
        if (!settings.introSubmitEnabled || apiKey.isBlank()) return false

        val request = SubmitIntroRequest(
            imdbId = imdbId,
            season = season,
            episode = episode,
            startSec = startSec,
            endSec = endSec,
            startMs = (startSec * 1000).toLong(),
            endMs = (endSec * 1000).toLong(),
            segmentType = segmentType,
        )

        return SkipIntroApi.submitIntro(apiKey, request)
    }

    suspend fun verifyIntroDbApiKey(apiKey: String): Boolean {
        return SkipIntroApi.verifyIntroDbApiKey(apiKey)
    }

    fun clearCache() {
        cache.clear()
        imdbEntriesCache.clear()
        animeSkipShowIdCache.clear()
    }

    private fun List<SkipInterval>.intervalSummary(): String =
        joinToString(prefix = "[", postfix = "]") { interval ->
            "${interval.provider}:${interval.type}@${interval.startTime}-${interval.endTime}"
        }

    private fun List<SkipInterval>.typesSummary(): String =
        groupBy { "${it.provider}:${it.type}" }
            .map { (type, values) -> "$type=${values.size}" }
            .joinToString(prefix = "[", postfix = "]")

    private fun List<ArmEntry>.armSummary(): String =
        joinToString(prefix = "[", postfix = "]") { entry ->
            "mal=${entry.myanimelist},anilist=${entry.anilist},kitsu=${entry.kitsu},imdb=${entry.imdb}"
        }
}
