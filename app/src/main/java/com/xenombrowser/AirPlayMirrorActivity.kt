package com.xenombrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xenombrowser.airplay.RtpReceiver

class AirPlayMirrorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PORT   = "rtp_port"
        const val EXTRA_WIDTH  = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_SPS    = "sps"
        const val EXTRA_PPS    = "pps"
        const val ACTION_STOP  = "com.xenombrowser.AIRPLAY_STOP"
    }

    private var rtpReceiver: RtpReceiver? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvInfo: TextView

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Full-screen immersive
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        setContentView(R.layout.activity_airplay_mirror)
        surfaceView = findViewById(R.id.surface_mirror)
        tvInfo      = findViewById(R.id.tv_airplay_info)

        ContextCompat.registerReceiver(this, stopReceiver, IntentFilter(ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        val port   = intent.getIntExtra(EXTRA_PORT, 7010)
        val width  = intent.getIntExtra(EXTRA_WIDTH, 1920)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
        val sps    = intent.getByteArrayExtra(EXTRA_SPS) ?: ByteArray(0)
        val pps    = intent.getByteArrayExtra(EXTRA_PPS) ?: ByteArray(0)

        tvInfo.text = "AirPlay  ${width}×${height}"

        // Wait for Surface to be ready before starting decoder
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                rtpReceiver = RtpReceiver(port, holder.surface, width, height, sps, pps)
                rtpReceiver!!.start()
                // Hide info label after 3 seconds
                tvInfo.postDelayed({ tvInfo.visibility = View.GONE }, 3000)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopReceiver()
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = Unit
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    private fun stopReceiver() {
        rtpReceiver?.apply { running = false }
        rtpReceiver = null
    }
}
