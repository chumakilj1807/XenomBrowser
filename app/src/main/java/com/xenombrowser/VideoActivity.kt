package com.xenombrowser

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class VideoActivity : FragmentActivity() {

    companion object {
        const val EXTRA_URL        = "url"
        const val EXTRA_AUDIO_ONLY = "audio_only"
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var controlsOverlay: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var infoText: TextView
    private lateinit var root: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var controlsHider: Runnable? = null
    private val HIDE_DELAY = 4000L
    private var url = ""
    private var retries = 0
    private val MAX_RETRIES = 3
    private val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video)

        root           = findViewById<View>(R.id.player_view).parent as FrameLayout
        playerView     = findViewById(R.id.player_view)
        controlsOverlay= findViewById(R.id.controls_overlay)
        seekBar        = findViewById(R.id.seek_bar)
        tvPosition     = findViewById(R.id.tv_position)
        tvDuration     = findViewById(R.id.tv_duration)

        // Buffering spinner (centered)
        buffering = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER)
        }
        root.addView(buffering)
        // Transient info (speed / title)
        infoText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(16); leftMargin = dp(16)
            }
        }
        root.addView(infoText)

        url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        buildPlayer()
        showControls()
    }

    private fun buildPlayer() {
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            )).build()
        playerView.player = player
        playerView.keepScreenOn = true
        playerView.useController = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare(); player.play()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    retries = 0
                    seekBar.max = player.duration.coerceAtLeast(1).toInt()
                    tvDuration.text = formatMs(player.duration)
                    startProgressUpdater()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (retries < MAX_RETRIES) {
                    retries++
                    showInfo("Переподключение ($retries)...")
                    handler.postDelayed({ player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.play() }, 1500)
                } else {
                    buffering.visibility = View.GONE
                    showInfo("Не удалось воспроизвести видео")
                    Toast.makeText(this@VideoActivity, "Ошибка воспроизведения: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun startProgressUpdater() {
        progressUpdater?.let { handler.removeCallbacks(it) }
        progressUpdater = object : Runnable {
            override fun run() {
                if (!isFinishing) {
                    seekBar.progress = player.currentPosition.toInt()
                    tvPosition.text  = formatMs(player.currentPosition)
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(progressUpdater!!)
    }

    private fun showControls() {
        if (isInPipMode()) return
        controlsOverlay.visibility = View.VISIBLE
        controlsHider?.let { handler.removeCallbacks(it) }
        controlsHider = Runnable { controlsOverlay.visibility = View.GONE }
        handler.postDelayed(controlsHider!!, HIDE_DELAY)
    }

    private fun showInfo(text: String) {
        infoText.text = text; infoText.visibility = View.VISIBLE
        handler.postDelayed({ infoText.visibility = View.GONE }, 2000)
    }

    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        player.playbackParameters = PlaybackParameters(speeds[speedIdx])
        showInfo("Скорость: ${speeds[speedIdx]}×")
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            val ar = if (player.videoSize.width > 0 && player.videoSize.height > 0)
                Rational(player.videoSize.width, player.videoSize.height) else Rational(16, 9)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ar).build())
        } catch (_: Exception) {}
    }
    private fun isInPipMode() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        controlsOverlay.visibility = if (isInPip) View.GONE else View.VISIBLE
        if (!isInPip) showControls()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying) enterPip()
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60) else "%d:%02d".format(m, s % 60)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        showControls()
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                { if (player.isPlaying) player.pause() else player.play(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                { player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                { val d = player.duration; player.seekTo(if (d > 0) (player.currentPosition + 30_000L).coerceAtMost(d) else player.currentPosition + 30_000L); true }
            KeyEvent.KEYCODE_DPAD_UP ->
                { player.seekTo((player.currentPosition - 90_000L).coerceAtLeast(0L)); true }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                { val d = player.duration; player.seekTo(if (d > 0) (player.currentPosition + 90_000L).coerceAtMost(d) else player.currentPosition + 90_000L); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y ->
                { cycleSpeed(); true }
            KeyEvent.KEYCODE_WINDOW, KeyEvent.KEYCODE_INFO ->
                { enterPip(); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onPause()   { super.onPause(); if (!isInPipMode()) player.pause() }
    override fun onResume()  { super.onResume(); player.play() }
    override fun onDestroy() {
        super.onDestroy()
        progressUpdater?.let { handler.removeCallbacks(it) }
        controlsHider?.let  { handler.removeCallbacks(it) }
        player.release()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
