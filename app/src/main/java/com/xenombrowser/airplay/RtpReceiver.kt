package com.xenombrowser.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket

private const val TAG = "RtpReceiver"

class RtpReceiver(
    private val port: Int,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val spsParam: ByteArray,
    private val ppsParam: ByteArray
) : Thread("RtpReceiver") {

    @Volatile var running = false
    private var codec: MediaCodec? = null

    override fun run() {
        running = true
        val socket = DatagramSocket(port)
        socket.soTimeout = 3000

        setupCodec()

        val buf = ByteArray(65535)
        val packet = DatagramPacket(buf, buf.size)

        while (running) {
            try {
                socket.receive(packet)
                processRtp(buf, packet.length)
            } catch (_: java.net.SocketTimeoutException) {
                // keep waiting
            } catch (e: Exception) {
                if (running) Log.w(TAG, "RTP error: ${e.message}")
                break
            }
        }

        socket.close()
        codec?.stop()
        codec?.release()
        codec = null
    }

    private fun setupCodec() {
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
            if (spsParam.isNotEmpty()) {
                fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(startCode + spsParam))
            }
            if (ppsParam.isNotEmpty()) {
                fmt.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(startCode + ppsParam))
            }
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
        } catch (e: Exception) {
            Log.e(TAG, "Codec setup failed: ${e.message}")
        }
    }

    private val startCode = byteArrayOf(0, 0, 0, 1)

    private fun processRtp(data: ByteArray, len: Int) {
        if (len < 12) return
        // Skip 12-byte RTP header
        var offset = 12
        // Skip CSRC list
        val cc = (data[0].toInt() and 0x0F)
        offset += cc * 4
        // Skip extension header if present
        if ((data[0].toInt() and 0x10) != 0 && offset + 4 <= len) {
            val extLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4 + extLen * 4
        }
        if (offset >= len) return

        val pts = ((data[4].toLong() and 0xFF) shl 24) or
                  ((data[5].toLong() and 0xFF) shl 16) or
                  ((data[6].toLong() and 0xFF) shl 8)  or
                  (data[7].toLong() and 0xFF)

        // Parse AVCC: 4-byte length + NAL unit, repeat
        while (offset + 4 < len) {
            val nalLen = ((data[offset].toInt() and 0xFF) shl 24) or
                         ((data[offset+1].toInt() and 0xFF) shl 16) or
                         ((data[offset+2].toInt() and 0xFF) shl 8)  or
                         (data[offset+3].toInt() and 0xFF)
            offset += 4
            if (nalLen <= 0 || offset + nalLen > len) break

            val nal = startCode + data.copyOfRange(offset, offset + nalLen)
            feedCodec(nal, pts * 1000L / 90) // 90kHz → microseconds
            offset += nalLen
        }
    }

    private fun feedCodec(nal: ByteArray, pts: Long) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(nal)
                c.queueInputBuffer(idx, 0, nal.size, pts, 0)
            }
            val info = MediaCodec.BufferInfo()
            val outIdx = c.dequeueOutputBuffer(info, 0)
            if (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true) // render to Surface
            }
        } catch (_: Exception) {}
    }

    private operator fun ByteArray.plus(other: ByteArray) =
        ByteArray(size + other.size).also { copyInto(it); other.copyInto(it, size) }
}
