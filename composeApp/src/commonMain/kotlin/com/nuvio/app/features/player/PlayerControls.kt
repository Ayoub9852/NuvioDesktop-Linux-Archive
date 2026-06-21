package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppIconResource
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.appIconPainter
import com.nuvio.app.core.ui.nuvioTypeScale
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerControlsShell(
    title: String,
    streamTitle: String,
    providerName: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    isLocked: Boolean,
    isFullscreenSupported: Boolean,
    isFullscreen: Boolean,
    onLockToggle: () -> Unit,
    onFullscreenClick: () -> Unit,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onResizeModeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    audioLevel: PlayerAudioLevel,
    onVolumeClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onVolumeInteractionChange: (Boolean) -> Unit,
    onSourcesClick: (() -> Unit)? = null,
    onEpisodesClick: (() -> Unit)? = null,
    onSubmitIntroClick: (() -> Unit)? = null,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    horizontalSafePadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.7f),
                        ),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalSafePadding),
        ) {
            PlayerHeader(
                title = title,
                streamTitle = streamTitle,
                providerName = providerName,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                metrics = metrics,
                isLocked = isLocked,
                isFullscreenSupported = isFullscreenSupported,
                isFullscreen = isFullscreen,
                onSubmitIntroClick = onSubmitIntroClick,
                onLockToggle = onLockToggle,
                onFullscreenClick = onFullscreenClick,
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                    .padding(
                        start = metrics.horizontalPadding,
                        end = metrics.horizontalPadding,
                        top = metrics.verticalPadding / 4,
                    ),
            )

            CenterControls(
                snapshot = playbackSnapshot,
                metrics = metrics,
                onSeekBack = onSeekBack,
                onSeekForward = onSeekForward,
                onTogglePlayback = onTogglePlayback,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = metrics.centerLift),
            )

            ProgressControls(
                playbackSnapshot = playbackSnapshot,
                displayedPositionMs = displayedPositionMs,
                metrics = metrics,
                resizeMode = resizeMode,
                onScrubChange = onScrubChange,
                onScrubFinished = onScrubFinished,
                onResizeModeClick = onResizeModeClick,
                onSpeedClick = onSpeedClick,
                onSubtitleClick = onSubtitleClick,
                onAudioClick = onAudioClick,
                audioLevel = audioLevel,
                onVolumeClick = onVolumeClick,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
                onVolumeInteractionChange = onVolumeInteractionChange,
                onSourcesClick = onSourcesClick,
                onEpisodesClick = onEpisodesClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = metrics.horizontalPadding)
                    .padding(bottom = metrics.sliderBottomOffset),
            )
        }
    }
}

