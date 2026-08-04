package com.pegasus.bridge.video

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fullscreen video player activity — handles both the new
 * `pegasus-data://play-video?url=…` scheme and the legacy `pegasus-video://play?url=…`.
 *
 * For YouTube URLs, streams are resolved via [YouTubeResolver] before playback.
 * For direct HTTPS URLs (Steam CDN, etc.) the URL is fed straight into ExoPlayer.
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PegasusBridge.Video"
        // User-Agent identico a Chrome Android — necessario per Steam CDN
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Odin2) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var loadingSpinner: ProgressBar
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var currentJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_player)

        playerView    = findViewById(R.id.playerView)
        titleView     = findViewById(R.id.videoTitle)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        setupImmersive()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentJob?.cancel()
        releasePlayer()
        handleIntent(intent)
    }

    override fun onStart() { super.onStart(); player?.play() }
    override fun onStop()  { super.onStop();  player?.pause() }

    override fun onDestroy() {
        currentJob?.cancel()
        releasePlayer()
        releaseAudioFocus()
        super.onDestroy()
    }

    // ── Intent handling ────────────────────────────────────────────────────

    private fun handleIntent(src: Intent?) {
        val params = UriParams.fromIntentData(src?.data)
        if (params == null) {
            Log.e(TAG, "Invalid URI: ${src?.data}")
            Toast.makeText(this, R.string.err_invalid_uri, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Log.i(TAG, "Playing URL: ${params.url} | title: ${params.title}")
        titleView.text = params.title

        if (YouTubeUrlDetector.isYouTube(params.url)) {
            showLoading(true)
            currentJob = lifecycleScope.launch {
                try {
                    val streams = YouTubeResolver.resolve(params.url)
                    if (params.title.isBlank() && streams.title.isNotBlank()) {
                        titleView.text = streams.title
                    }
                    playYouTubeStreams(streams)
                } catch (e: ResolveError.AgeRestricted) {
                    showLoading(false)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_age_gate, Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: ResolveError.RegionBlocked) {
                    showLoading(false)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_region, Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: ResolveError.VideoUnavailable) {
                    showLoading(false)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_unavailable, Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: ResolveError.NoStreamFound) {
                    showLoading(false)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_no_stream, Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: ResolveError.SignInRequired) {
                    showLoading(false)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_sign_in, Toast.LENGTH_LONG).show()
                    finish()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Normale — utente ha chiuso o onNewIntent ha annullato
                } catch (t: Throwable) {
                    showLoading(false)
                    Log.e(TAG, "YT resolve error", t)
                    Toast.makeText(this@VideoPlayerActivity, R.string.err_yt_generic, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        } else {
            startPlayback(params.url)
        }
    }

    private fun playYouTubeStreams(streams: ResolvedStreams) {
        showLoading(false)
        when {
            streams.progressive != null ->
                startPlayback(streams.progressive.url, streams.progressive.mimeType)
            streams.dashManifestUrl != null ->
                startPlayback(streams.dashManifestUrl, MimeTypes.APPLICATION_MPD)
            streams.dashSeparate != null ->
                startMergedPlayback(streams.dashSeparate)
            else -> {
                Toast.makeText(this, R.string.err_yt_no_stream, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun showLoading(visible: Boolean) {
        loadingSpinner.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ── Playback ───────────────────────────────────────────────────────────

    private fun startPlayback(url: String, mimeType: String? = null) {
        requestAudioFocus()
        val httpFactory = buildHttpDataSourceFactory()
        val srcFactory  = DefaultMediaSourceFactory(httpFactory)
        val item = if (mimeType != null) MediaItem.Builder().setUri(url).setMimeType(mimeType).build()
                   else MediaItem.fromUri(url)
        launchPlayer(srcFactory, srcFactory.createMediaSource(item))
    }

    private fun startMergedPlayback(sep: ResolvedStreams.DashSeparate) {
        requestAudioFocus()
        val httpFactory = buildHttpDataSourceFactory()
        val srcFactory  = DefaultMediaSourceFactory(httpFactory)
        val merged = MergingMediaSource(
            srcFactory.createMediaSource(MediaItem.Builder().setUri(sep.videoUrl).setMimeType(sep.videoMimeType).build()),
            srcFactory.createMediaSource(MediaItem.Builder().setUri(sep.audioUrl).setMimeType(sep.audioMimeType).build())
        )
        launchPlayer(srcFactory, merged)
    }

    private fun buildHttpDataSourceFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    private fun launchPlayer(mediaSourceFactory: DefaultMediaSourceFactory, source: MediaSource) {
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Playback error: ${error.errorCodeName} — ${error.message}")
                Toast.makeText(
                    this@VideoPlayerActivity,
                    PlayerErrorMapper.humanMessage(error),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "State: $state")
                if (state == Player.STATE_ENDED) finish()
            }
        })

        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()

        playerView.player = p
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 3000
        playerView.controllerHideOnTouch = true
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setKeepContentOnPlayerReset(true)

        player = p
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    // ── Immersive mode ─────────────────────────────────────────────────────

    private fun setupImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY          or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE             or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION    or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN         or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION           or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    // ── Audio focus ────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        val am = (audioManager ?: getSystemService(AUDIO_SERVICE) as AudioManager)
            .also { audioManager = it }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener { focus ->
                    when (focus) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                        AudioManager.AUDIOFOCUS_GAIN           -> player?.play()
                    }
                }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }
}
