package com.krejci.qringset.ble

import java.util.Calendar

/**
 * Pure, stateless helpers for the Colmi/QRing BLE wire format — no Android or connection state.
 *
 * Commands are 16-byte packets `[cmd, …subdata…, checksum]` where
 * `checksum = sum(bytes[0..14]) % 255`. Log payloads pack little-endian integers and
 * per-day timestamps, so the byte readers and epoch helpers the parsers need live here too.
 */
object RingProtocol {

    /** Build a 16-byte command packet from its header bytes, appending the checksum. */
    fun packet(head: ByteArray): ByteArray {
        val p = ByteArray(16)
        System.arraycopy(head, 0, p, 0, head.size)
        var sum = 0
        for (i in 0..14) sum += p[i].toInt() and 0xFF
        p[15] = (sum % 255).toByte()
        return p
    }

    /** Unsigned little-endian 16-bit value at [off]. */
    fun u16(r: ByteArray, off: Int) = (r[off].toInt() and 0xFF) or ((r[off + 1].toInt() and 0xFF) shl 8)

    /** Unsigned little-endian 32-bit value at [off]. */
    fun le32(r: ByteArray, off: Int): Long =
        (r[off].toLong() and 0xFF) or ((r[off + 1].toLong() and 0xFF) shl 8) or
            ((r[off + 2].toLong() and 0xFF) shl 16) or ((r[off + 3].toLong() and 0xFF) shl 24)

    /** Read a binary-coded-decimal byte (e.g. 0x26 -> 26). */
    fun bcd(b: Byte): Int = ("%02x".format(b.toInt() and 0xFF)).toInt()

    /** Space-separated hex dump, for logging raw packets. */
    fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    /** Unix seconds at local midnight, [dayOffset] days from today. */
    fun midnight(dayOffset: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        return c.timeInMillis / 1000
    }

    /** Unix seconds for a local wall-clock date/time. */
    fun ymd(y: Int, mo0: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo0, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }
}
