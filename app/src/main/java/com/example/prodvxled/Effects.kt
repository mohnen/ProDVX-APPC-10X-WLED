package com.example.prodvxled

import android.graphics.Color
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Subset of WLED effects, placed at their real WLED effect IDs so that
 * controllers sending a numeric `fx` get what they expect. Unimplemented
 * slots are reported as "RSVD" and fall back to Solid.
 */
object Effects {
    val NAMES: Map<Int, String> = mapOf(
        0 to "Solid", 1 to "Blink", 2 to "Breathe", 3 to "Wipe",
        9 to "Rainbow", 10 to "Scan", 11 to "Dual Scan", 12 to "Fade",
        15 to "Running", 17 to "Twinkle", 20 to "Sparkle", 23 to "Strobe",
        28 to "Chase", 42 to "Fireworks", 43 to "Rain", 45 to "Fire Flicker",
        46 to "Gradient", 66 to "Fire 2012", 76 to "Meteor",
    )

    const val FX_COUNT = 77          // last implemented id + 1
    const val PAL_COUNT = 37         // last implemented palette id + 1

    private val PAL_NAMES: Map<Int, String> = mapOf(
        0 to "Default", 1 to "* Random Cycle", 2 to "* Color 1", 6 to "Party",
        11 to "Rainbow", 13 to "Sunset", 35 to "Fire", 36 to "Icefire",
    )

    fun effectsJson(): JSONArray {
        val a = JSONArray()
        for (i in 0 until FX_COUNT) a.put(NAMES[i] ?: "RSVD")
        return a
    }

    fun palettesJson(): JSONArray {
        val a = JSONArray()
        for (i in 0 until PAL_COUNT) a.put(PAL_NAMES[i] ?: "RSVD")
        return a
    }

    fun isAnimated(fx: Int) = fx != 0

    // ---------- color helpers (packed 0xRRGGBB) ----------

