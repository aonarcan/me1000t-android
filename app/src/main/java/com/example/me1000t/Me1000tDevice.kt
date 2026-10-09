package com.example.me1000t

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Wraps a claimed USB-HID connection to the Magneti Marelli ME1000T box and
 * implements its ASCII command protocol (reverse-engineered + capture-verified).
 *
 * Wire format (confirmed from a real USB capture):
 *   - Commands  -> interrupt OUT endpoint, 32-byte report, ASCII, zero-padded,
 *                  NO leading report-ID byte.
 *   - Responses -> interrupt IN endpoint, 32-byte report, ASCII; the reply
 *                  echoes the 2-char opcode, then data.
 *
 * Robustness: the box occasionally drops or delays a reply, so each exchange
 * is retried a few times with a short "settle" gap between the send and the
 * read. All our commands are idempotent (re-sending the same read, or re-
 * selecting the same map, is harmless), so re-sending on timeout is safe.
 *
 * All calls are serialized by the caller (a single-thread executor).
 */
class Me1000tDevice(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint
) {
    companion object {
        const val VID = 0x26D6          // 9942  Magneti Marelli
        const val PID = 0x0E19          // 3609  ME1000T
        const val REPORT_LEN = 32
        const val READ_TIMEOUT_MS = 700 // per attempt
        const val WRITE_TIMEOUT_MS = 500
        const val SETTLE_MS = 15L       // wait after sending before reading
        const val BACKOFF_MS = 25L      // wait before a retry
        const val ATTEMPTS = 4
    }

    /**
     * Send an ASCII command (e.g. "?03", "!031", "?0610") and return the ASCII
     * reply, retrying on timeout. Returns null only if all attempts fail.
     */
    @Synchronized
    fun sendCommand(cmd: String): String? {
        val ascii = cmd.toByteArray(Charsets.US_ASCII)
        if (ascii.size > REPORT_LEN) return null

        val out = ByteArray(REPORT_LEN)                 // zero-filled -> padding + terminator
        System.arraycopy(ascii, 0, out, 0, ascii.size)

        repeat(ATTEMPTS) { attempt ->
            val wrote = connection.bulkTransfer(epOut, out, out.size, WRITE_TIMEOUT_MS)
            if (wrote >= 0) {
                try { Thread.sleep(SETTLE_MS) } catch (_: InterruptedException) {}
                val inBuf = ByteArray(REPORT_LEN)
                val read = connection.bulkTransfer(epIn, inBuf, inBuf.size, READ_TIMEOUT_MS)
                if (read > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until read) {
                        val b = inBuf[i].toInt() and 0xFF
                        if (b == 0) break                // stop at null padding
                        sb.append(b.toChar())
                    }
                    val s = sb.toString()
                    if (s.isNotEmpty()) return s
                }
            }
            if (attempt < ATTEMPTS - 1) {
                try { Thread.sleep(BACKOFF_MS) } catch (_: InterruptedException) {}
            }
        }
        return null
    }

    // --- Convenience wrappers for the confirmed-safe commands ---

    /** Status poll. Reply like "0321000": [2] = active map digit, rest = ready/lock/settings flags. */
    fun readStatus(): String? = sendCommand("?03")

    /** Send a raw map-switch command (0,1,2). Prefer MainActivity's verified switch. */
    fun switchMap(n: Int): String? = sendCommand("!03$n")

    /** Parse the active map digit (0,1,2) from a status reply, or null. */
    fun activeMapOf(status: String?): Int? {
        if (status == null || status.length < 3 || !status.startsWith("03")) return null
        val c = status[2]
        return if (c in '0'..'9') c - '0' else null
    }

    /**
     * Read one map cell. x1 = map/row index, x2 = point/column index (single hex digit each).
     * Reply "06XYVV" -> returns the value byte 0..255, or null.
     */
    fun readCell(x1: Int, x2: Int): Int? {
        val cmd = "?06" + x1.toString(16).uppercase() + x2.toString(16).uppercase()
        val r = sendCommand(cmd) ?: return null
        if (r.length < 6) return null
        return try { r.substring(4, 6).toInt(16) } catch (e: Exception) { null }
    }

    /**
     * Write the engine warm-up delay: map cell (0,1), command "!0601<VV>".
     * The ONLY write this app performs. Bounded 0..255, verified by reading the cell back.
     * Returns true only if the box reports the new value; false on any error/mismatch.
     */
    fun setWarmup(seconds: Int): Boolean {
        if (seconds < 0 || seconds > 255) return false
        val cmd = "!0601" + String.format("%02X", seconds)
        sendCommand(cmd) ?: return false
        // verify by reading cell (0,1) back
        return readCell(0, 1) == seconds
    }

    fun close() {
        try { connection.releaseInterface(intf) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }
}