@Composable
private fun PlayerHeader(
    title: String,
    streamTitle: String,
    providerName: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    metrics: PlayerLayoutMetrics,
    isLocked: Boolean,
    isFullscreenSupported: Boolean,
    isFullscreen: Boolean,
    onSubmitIntroClick: (() -> Unit)?,
    onLockToggle: () -> Unit,
    onFullscreenClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeScale = MaterialTheme.nuvioTypeScale
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = typeScale.titleLg.copy(
                        fontSize = metrics.titleSize,
                        lineHeight = metrics.titleSize * 1.16f,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (seasonNumber != null && episodeNumber != null && !episodeTitle.isNullOrBlank()) {
                    Text(
                        text = stringResource(
                            Res.string.compose_player_episode_title_format,
                            seasonNumber,
                            episodeNumber,
                            episodeTitle,
                        ),
                        style = typeScale.bodyMd.copy(
                            fontSize = metrics.episodeInfoSize,
                            lineHeight = metrics.episodeInfoSize * 1.3f,
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = streamTitle,
                        style = typeScale.labelSm.copy(
                            fontSize = metrics.metadataSize,
                            lineHeight = metrics.metadataSize * 1.25f,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = providerName,
                        style = typeScale.labelSm.copy(
                            fontSize = metrics.metadataSize,
                            lineHeight = metrics.metadataSize * 1.25f,
                            fontStyle = FontStyle.Italic,
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isFullscreenSupported) {
                    PlayerHeaderIconButton(
                        icon = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                        contentDescription = if (isFullscreen) {
                            stringResource(Res.string.compose_player_exit_fullscreen)
                        } else {
                            stringResource(Res.string.compose_player_enter_fullscreen)
                        },
                        buttonSize = metrics.headerIconSize + 16.dp,
                        iconSize = metrics.headerIconSize,
                        onClick = onFullscreenClick,
                    )
                }
                if (onSubmitIntroClick != null) {
                    PlayerHeaderIconButton(
                        icon = Icons.Rounded.Flag,
                        contentDescription = "Submit Intro",
                        buttonSize = metrics.headerIconSize + 16.dp,
                        iconSize = metrics.headerIconSize,
                        onClick = onSubmitIntroClick,
                    )
                }
                PlayerHeaderIconButton(
                    icon = if (isLocked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                    contentDescription = if (isLocked) {
                        stringResource(Res.string.compose_player_unlock_controls)
                    } else {
                        stringResource(Res.string.compose_player_lock_controls)
                    },
                    buttonSize = metrics.headerIconSize + 16.dp,
                    iconSize = metrics.headerIconSize,
                    onClick = onLockToggle,
                )
                NuvioBackButton(
                    onClick = onBack,
                    containerColor = Color.Black.copy(alpha = 0.35f),
                    contentColor = Color.White,
                    buttonSize = metrics.headerIconSize + 16.dp,
                    iconSize = metrics.headerIconSize,
                    contentDescription = stringResource(Res.string.compose_player_close),
                )
            }
        }
    }
}

@Composable
private fun PlayerHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun CenterControls(
    snapshot: PlayerPlaybackSnapshot,
    metrics: PlayerLayoutMetrics,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlayback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(metrics.centerGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideControlButton(
            icon = Icons.Rounded.Replay10,
            contentDescription = stringResource(Res.string.compose_player_seek_back_10),
            metrics = metrics,
            onClick = onSeekBack,
        )
        PlayPauseControlButton(
            isPlaying = snapshot.isPlaying,
            isBuffering = snapshot.isLoading,
            metrics = metrics,
            onClick = onTogglePlayback,
        )
        SideControlButton(
            icon = Icons.Rounded.Forward10,
            contentDescription = stringResource(Res.string.compose_player_seek_forward_10),
            metrics = metrics,
            onClick = onSeekForward,
        )
    }
}

@Composable
private fun SideControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    metrics: PlayerLayoutMetrics,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(metrics.sideButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(metrics.playIconSize),
        )
    }
}

@Composable
private fun PlayPauseControlButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    metrics: PlayerLayoutMetrics,
    onClick: () -> Unit,
) {
    val playPausePainter = appIconPainter(
        if (isPlaying) AppIconResource.PlayerPause else AppIconResource.PlayerPlay,
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(metrics.playButtonPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(metrics.playIconSize),
            )
        } else {
            Icon(
                painter = playPausePainter,
                contentDescription = if (isPlaying) {
                    stringResource(Res.string.compose_action_pause)
                } else {
                    stringResource(Res.string.detail_btn_play)
                },
                tint = Color.White,
                modifier = Modifier.size(metrics.playIconSize),
            )
        }
    }
}

