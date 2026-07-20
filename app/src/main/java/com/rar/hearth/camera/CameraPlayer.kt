package com.rar.hearth.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.rar.hearth.config.CameraConfig
import kotlinx.coroutines.launch

/** The dusk letterbox background used behind every feed and error overlay. */
private val DUSK = Color(0xFF12141C)

/**
 * Plays a single [StreamSource] in one ExoPlayer instance wrapped in a letterboxed PlayerView.
 * The player is created once and released in onDispose; a source change re-prepares the same
 * instance. All playback decisions (which URL, fallback, mute) live in the caller — this composable
 * just plays what it is told and reports errors via [onError].
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPlayer(
    source: StreamSource,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }
    val latestOnError by rememberUpdatedState(onError)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = latestOnError()
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val mediaSource = remember(source) { buildMediaSource(source) }
    LaunchedEffect(mediaSource) {
        mediaSource?.let {
            player.setMediaSource(it)
            player.prepare()
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    AndroidView(
        modifier = modifier.fillMaxSize().background(DUSK),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(DUSK.toArgb())
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
    )
}

@OptIn(UnstableApi::class)
private fun buildMediaSource(source: StreamSource): MediaSource? = when (source) {
    is StreamSource.Rtsp ->
        RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(source.url))
    is StreamSource.Hls ->
        HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
            .createMediaSource(MediaItem.fromUri(source.url))
    StreamSource.Unavailable -> null
}

/**
 * Orchestrates playback for one [camera]: resolves the primary source, does a single fallback step
 * on the first playback error, and shows an error overlay (with Retry) when the stream is
 * Unavailable. Callers key this by camera identity so switching cameras disposes the old player.
 */
@Composable
fun CameraFeed(
    camera: CameraConfig,
    resolver: StreamResolver,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(camera) { mutableIntStateOf(0) }
    var source by remember(camera) { mutableStateOf<StreamSource?>(null) }
    var usedFallback by remember(camera) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(camera, attempt) {
        usedFallback = false
        source = resolver.primary(camera)
    }

    Box(modifier.fillMaxSize().background(DUSK), contentAlignment = Alignment.Center) {
        when (val s = source) {
            null -> {} // resolving; dusk background only
            StreamSource.Unavailable -> StreamUnavailable(camera.name) { attempt++ }
            else -> CameraPlayer(
                source = s,
                muted = muted,
                onError = {
                    if (!usedFallback) {
                        usedFallback = true
                        scope.launch { source = resolver.fallback(camera, s) }
                    } else {
                        source = StreamSource.Unavailable
                    }
                },
            )
        }
    }
}

/** Error overlay: camera name + "stream unavailable" on the dusk background, tap to retry. */
@Composable
private fun StreamUnavailable(cameraName: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(DUSK),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cameraName, color = Color.White, textAlign = TextAlign.Center)
            Text("stream unavailable", color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("Retry", color = Color(0xFF7FB2E5))
            }
        }
    }
}
