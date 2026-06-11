package com.xenombrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.xenombrowser.airplay.AIRPLAY_PORT
import com.xenombrowser.airplay.AirPlayServer

private const val TAG      = "AirPlayService"
private const val CH_ID    = "airplay_ch"
private const val NOTIF_ID = 1001

class AirPlayService : Service() {

    private lateinit var server: AirPlayServer
    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    private val deviceId by lazy {
        val prefs = getSharedPreferences("airplay_keys", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: run {
            val id = (1..6).joinToString(":") { "%02X".format((Math.random() * 256).toInt()) }
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        server = AirPlayServer(
            deviceId      = deviceId,
            onMirrorStart = { port, w, h, sps, pps ->
                Log.i(TAG, "Mirror started ${w}x${h} port=$port")
                startActivity(Intent(this, AirPlayMirrorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(AirPlayMirrorActivity.EXTRA_PORT,   port)
                    putExtra(AirPlayMirrorActivity.EXTRA_WIDTH,  w)
                    putExtra(AirPlayMirrorActivity.EXTRA_HEIGHT, h)
                    putExtra(AirPlayMirrorActivity.EXTRA_SPS,    sps)
                    putExtra(AirPlayMirrorActivity.EXTRA_PPS,    pps)
                })
            },
            onMirrorStop = {
                Log.i(TAG, "Mirror stopped")
                sendBroadcast(Intent(AirPlayMirrorActivity.ACTION_STOP))
            }
        )
        server.start()
        registerMdns()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterMdns()
        server.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── mDNS (_airplay._tcp) ─────────────────────────────────────────────

    private fun registerMdns() {
        // NO "pk" attribute — tells iOS to connect without pairing or FairPlay.
        // "features" 0x4A7FBFD5 = screen-mirroring + audio + video flags.
        val info = NsdServiceInfo().apply {
            serviceName = "XenomBrowser"
            serviceType = "_airplay._tcp."
            port        = AIRPLAY_PORT
            setAttribute("deviceid",    deviceId)
            setAttribute("features",    "0x4A7FBFD5,0x00000000")
            setAttribute("model",       "AppleTV5,3")
            setAttribute("srcvers",     "220.68")
            setAttribute("osvers",      "9.0")
            setAttribute("statusflags", "68")
        }

        regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered as '${si.serviceName}'")
            }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {
                Log.e(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }

        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener!!)
    }

    private fun unregisterMdns() {
        runCatching { regListener?.let { nsdManager?.unregisterService(it) } }
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CH_ID, "AirPlay", NotificationManager.IMPORTANCE_LOW)
        ch.description = "AirPlay receiver active"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AirPlay готов")
            .setContentText("Найдите 'XenomBrowser' в AirPlay на iPhone")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