@Composable
private fun ProgressControls(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    resizeMode: PlayerResizeMode,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
    onResizeModeClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    onAudioClick: () -> Unit,
    audioLevel: PlayerAudioLevel,
    onVolumeClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onVolumeInteractionChange: (Boolean) -> Unit,
    onSourcesClick: (() -> Unit)? = null,
    onEpisodesClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val durationMs = playbackSnapshot.durationMs.coerceAtLeast(1L)
    val aspectRatioPainter = appIconPainter(AppIconResource.PlayerAspectRatio)
    val subtitlesPainter = appIconPainter(AppIconResource.PlayerSubtitles)
    val audioPainter = appIconPainter(AppIconResource.PlayerAudioFilled)
    var volumePopoverVisible by remember { mutableStateOf(false) }
    var volumeCloseSerial by remember { mutableIntStateOf(0) }
    var volumePointerInside by remember { mutableStateOf(false) }
    var volumeDragging by remember { mutableStateOf(false) }
    val latestOnVolumeInteractionChange by rememberUpdatedState(onVolumeInteractionChange)

    fun openVolumePopover(reason: String) {
        if (!volumePopoverVisible) {
            val percentage = (audioLevel.fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
            PlayerRuntimeTrace.info(
                "volume control opened reason=$reason value=$percentage muted=${audioLevel.isMuted} rendered=true",
            )
        }
        volumePopoverVisible = true
        latestOnVolumeInteractionChange(true)
        volumeCloseSerial += 1
    }

    fun requestVolumeClose(reason: String) {
        volumeCloseSerial += 1
        PlayerRuntimeTrace.info("volume control close requested reason=$reason")
    }

    LaunchedEffect(volumeCloseSerial, volumePopoverVisible, volumeDragging, volumePointerInside) {
        val serial = volumeCloseSerial
        if (!volumePopoverVisible || volumeDragging || volumePointerInside) return@LaunchedEffect
        delay(100L)
        if (serial == volumeCloseSerial && !volumeDragging && !volumePointerInside) {
            volumePopoverVisible = false
            latestOnVolumeInteractionChange(false)
            val percentage = (audioLevel.fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
            PlayerRuntimeTrace.info("volume control closed reason=delay value=$percentage muted=${audioLevel.isMuted}")
        }
    }

    DisposableEffect(Unit) {
        onDispose { latestOnVolumeInteractionChange(false) }
    }

    Box(modifier = modifier) {
        if (volumePopoverVisible) {
            VolumeSliderPopover(
                audioLevel = audioLevel,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
                onPointerInsideChange = { inside ->
                    volumePointerInside = inside
                    if (inside) openVolumePopover("popover-hover") else requestVolumeClose("popover-exit")
                },
                onDraggingChange = { dragging ->
                    volumeDragging = dragging
                    latestOnVolumeInteractionChange(dragging || volumePopoverVisible)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-46).dp),
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.sliderTouchHeight)
                .graphicsLayer(scaleY = metrics.sliderScaleY),
            value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
            onValueChange = { value -> onScrubChange(value.toLong()) },
            onValueChangeFinished = { onScrubFinished(displayedPositionMs.coerceIn(0L, durationMs)) },
            valueRange = 0f..durationMs.toFloat(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimePill(text = formatPlaybackTime(displayedPositionMs), fontSize = metrics.timeSize)
            TimePill(text = formatPlaybackTime(durationMs), fontSize = metrics.timeSize)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(24.dp),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerActionPillButton(
                        label = stringResource(resizeMode.labelRes),
                        painter = aspectRatioPainter,
                        onClick = onResizeModeClick,
                    )
                    PlayerActionPillButton(
                        label = formatPlaybackSpeedLabel(playbackSnapshot.playbackSpeed),
                        icon = Icons.Rounded.Speed,
                        onClick = onSpeedClick,
                    )
                    PlayerActionPillButton(
                        label = stringResource(Res.string.compose_player_subs),
                        painter = subtitlesPainter,
                        onClick = onSubtitleClick,
                    )
                    VolumeButton(
                        audioLevel = audioLevel,
                        onClick = {
                            onVolumeClick()
                        },
                        onHoverChange = { inside ->
                            volumePointerInside = inside
                            if (inside) openVolumePopover("button-hover") else requestVolumeClose("button-exit")
                        },
                    )
                    PlayerActionPillButton(
                        label = stringResource(Res.string.compose_player_audio),
                        painter = audioPainter,
                        onClick = onAudioClick,
                    )
                    if (onSourcesClick != null) {
                        PlayerActionPillButton(
                            label = stringResource(Res.string.compose_player_sources),
                            icon = Icons.Rounded.SwapHoriz,
                            onClick = onSourcesClick,
                        )
                    }
                    if (onEpisodesClick != null) {
                        PlayerActionPillButton(
                            label = stringResource(Res.string.compose_player_episodes),
                            icon = Icons.Rounded.VideoLibrary,
                            onClick = onEpisodesClick,
                        )
                    }
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeButton(
    audioLevel: PlayerAudioLevel,
    onClick: () -> Unit,
    onHoverChange: (Boolean) -> Unit,
) {
    val percentage = (audioLevel.fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
    val isMuted = audioLevel.isMuted || percentage == 0
    val volumeIcon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
            .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = volumeIcon,
            contentDescription = "Volume",
            tint = Color.White.copy(alpha = if (isMuted) 0.68f else 1f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumeSliderPopover(
    audioLevel: PlayerAudioLevel,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    onPointerInsideChange: (Boolean) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val latestOnVolumeChangeFinished by rememberUpdatedState(onVolumeChangeFinished)
    val latestOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val percentage = (audioLevel.fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
    val isMuted = audioLevel.isMuted || percentage == 0
    val volumeIcon = if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp

    Surface(
        color = Color.Black.copy(alpha = 0.78f),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
            .onPointerEvent(PointerEventType.Enter) { onPointerInsideChange(true) }
            .onPointerEvent(PointerEventType.Exit) { onPointerInsideChange(false) }
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(18.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .width(210.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = volumeIcon,
                contentDescription = "Volume",
                tint = Color.White.copy(alpha = if (isMuted) 0.72f else 0.96f),
                modifier = Modifier.size(17.dp),
            )
            Slider(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .graphicsLayer(scaleY = 0.72f),
                value = audioLevel.fraction.coerceIn(0f, 1f),
                onValueChange = { value ->
                    latestOnDraggingChange(true)
                    latestOnVolumeChange(value.coerceIn(0f, 1f))
                },
                onValueChangeFinished = {
                    latestOnDraggingChange(false)
                    latestOnVolumeChangeFinished()
                    PlayerRuntimeTrace.info("volume control committed value=$percentage muted=$isMuted rendered=true")
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.96f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                ),
            )
            Text(
                text = "$percentage%",
                style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

@Composable
internal fun LockedPlayerOverlay(
    playbackSnapshot: PlayerPlaybackSnapshot,
    displayedPositionMs: Long,
    metrics: PlayerLayoutMetrics,
    horizontalSafePadding: androidx.compose.ui.unit.Dp,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = playbackSnapshot.durationMs.coerceAtLeast(1L)
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
        disabledThumbColor = Color.White,
        disabledActiveTrackColor = Color.White,
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.28f),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.72f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onUnlock),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = stringResource(Res.string.compose_player_unlock_controls),
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.compose_player_tap_to_unlock),
                style = MaterialTheme.nuvioTypeScale.bodyMd.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.92f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = horizontalSafePadding + metrics.horizontalPadding)
                .padding(bottom = metrics.sliderBottomOffset),
        ) {
            Slider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.sliderTouchHeight)
                    .graphicsLayer(scaleY = metrics.sliderScaleY),
                value = displayedPositionMs.coerceIn(0L, durationMs).toFloat(),
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = 0f..durationMs.toFloat(),
                enabled = false,
                colors = sliderColors,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TimePill(text = formatPlaybackTime(displayedPositionMs), fontSize = metrics.timeSize)
                TimePill(text = formatPlaybackTime(durationMs), fontSize = metrics.timeSize)
            }
        }
    }
}

@Composable
private fun TimePill(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.nuvioTypeScale.labelSm.copy(
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                fontWeight = FontWeight.Medium,
            ),
            color = Color.White,
        )
    }
}

@Composable
private fun PlayerActionPillButton(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    painter: Painter? = null,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.nuvioTypeScale.labelSm,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}
