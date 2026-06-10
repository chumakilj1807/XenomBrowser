package com.xenombrowser

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
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

    private val handler = Handler(Looper.getMainLooper())
    private var progressUpdater: Runnable? = null
    private var controlsHider: Runnable? = null
    private val HIDE_DELAY = 4000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_video)

        playerView     = findViewById(R.id.player_view)
        controlsOverlay= findViewById(R.id.controls_overlay)
        seekBar        = findViewById(R.id.seek_bar)
        tvPosition     = findViewById(R.id.tv_position)
        tvDuration     = findViewById(R.id.tv_duration)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                )
            ).build()

        playerView.player = player
        playerView.keepScreenOn = true
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    seekBar.max = player.duration.coerceAtLeast(1).toInt()
                    tvDuration.text = formatMs(player.duration)
                    startProgressUpdater()
                }
            }
        })

        showControls()
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
        controlsOverlay.visibility = View.VISIBLE
        controlsHider?.let { handler.removeCallbacks(it) }
        controlsHider = Runnable { controlsOverlay.visibility = View.GONE }
        handler.postDelayed(controlsHider!!, HIDE_DELAY)
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val s = ms / 1000; val m = s / 60; val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        showControls()
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                { if (player.isPlaying) player.pause() else player.play(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
                { player.seekTo((player.currentPosition - 30_000L).coerceAtLeast(0L)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                { val d = player.duration; player.seekTo(if (d > 0) (player.currentPosition + 30_000L).coerceAtMost(d) else player.currentPosition + 30_000L); true }
            KeyEvent.KEYCODE_DPAD_UP ->
                { player.seekTo((player.currentPosition - 90_000L).coerceAtLeast(0L)); true }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                { val d = player.duration; player.seekTo(if (d > 0) (player.currentPosition + 90_000L).coerceAtMost(d) else player.currentPosition + 90_000L); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onPause()   { super.onPause();   player.pause() }
    override fun onResume()  { super.onResume();  player.play() }
    override fun onDestroy() {
        super.onDestroy()
        progressUpdater?.let { handler.removeCallbacks(it) }
        controlsHider?.let  { handler.removeCallbacks(it) }
        player.release()
    }
}
