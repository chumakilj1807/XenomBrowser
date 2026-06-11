package com.xenombrowser.airplay

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

private const val TAG = "AirPlayServer"
const val AIRPLAY_PORT   = 7000
const val RTP_VIDEO_PORT = 7010
const val RTP_AUDIO_PORT = 7011

class AirPlayServer(
    private val deviceId: String,
    private val onMirrorStart: (port: Int, w: Int, h: Int, sps: ByteArray, pps: ByteArray) -> Unit,
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
                  catch (e: Exception) { Log.e(TAG, "Accept error: ${e.message}") }
            }
        }
        Log.i(TAG, "AirPlay server listening on :$AIRPLAY_PORT")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    // ── Connection handler ───────────────────────────────────────────────

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 60_000
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()

            while (running && !socket.isClosed) {
                // Read first line of request
                val firstLine = readLine(inp) ?: break
                if (firstLine.isBlank()) continue

                Log.d(TAG, ">>> $firstLine")

                // Read headers
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val hLine = readLine(inp) ?: break
                    if (hLine.isBlank()) break
                    val ci = hLine.indexOf(':')
                    if (ci > 0) headers[hLine.substring(0, ci).trim().lowercase()] =
                        hLine.substring(ci + 1).trim()
                }

                // Read body as raw bytes — CRITICAL: never use BufferedReader for binary TLV8
                val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
                val body    = readExact(inp, bodyLen)

                val isRtsp = firstLine.contains("RTSP/1.0") && !firstLine.startsWith("HTTP")
                if (isRtsp) handleRtsp(firstLine, headers, body, out)
                else        handleHttp(firstLine, headers, body, out)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── Raw I/O ──────────────────────────────────────────────────────────

    /** Read one CRLF-terminated line from the stream as ASCII. */
    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /** Read exactly `n` bytes from stream (retries until full). */
    private fun readExact(inp: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val read = inp.read(buf, off, n - off)
            if (read < 0) break
            off += read
        }
        return buf
    }

    // ── HTTP handlers ────────────────────────────────────────────────────

    private fun handleHttp(line: String, hdrs: Map<String, String>, body: ByteArray, out: OutputStream) {
        val parts  = line.split(" ")
        val method = parts.getOrElse(0) { "GET" }
        val path   = parts.getOrElse(1) { "/" }.substringBefore("?")

        Log.d(TAG, "HTTP $method $path  body=${body.size}b")

        when {
            method == "GET"  && path == "/info"        -> handleInfo(out)
            method == "POST" && path == "/pair-setup"  -> handlePairSetup(body, out)
            method == "POST" && path == "/pair-verify" -> sendHttp(out, 200, "application/octet-stream",
                                                            TLV8.singleByte(TLV8.T_STATE, 4))
            method == "POST" && path == "/fp-setup"    -> handleFpSetup(body, out)
            else -> sendHttp(out, 200, "application/octet-stream", ByteArray(0))
        }
    }

    private fun handleInfo(out: OutputStream) {
        // No "pk" field → iOS skips pairing and FairPlay, goes straight to RTSP mirroring.
        // The features value 0x4A7FBFD5 enables screen mirroring.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>deviceID</key><string>$deviceId</string>
<key>features</key><integer>1249992661</integer>
<key>model</key><string>AppleTV5,3</string>
<key>srcvers</key><string>220.68</string>
<key>osvers</key><string>9.0</string>
<key>statusFlags</key><integer>68</integer>
</dict></plist>"""
        sendHttp(out, 200, "text/x-apple-plist+xml", xml.toByteArray(Charsets.UTF_8))
    }

    private fun handlePairSetup(body: ByteArray, out: OutputStream) {
        // Without pk in /info iOS shouldn't reach here.
        // If it does, acknowledge all states with empty OK so it continues.
        val tlv   = TLV8.decode(body)
        val state = tlv[TLV8.T_STATE]?.firstOrNull()?.toInt() ?: 0
        Log.d(TAG, "pair-setup state=$state")
        val resp = TLV8.singleByte(TLV8.T_STATE, state + 1)
        sendHttp(out, 200, "application/octet-stream", resp)
    }

    private fun handleFpSetup(body: ByteArray, out: OutputStream) {
        // FairPlay two-phase handshake.
        // Phase 1 (body ~16 bytes): respond with 142-byte "FPLY" blob.
        // Phase 2 (body ~164 bytes): respond with 32-byte session key.
        // Using a stub with the correct FPLY header — sufficient for many iOS versions.
        // For iOS 14+ with full validation, embed UxPlay fairplay bytes here.
        val phase = if (body.size > 14) body[14].toInt() and 0xFF else 1
        Log.d(TAG, "fp-setup phase=$phase body=${body.size}b")

        val stub = when (phase) {
            1 -> byteArrayOf(
                0x46, 0x50, 0x4C, 0x59,  // "FPLY" magic
                0x00, 0x03, 0x02, 0x00,  // version
                0x00, 0x00, 0x00, 0x00,  // flags
                0x00, 0x82.toByte()      // sub-type, length high
            ) + ByteArray(128)           // 128-byte RSA payload (stub)
            else -> ByteArray(32)        // 32-byte AES session key (stub)
        }
        sendHttp(out, 200, "application/octet-stream", stub)
    }

    // ── RTSP handlers ────────────────────────────────────────────────────

    private var sdpWidth  = 1920
    private var sdpHeight = 1080
    private var sdpSps    = ByteArray(0)
    private var sdpPps    = ByteArray(0)

    private fun handleRtsp(line: String, hdrs: Map<String, String>, body: ByteArray, out: OutputStream) {
        val method = line.split(" ")[0]
        val cSeq   = hdrs["cseq"] ?: "0"
        Log.d(TAG, "RTSP $method  CSeq=$cSeq")

        when (method) {
            "OPTIONS" -> rtspOk(out, cSeq, mapOf(
                "Public" to "OPTIONS, ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
            ))

            "ANNOUNCE" -> {
                parseSdp(String(body, Charsets.UTF_8))
                rtspOk(out, cSeq)
            }

            "SETUP" -> {
                // Determine video vs audio by URI or track id
                val uri   = line.split(" ").getOrElse(1) { "" }
                val isVid = uri.contains("video", ignoreCase = true) ||
                            uri.contains("streamid=1", ignoreCase = true) ||
                            !uri.contains("audio", ignoreCase = true)
                val port  = if (isVid) RTP_VIDEO_PORT else RTP_AUDIO_PORT
                val tx    = hdrs["transport"] ?: "RTP/AVP/UDP;unicast;mode=record"
                rtspOk(out, cSeq, mapOf(
                    "Transport" to "$tx;server_port=$port",
                    "Session"   to "XENOM1"
                ))
            }

            "RECORD" -> {
                rtspOk(out, cSeq, mapOf("Session" to "XENOM1", "RTP-Info" to ""))
                onMirrorStart(RTP_VIDEO_PORT, sdpWidth, sdpHeight, sdpSps, sdpPps)
            }

            "FLUSH"         -> rtspOk(out, cSeq, mapOf("RTP-Info" to ""))
            "TEARDOWN"      -> { rtspOk(out, cSeq); onMirrorStop() }
            "GET_PARAMETER" -> rtspOk(out, cSeq)  // heartbeat — just 200 OK
            "SET_PARAMETER" -> rtspOk(out, cSeq)

            else -> {
                Log.w(TAG, "Unknown RTSP method: $method")
                val sb = StringBuilder("RTSP/1.0 501 Not Implemented\r\nCSeq: $cSeq\r\n\r\n")
                out.write(sb.toString().toByteArray()); out.flush()
            }
        }
    }

    private fun parseSdp(sdp: String) {
        Log.d(TAG, "SDP:\n$sdp")
        sdp.lines().forEach { l ->
            when {
                l.startsWith("a=framesize:") -> {
                    // a=framesize:96 1920-1080
                    val wh = l.substringAfter(" ").split("-")
                    sdpWidth  = wh.getOrNull(0)?.toIntOrNull() ?: sdpWidth
                    sdpHeight = wh.getOrNull(1)?.toIntOrNull() ?: sdpHeight
                }
                l.startsWith("a=fmtp:") && l.contains("sprop-parameter-sets=") -> {
                    val sets = l.substringAfter("sprop-parameter-sets=").split(",")
                    sdpSps = try { android.util.Base64.decode(sets[0].trim(), android.util.Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                    sdpPps = try { android.util.Base64.decode(sets.getOrElse(1) { "" }.trim(), android.util.Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                }
            }
        }
        Log.i(TAG, "SDP parsed: ${sdpWidth}x${sdpHeight} sps=${sdpSps.size}b pps=${sdpPps.size}b")
    }

    // ── Response helpers ─────────────────────────────────────────────────

    private fun sendHttp(out: OutputStream, code: Int, ct: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "Error"
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $ct\r\nContent-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun rtspOk(out: OutputStream, cSeq: String, extra: Map<String, String> = emptyMap()) {
        val sb = StringBuilder("RTSP/1.0 200 OK\r\nCSeq: $cSeq\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
