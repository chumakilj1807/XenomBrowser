package com.xenombrowser.airplay

import java.io.ByteArrayOutputStream

object TLV8 {
    const val T_METHOD        = 0x00
    const val T_IDENTIFIER    = 0x01
    const val T_SALT          = 0x02
    const val T_PUBLIC_KEY    = 0x03
    const val T_PROOF         = 0x04
    const val T_ENCRYPTED     = 0x05
    const val T_STATE         = 0x06
    const val T_ERROR         = 0x07
    const val T_SIGNATURE     = 0x0A

    fun encode(data: Map<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        data.forEach { (type, value) ->
            var offset = 0
            while (true) {
                val chunkLen = minOf(255, value.size - offset)
                out.write(type and 0xFF)
                out.write(chunkLen)
                out.write(value, offset, chunkLen)
                offset += chunkLen
                if (offset >= value.size) break
            }
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val result = LinkedHashMap<Int, ByteArrayOutputStream>()
        var i = 0
        while (i + 1 < data.size) {
            val type   = data[i].toInt() and 0xFF
            val length = data[i + 1].toInt() and 0xFF
            i += 2
            if (i + length > data.size) break
            result.getOrPut(type) { ByteArrayOutputStream() }.write(data, i, length)
            i += length
        }
        return result.mapValues { it.value.toByteArray() }
    }

    fun singleByte(type: Int, value: Int) = encode(mapOf(type to byteArrayOf(value.toByte())))
}
