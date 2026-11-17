package net.mohnen.prodvxled

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Protocol for the ProDVX LED frame (same as prodvx_led.py):
 * /dev/ttyS1 @ 115200 8N1, frame = 55 AA + 52 x [L, B, R, G] + 88
 * Writes go through a shell (`su -c` or `sh -c`), so no JNI library is needed.
 */
object LedController {
    const val LED_COUNT = 52
    private const val DEVICE = "/dev/ttyS1"
    private const val CMAX = 0x70

    val SIDES: Map<String, List<Int>> = mapOf(
        "left" to (2..12).toList(),
        "bottom" to (13..27).toList(),
        "right" to (28..38).toList(),
        "top" to (39..51).toList() + listOf(0, 1),
    )

    /**
     * colors: 52 packed RGB ints (0xRRGGBB), level 1..15 (global brightness nibble),
     * dim 0..1 extra scaling applied to the RGB channels (used to smooth the coarse
     * 15-step global brightness into a continuous 0..255 WLED brightness).
     */
    fun buildFrame(colors: IntArray, level: Int, dim: Float = 1f): ByteArray {
        require(colors.size == LED_COUNT)
        val l = 0xF0 + level.coerceIn(1, 15)
        val out = ByteArray(2 + LED_COUNT * 4 + 1)
        out[0] = 0x55
        out[1] = 0xAA.toByte()
        colors.forEachIndexed { i, c ->
            val o = 2 + i * 4
            out[o] = l.toByte()
            out[o + 1] = scale(((c) and 0xFF) * dim)          // B
            out[o + 2] = scale(((c shr 16) and 0xFF) * dim)   // R
            out[o + 3] = scale(((c shr 8) and 0xFF) * dim)    // G
        }
        out[out.size - 1] = 0x88.toByte()
        return out
    }

    private fun scale(v: Float): Byte = (v * CMAX / 255f + 0.5f).toInt().coerceIn(0, CMAX).toByte()
    private fun scale(v: Int): Byte = ((v * CMAX + 127) / 255).toByte()

    /** Blocking. Returns null on success, otherwise an error message. Call off the main thread. */
    fun send(cacheDir: File, frame: ByteArray, useRoot: Boolean): String? {
        return try {
            val f = File(cacheDir, "led.bin")
            f.writeBytes(frame)
            f.setReadable(true, false)
            val path = f.absolutePath
            val cmd = "stty -F $DEVICE 115200 raw -echo 2>/dev/null; " +
                "cat $path > $DEVICE && sleep 0.03 && cat $path > $DEVICE"
            val args = if (useRoot) arrayOf("su", "-c", cmd) else arrayOf("sh", "-c", cmd)
            val p = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code == 0) null else "Exit code $code${if (output.isNotEmpty()) ": $output" else ""}"
        } catch (e: IOException) {
            e.message ?: "I/O error"
        }
    }

    /**
     * Persistent writer: keeps `cat > DEVICE` alive so frames can be pushed
     * back-to-back (needed for effects/realtime at ~30fps where spawning a
     * process per frame would be far too slow).
     */
    class Pipe(private val device: String = DEVICE) {
        @Volatile var useRoot: Boolean = true
            set(v) { if (v != field) { field = v; close() } }

        private var proc: Process? = null
        private var out: OutputStream? = null

        @Synchronized
        fun write(frame: ByteArray): String? = try {
            ensure()
            out!!.write(frame)
            out!!.flush()
            null
        } catch (e: Exception) {
            close()
            e.message ?: "write failed"
        }

        private fun ensure() {
            val p = proc
            if (p != null && p.isAlive && out != null) return
            close()
            val sh = if (useRoot) "su" else "sh"
            try {
                ProcessBuilder(sh, "-c", "stty -F $device 115200 raw -echo")
                    .redirectErrorStream(true).start().waitFor()
            } catch (_: Exception) { }
            proc = ProcessBuilder(sh, "-c", "cat > $device")
                .redirectErrorStream(true).start()
            out = BufferedOutputStream(proc!!.outputStream, 64 * 1024)
        }

        @Synchronized
        fun close() {
            try { out?.close() } catch (_: Exception) { }
            try { proc?.destroy() } catch (_: Exception) { }
            proc = null
            out = null
        }
    }
}
