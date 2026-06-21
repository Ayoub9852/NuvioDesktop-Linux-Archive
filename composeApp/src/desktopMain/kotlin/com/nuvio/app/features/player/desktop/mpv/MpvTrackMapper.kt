package com.nuvio.app.features.player.desktop.mpv

import com.nuvio.app.features.player.AudioTrack
import com.nuvio.app.features.player.SubtitleTrack
import org.openani.mediamp.mpv.MPVHandle

internal fun MPVHandle.audioTracks(): List<AudioTrack> {
    val count = getMpvIntProperty("track-list/count") ?: return emptyList()
    val tracks = mutableListOf<AudioTrack>()
    for (i in 0 until count) {
        if (getMpvStringProperty("track-list/$i/type") != "audio") continue
        val id = getMpvIntProperty("track-list/$i/id") ?: continue
        val title = getMpvStringProperty("track-list/$i/title")
        val lang = getMpvStringProperty("track-list/$i/lang").takeIf { it.isNotBlank() }
        tracks.add(
            AudioTrack(
                index = tracks.size,
                id = id.toString(),
                label = title.ifEmpty { lang ?: "Track $id" },
                language = lang,
                isSelected = getMpvBooleanProperty("track-list/$i/selected"),
            ),
        )
    }
    return tracks
}

internal fun MPVHandle.audioTrackListSummary(): String {
    val count = getMpvIntProperty("track-list/count") ?: return "track-list=unavailable"
    val entries = buildList {
        for (i in 0 until count) {
            if (getMpvStringProperty("track-list/$i/type") != "audio") continue
            val id = getMpvIntProperty("track-list/$i/id")?.toString() ?: "unknown"
            val title = getMpvStringProperty("track-list/$i/title").ifBlank { "none" }
            val lang = getMpvStringProperty("track-list/$i/lang").ifBlank { "unknown" }
            val codec = getMpvStringProperty("track-list/$i/codec").ifBlank { "unknown" }
            val selected = getMpvBooleanProperty("track-list/$i/selected")
            add("ui=${size}:mpvIndex=$i:id=$id:lang=$lang:title=$title:codec=$codec:selected=$selected")
        }
    }
    return entries.joinToString(prefix = "[", postfix = "]").ifBlank { "[]" }
}

internal fun MPVHandle.subtitleTracks(): List<SubtitleTrack> {
    val count = getMpvIntProperty("track-list/count") ?: return emptyList()
    val tracks = mutableListOf<SubtitleTrack>()
    for (i in 0 until count) {
        if (getMpvStringProperty("track-list/$i/type") != "sub") continue
        val id = getMpvIntProperty("track-list/$i/id") ?: continue
        val title = getMpvStringProperty("track-list/$i/title")
        val lang = getMpvStringProperty("track-list/$i/lang").takeIf { it.isNotBlank() }
        val forced = getMpvBooleanProperty("track-list/$i/forced") || title.contains("forced", ignoreCase = true)
        tracks.add(
            SubtitleTrack(
                index = tracks.size,
                id = id.toString(),
                label = title.ifEmpty { lang ?: "Subtitle $id" },
                language = lang,
                isSelected = getMpvBooleanProperty("track-list/$i/selected"),
                isForced = forced,
            ),
        )
    }
    return tracks
}