    fun mix(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val r = ((a shr 16 and 0xFF) * (1 - tt) + (b shr 16 and 0xFF) * tt).toInt()
        val g = ((a shr 8 and 0xFF) * (1 - tt) + (b shr 8 and 0xFF) * tt).toInt()
        val bl = ((a and 0xFF) * (1 - tt) + (b and 0xFF) * tt).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    fun dimColor(c: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return (((c shr 16 and 0xFF) * ff).toInt() shl 16) or
               (((c shr 8 and 0xFF) * ff).toInt() shl 8) or
               ((c and 0xFF) * ff).toInt()
    }

    private fun hsv(h: Float, s: Float = 1f, v: Float = 1f): Int =
        Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v)) and 0xFFFFFF

    /** Palette lookup: t in 0..1 across the palette. */
    fun palColor(pal: Int, t: Float, col: IntArray): Int {
        val tt = ((t % 1f) + 1f) % 1f
        return when (pal) {
            1 -> hsv(tt * 360f)                                        // random cycle ~ rainbow hues
            2 -> col[0]                                                // color 1
            6 -> {                                                     // party: fixed bright hues
                val party = intArrayOf(0xFF5500, 0xA840FF, 0x00E8FF, 0xFF0080, 0x00FF40, 0xFFE000)
                party[(tt * party.size).toInt() % party.size]
            }
            11 -> hsv(tt * 360f)                                       // rainbow
            13 -> {                                                    // sunset: dark red -> orange -> yellow -> purple
                val stops = intArrayOf(0x1B0A47, 0xB8211E, 0xF5A623, 0xF8E71C, 0x4A1259)
                gradStop(stops, tt)
            }
            35 -> {                                                    // fire: black -> red -> yellow -> white
                val stops = intArrayOf(0x000000, 0x660000, 0xFF0000, 0xFF8800, 0xFFFF00, 0xFFFFFF)
                gradStop(stops, tt)
            }
            36 -> {                                                    // icefire: black -> blue -> cyan -> white
                val stops = intArrayOf(0x000000, 0x000066, 0x0000FF, 0x00FFFF, 0xFFFFFF)
                gradStop(stops, tt)
            }
            else -> col[0]                                             // default / unimplemented
        }
    }

    private fun gradStop(stops: IntArray, t: Float): Int {
        val x = t.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceIn(0, stops.size - 2)
        return mix(stops[i], stops[i + 1], x - i)
    }

    // ---------- scratch memory for stateful effects ----------

    private val heat = FloatArray(WledState.LED_COUNT)
    private val fwBuf = IntArray(WledState.LED_COUNT)

    /**
     * Render one segment into `buf` (full 52-LED array; seg.start..seg.stop used).
     * `now` is ms. Effect speed from seg.sx, intensity from seg.ix.
     */
    fun render(seg: WledState.Seg, buf: IntArray, now: Long) {
        val start = seg.start.coerceIn(0, WledState.LED_COUNT)
        val stop = seg.stop.coerceIn(start, WledState.LED_COUNT)
        val len = stop - start
        if (len <= 0) return
        val c = seg.col
        // WLED-ish time base: speed 0..255 -> roughly 0.25x..8x
        val spd = 0.25f + 8f * seg.sx / 255f
        val t = now / 1000f * spd

        when (seg.fx) {
            0 -> fill(buf, start, stop, c[0])

            1 -> fill(buf, start, stop, if ((t * 2).toInt() % 2 == 0) c[0] else c[1])

            2 -> {  // breathe
                val b = 0.5f + 0.5f * sin(t * Math.PI).toFloat()
                fill(buf, start, stop, dimColor(c[0], 0.05f + 0.95f * b))
            }

            3 -> {  // wipe
                val cycle = (t * len).toInt()
                val pos = cycle % len
                val fwd = (cycle / len) % 2 == 0
                for (i in 0 until len) buf[start + i] = if (if (fwd) i <= pos else (len - 1 - i) <= pos) c[0] else c[1]
            }

            9 -> for (i in 0 until len) buf[start + i] = hsv(i * 360f / len + t * 40f)

            10, 11 -> {  // scan / dual scan
                fill(buf, start, stop, c[1])
                val pos = bounce((t * len).toInt(), len)
                buf[start + pos] = c[0]
                if (seg.fx == 11 && len > 2) buf[start + (len - 1 - pos)] = c[0]
            }

            12 -> fill(buf, start, stop, mix(c[0], c[1], 0.5f + 0.5f * sin(t * Math.PI).toFloat()))

            15 -> {  // running: sine wave of c0 over c1
                for (i in 0 until len) {
                    val w = 0.5f + 0.5f * sin((i * Math.PI * 4 / len) - t * Math.PI * 2).toFloat()
                    buf[start + i] = mix(c[1], c[0], w * w)
                }
            }

            17, 20 -> {  // twinkle / sparkle
                fill(buf, start, stop, c[1])
                val density = max(1, len * seg.ix / 255 / 2)
                val tick = (t * 20).toInt()
                for (k in 0 until density) {
                    val h = (k * 2654435761L xor tick.toLong()).let { (it xor (it shr 16)).toInt() }
                    val idx = abs(h) % len
                    val decay = abs(h shr 8) % 100 / 100f
                    buf[start + idx] = if (seg.fx == 20) c[0] else mix(c[1], c[0], 1f - decay)
                }
            }

            23 -> fill(buf, start, stop, if ((t * 12).toInt() % 4 == 0) c[0] else 0)  // strobe

            28 -> for (i in 0 until len) {  // theater chase
                buf[start + i] = if (((i - (t * 8).toInt()) % 3 + 3) % 3 == 0) c[0] else c[1]
            }

            42 -> {  // fireworks: decaying random bursts
                for (i in start until stop) buf[i] = dimColor(fwBuf[i], 0.88f)
                val rate = 1 + seg.ix / 64
                repeat(rate) {
                    val h = (now xor (it * 7919L)).let { v -> (v xor (v shr 13)) }
                    if ((h and 0xFF) < 40) {
                        val idx = start + abs(h.toInt() shr 8) % len
                        buf[idx] = c[0]
                    }
                }
                System.arraycopy(buf, 0, fwBuf, 0, buf.size)
            }

            43 -> {  // rain: c0 comet with decaying tail + c1 sparkle
                for (i in start until stop) buf[i] = dimColor(fwBuf[i], 0.80f)
                val head = ((t * len * 0.7f).toInt() % len + len) % len
                buf[start + head] = c[0]
                val h = (now xor 0x9E3779B9L).let { (it xor (it shr 16)).toInt() }
                if (h and 0x3F < seg.ix / 4) buf[start + abs(h shr 8) % len] = c[1]
                System.arraycopy(buf, 0, fwBuf, 0, buf.size)
            }

            45 -> {  // fire flicker
                for (i in 0 until len) {
                    val h = ((i * 1103515245L) + now / 50).let { (it xor (it shr 13)) }.toInt()
                    val f = 0.65f + 0.35f * (abs(h) % 100) / 100f
                    buf[start + i] = dimColor(c[0], f)
                }
            }

            46 -> for (i in 0 until len) buf[start + i] = mix(c[0], c[1], i.toFloat() / max(1, len - 1))

            66 -> {  // fire 2012
                val cool = 20f + seg.ix / 10f
                for (i in 0 until len) {
                    val h = ((i * 2654435761L) xor (now / 40)).let { (it xor (it shr 16)) }.toInt()
                    heat[i] = max(0f, heat[i] - (abs(h) % (cool.toInt() + 1)))
                }
                for (k in 2 until len) heat[k] = (heat[k - 1] + heat[k - 2] + heat[k - 2]) / 3.05f
                val sparks = 1 + seg.ix / 80
                repeat(sparks) {
                    val h = (now / 33 xor (it * 40503L)).let { v -> (v xor (v shr 11)) }.toInt()
                    if (abs(h) % 100 < 30) {
                        val y = abs(h shr 8) % min(7, len)
                        heat[y] = min(255f, heat[y] + 120 + abs(h shr 4) % 100)
                    }
                }
                // upside-down strip would need reverse; keep it simple: heat rises from index 0
                for (i in 0 until len) {
                    buf[start + i] = palColor(if (seg.pal == 0) 35 else seg.pal, heat[i] / 255f, c)
                }
            }

            76 -> {  // meteor: c0 head, exponential tail over c1
                fill(buf, start, stop, c[1])
                val tail = 2 + seg.ix * len / 255 / 2
                val head = (t * len * 0.8f).toInt() % (len * 2)   // shoot out and back dark
                for (i in 0 until len) {
                    val d = head - i
                    if (d in 0..tail) buf[start + i] = mix(c[1], c[0], 1f - d.toFloat() / (tail + 1))
                }
            }

            else -> fill(buf, start, stop, c[0])
        }

        // segment brightness
        if (seg.bri < 255) {
            val f = seg.bri / 255f
            for (i in start until stop) buf[i] = dimColor(buf[i], f)
        }
    }

    private fun fill(buf: IntArray, s: Int, e: Int, c: Int) { for (i in s until e) buf[i] = c }

    private fun bounce(v: Int, len: Int): Int {
        if (len <= 1) return 0
        val span = 2 * (len - 1)
        val m = ((v % span) + span) % span
        return if (m < len) m else span - m
    }
}
