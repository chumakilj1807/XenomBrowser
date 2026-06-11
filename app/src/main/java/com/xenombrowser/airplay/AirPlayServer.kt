package com.xenombrowser.airplay

import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

private const val TAG = "AirPlayServer"
const val AIRPLAY_PORT = 7000
const val RTP_VIDEO_PORT = 7010
const val RTP_AUDIO_PORT = 7011

class AirPlayServer(
    private val crypto: AirPlayCrypto,
    private val deviceId: String,
    private val onMirrorStart: (videoPort: Int, width: Int, height: Int, sps: ByteArray, pps: ByteArray) -> Unit,
    private val onMirrorStop: () -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(AIRPLAY_PORT)
        pool.execute {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleClient(client) }
                } catch (_: SocketException) { break }
            }
        }
        Log.i(TAG, "AirPlay server started on port $AIRPLAY_PORT")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input  = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            while (running && !socket.isClosed) {
                val firstLine = input.readLine() ?: break
                if (firstLine.isBlank()) continue

                val headers = mutableMapOf<String, String>()
                var line = input.readLine()
                while (line != null && line.isNotBlank()) {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                    line = input.readLine()
                }

                val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLen > 0) {
                    val buf = CharArray(contentLen)
                    input.read(buf, 0, contentLen)
                    String(buf).toByteArray(Charsets.ISO_8859_1)
                } else ByteArray(0)

                val isRtsp = firstLine.contains("RTSP/1.0") && !firstLine.startsWith("HTTP")
                if (isRtsp) {
                    handleRtsp(firstLine, headers, body, output)
                } else {
                    handleHttp(firstLine, headers, body, output)
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── HTTP handlers ────────────────────────────────────────────────────

    private fun handleHttp(
        requestLine: String, headers: Map<String, String>,
        body: ByteArray, out: OutputStream
    ) {
        val parts  = requestLine.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path   = parts.getOrElse(1) { "/" }.substringBefore("?")
        val cSeq   = headers["cseq"] ?: ""

        when {
            method == "GET"  && path == "/info"        -> handleInfo(out)
            method == "POST" && path == "/pair-setup"  -> handlePairSetup(body, out)
            method == "POST" && path == "/pair-verify" -> handlePairVerify(body, out)
            method == "POST" && path == "/fp-setup"    -> handleFpSetup(body, out)
            else -> sendHttp(out, 404, "application/octet-stream", ByteArray(0))
        }
    }

    private fun handleInfo(out: OutputStream) {
        val pkHex = AirPlayCrypto.bytesToHex(crypto.ltEdPub.encoded)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>deviceID</key><string>$deviceId</string>
<key>features</key><integer>130367488517</integer>
<key>model</key><string>AppleTV5,3</string>
<key>pk</key><string>$pkHex</string>
<key>pi</key><string>00000000-0000-0000-0000-000000000000</string>
<key>sourceVersion</key><string>220.68</string>
<key>statusFlags</key><integer>68</integer>
<key>osvers</key><string>9.0</string>
</dict></plist>"""
        sendHttp(out, 200, "text/x-apple-plist+xml", xml.toByteArray())
    }

    private fun handlePairSetup(body: ByteArray, out: OutputStream) {
        val tlv   = TLV8.decode(body)
        val state = tlv[TLV8.T_STATE]?.firstOrNull()?.toInt() ?: 0

        when (state) {
            1 -> {
                // M1: start SRP, return salt + B
                val (salt, B) = crypto.srpBegin()
                val resp = TLV8.encode(mapOf(
                    TLV8.T_STATE      to byteArrayOf(2),
                    TLV8.T_SALT       to salt,
                    TLV8.T_PUBLIC_KEY to B
                ))
                sendHttp(out, 200, "application/octet-stream", resp)
            }
            3 -> {
                // M3: verify client proof, return M2
                val A  = tlv[TLV8.T_PUBLIC_KEY] ?: run { sendTlvError(out, 2); return }
                val M1 = tlv[TLV8.T_PROOF]      ?: run { sendTlvError(out, 2); return }
                val M2 = crypto.srpVerify(A, M1) ?: run { sendTlvError(out, 2); return }
                val resp = TLV8.encode(mapOf(
                    TLV8.T_STATE to byteArrayOf(4),
                    TLV8.T_PROOF to M2
                ))
                sendHttp(out, 200, "application/octet-stream", resp)
            }
            else -> sendTlvError(out, 6)
        }
    }

    private fun handlePairVerify(body: ByteArray, out: OutputStream) {
        val tlv   = TLV8.decode(body)
        val state = tlv[TLV8.T_STATE]?.firstOrNull()?.toInt() ?: 0

        when (state) {
            1 -> {
                val iosCurvePub = tlv[TLV8.T_PUBLIC_KEY] ?: run { sendTlvError(out, 2); return }
                val encrypted   = crypto.pvPhase1(iosCurvePub)
                val resp = TLV8.encode(mapOf(
                    TLV8.T_STATE      to byteArrayOf(2),
                    TLV8.T_PUBLIC_KEY to crypto.pvMyPub!!.encoded,
                    TLV8.T_ENCRYPTED  to encrypted
                ))
                sendHttp(out, 200, "application/octet-stream", resp)
            }
            3 -> {
                val enc = tlv[TLV8.T_ENCRYPTED] ?: run { sendTlvError(out, 2); return }
                if (!crypto.pvPhase2(enc)) { sendTlvError(out, 2); return }
                val resp = TLV8.encode(mapOf(TLV8.T_STATE to byteArrayOf(4)))
                sendHttp(out, 200, "application/octet-stream", resp)
            }
            else -> sendTlvError(out, 6)
        }
    }

    private fun handleFpSetup(body: ByteArray, out: OutputStream) {
        // FairPlay device authentication.
        // Phase 1: body[14] == 1 → respond with 142-byte header
        // Phase 2: body[14] == 2 → respond with 32-byte key response
        // Minimal stub — sufficient for some iOS versions.
        // For full iOS 14+ support replace with UxPlay fairplay bytes.
        val phase = if (body.size > 14) body[14].toInt() and 0xFF else 1
        val stub = when (phase) {
            1    -> ByteArray(142) // Apple TV 4 fp header — replace with UxPlay bytes
            else -> ByteArray(32)
        }
        sendHttp(out, 200, "application/octet-stream", stub)
    }

    // ── RTSP handlers ────────────────────────────────────────────────────

    private var sdpVideoPort = RTP_VIDEO_PORT
    private var sdpWidth  = 1920
    private var sdpHeight = 1080
    private var sdpSps    = ByteArray(0)
    private var sdpPps    = ByteArray(0)

    private fun handleRtsp(
        requestLine: String, headers: Map<String, String>,
        body: ByteArray, out: OutputStream
    ) {
        val method = requestLine.split(" ")[0]
        val cSeq   = headers["cseq"] ?: "0"

        when (method) {
            "OPTIONS" -> {
                rtspReply(out, 200, cSeq, mapOf(
                    "Public" to "OPTIONS, ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, " +
                                "GET_PARAMETER, SET_PARAMETER, POST, GET"
                ))
            }
            "ANNOUNCE" -> {
                parseSdp(String(body))
                rtspReply(out, 200, cSeq)
            }
            "SETUP" -> {
                // iOS sends "Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record"
                // We respond with our server port
                val transport = headers["transport"] ?: ""
                val isVideo   = requestLine.contains("/video", ignoreCase = true) ||
                                requestLine.contains("streamid=1", ignoreCase = true) ||
                                !requestLine.contains("/audio", ignoreCase = true)
                val serverPort = if (isVideo) RTP_VIDEO_PORT else RTP_AUDIO_PORT
                rtspReply(out, 200, cSeq, mapOf(
                    "Transport" to "$transport;server_port=$serverPort",
                    "Session"   to "1"
                ))
            }
            "RECORD" -> {
                rtspReply(out, 200, cSeq, mapOf("Session" to "1"))
                onMirrorStart(sdpVideoPort, sdpWidth, sdpHeight, sdpSps, sdpPps)
            }
            "TEARDOWN" -> {
                rtspReply(out, 200, cSeq)
                onMirrorStop()
            }
            "GET_PARAMETER", "SET_PARAMETER", "FLUSH" -> {
                rtspReply(out, 200, cSeq)
            }
            else -> rtspReply(out, 501, cSeq)
        }
    }

    private fun parseSdp(sdp: String) {
        var width = 1920; var height = 1080
        var sps = ByteArray(0); var pps = ByteArray(0)

        sdp.lines().forEach { l ->
            when {
                l.startsWith("a=framesize:") -> {
                    val parts = l.removePrefix("a=framesize:").trim().split(" ")
                    if (parts.size == 2) {
                        val wh = parts[1].split("-")
                        width  = wh.getOrNull(0)?.toIntOrNull() ?: width
                        height = wh.getOrNull(1)?.toIntOrNull() ?: height
                    }
                }
                l.startsWith("a=fmtp:") -> {
                    // e.g. a=fmtp:96 packetization-mode=1;profile-level-id=...;sprop-parameter-sets=AAAA,BBBB
                    val params = l.substringAfter("sprop-parameter-sets=", "")
                    if (params.isNotEmpty()) {
                        val sets = params.split(",")
                        sps = android.util.Base64.decode(sets.getOrElse(0) { "" }, android.util.Base64.NO_WRAP)
                        pps = android.util.Base64.decode(sets.getOrElse(1) { "" }, android.util.Base64.NO_WRAP)
                    }
                }
            }
        }
        sdpWidth = width; sdpHeight = height; sdpSps = sps; sdpPps = pps
    }

    // ── Response helpers ─────────────────────────────────────────────────

    private fun sendHttp(out: OutputStream, code: Int, ct: String, body: ByteArray) {
        val status = if (code == 200) "OK" else "Error"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $status\r\n")
        sb.append("Content-Type: $ct\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun rtspReply(
        out: OutputStream, code: Int, cSeq: String,
        extra: Map<String, String> = emptyMap()
    ) {
        val reason = if (code == 200) "OK" else "Not Implemented"
        val sb = StringBuilder("RTSP/1.0 $code $reason\r\nCSeq: $cSeq\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun sendTlvError(out: OutputStream, code: Int) {
        val body = TLV8.encode(mapOf(
            TLV8.T_STATE to byteArrayOf(0),
            TLV8.T_ERROR to byteArrayOf(code.toByte())
        ))
        sendHttp(out, 200, "application/octet-stream", body)
    }
}
