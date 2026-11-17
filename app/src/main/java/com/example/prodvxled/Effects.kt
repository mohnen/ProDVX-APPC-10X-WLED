package com.example.prodvxled

import android.graphics.Color
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Subset of WLED effects. Ids are contiguous 0..FX_COUNT-1: clients address
 * effects by index into /json/effects, so only implemented effects are listed
 * (no RSVD gaps like stock WLED).
 */
object Effects {
    val NAMES = listOf(
        "Solid", "Blink", "Breathe", "Wipe", "Rainbow", "Scan", "Dual Scan",
        "Fade", "Running", "Twinkle", "Sparkle", "Strobe", "Chase", "Fireworks",
        "Rain", "Fire Flicker", "Gradient", "Fire 2012", "Meteor", "Palette",
    )

    val FX_COUNT = NAMES.size

    private val PAL_NAMES = listOf(
        "Default", "* Random Cycle", "* Color 1", "Rainbow", "Party", "Sunset",
        "Fire", "Icefire", "Forest", "Ocean", "Pastel", "Lava", "Cloud",
    )

    val PAL_COUNT = PAL_NAMES.size

    fun effectsJson(): JSONArray {
        val a = JSONArray()
        NAMES.forEach { a.put(it) }
        return a
    }

    fun palettesJson(): JSONArray {
        val a = JSONArray()
        PAL_NAMES.forEach { a.put(it) }
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
            3 -> hsv(tt * 360f)                                        // rainbow
            4 -> {                                                     // party: fixed bright hues
                val party = intArrayOf(0xFF5500, 0xA840FF, 0x00E8FF, 0xFF0080, 0x00FF40, 0xFFE000)
                party[(tt * party.size).toInt() % party.size]
            }
            5 -> gradStop(intArrayOf(0x1B0A47, 0xB8211E, 0xF5A623, 0xF8E71C, 0x4A1259), tt)      // sunset
            6 -> gradStop(intArrayOf(0x000000, 0x660000, 0xFF0000, 0xFF8800, 0xFFFF00, 0xFFFFFF), tt) // fire
            7 -> gradStop(intArrayOf(0x000000, 0x000066, 0x0000FF, 0x00FFFF, 0xFFFFFF), tt)      // icefire
            8 -> gradStop(intArrayOf(0x002200, 0x008800, 0x22CC44, 0x88FF00), tt)                // forest
            9 -> gradStop(intArrayOf(0x000033, 0x0033AA, 0x0077DD, 0x00CCFF), tt)                // ocean
            10 -> gradStop(intArrayOf(0xFFB3BA, 0xFFDFBA, 0xFFFFBA, 0xBAFFC9, 0xBAE1FF), tt)     // pastel
            11 -> gradStop(intArrayOf(0x200000, 0xAA0000, 0xFF4400, 0xFF8800), tt)               // lava
            12 -> gradStop(intArrayOf(0x334455, 0x8899AA, 0xCCDDEE, 0xFFFFFF), tt)               // cloud
            else -> col[0]                                             // default
        }
    }

    /** Primary color for position t in 0..1: palette when set, else seg.col[0]. */
    private fun pcol(seg: WledState.Seg, t: Float): Int =
        if (seg.pal == 0) seg.col[0] else palColor(seg.pal, t, seg.col)

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
            0 -> {  // solid; with a palette active show it as a static gradient
                if (seg.pal == 0) fill(buf, start, stop, c[0])
                else for (i in 0 until len) buf[start + i] = palColor(seg.pal, i.toFloat() / max(1, len - 1), c)
            }

            1 -> fill(buf, start, stop, if ((t * 2).toInt() % 2 == 0) c[0] else c[1])   // blink

            2 -> {  // breathe
                val b = 0.5f + 0.5f * sin(t * Math.PI).toFloat()
                fill(buf, start, stop, dimColor(c[0], 0.05f + 0.95f * b))
            }

            3 -> {  // wipe
                val cycle = (t * len).toInt()
                val pos = cycle % len
                val fwd = (cycle / len) % 2 == 0
                for (i in 0 until len) buf[start + i] =
                    if (if (fwd) i <= pos else (len - 1 - i) <= pos) pcol(seg, i.toFloat() / len) else c[1]
            }

            4 -> for (i in 0 until len) buf[start + i] =    // rainbow
                if (seg.pal == 0) hsv(i * 360f / len + t * 40f)
                else palColor(seg.pal, i.toFloat() / len + t * 0.15f, c)

            5, 6 -> {  // scan / dual scan
                fill(buf, start, stop, c[1])
                val pos = bounce((t * len).toInt(), len)
                buf[start + pos] = pcol(seg, pos.toFloat() / len)
                if (seg.fx == 6 && len > 2) buf[start + (len - 1 - pos)] = buf[start + pos]
            }

            7 -> fill(buf, start, stop, mix(c[0], c[1], 0.5f + 0.5f * sin(t * Math.PI).toFloat()))   // fade

            8 -> {  // running: sine wave of primary over c1
                for (i in 0 until len) {
                    val w = 0.5f + 0.5f * sin((i * Math.PI * 4 / len) - t * Math.PI * 2).toFloat()
                    buf[start + i] = mix(c[1], pcol(seg, i.toFloat() / len), w * w)
                }
            }

            9, 10 -> {  // twinkle / sparkle
                fill(buf, start, stop, c[1])
                val density = max(1, len * seg.ix / 255 / 2)
                val tick = (t * 20).toInt()
                for (k in 0 until density) {
                    val h = (k * 2654435761L xor tick.toLong()).let { (it xor (it shr 16)).toInt() }
                    val idx = abs(h) % len
                    val decay = abs(h shr 8) % 100 / 100f
                    val pc = pcol(seg, (abs(h shr 4) % 100) / 100f)
                    buf[start + idx] = if (seg.fx == 10) pc else mix(c[1], pc, 1f - decay)
                }
            }

            11 -> fill(buf, start, stop, if ((t * 12).toInt() % 4 == 0) c[0] else 0)  // strobe

            12 -> for (i in 0 until len) {  // theater chase
                buf[start + i] = if (((i - (t * 8).toInt()) % 3 + 3) % 3 == 0) pcol(seg, i.toFloat() / len) else c[1]
            }

            13 -> {  // fireworks: decaying random bursts
                for (i in start until stop) buf[i] = dimColor(fwBuf[i], 0.88f)
                val rate = 1 + seg.ix / 64
                repeat(rate) {
                    val h = (now xor (it * 7919L)).let { v -> (v xor (v shr 13)) }
                    if ((h and 0xFF) < 40) {
                        val idx = start + abs(h.toInt() shr 8) % len
                        buf[idx] = pcol(seg, (abs((h shr 16).toInt()) % 100) / 100f)
                    }
                }
                System.arraycopy(buf, 0, fwBuf, 0, buf.size)
            }

            14 -> {  // rain: comet with decaying tail + c1 sparkle
                for (i in start until stop) buf[i] = dimColor(fwBuf[i], 0.80f)
                val head = ((t * len * 0.7f).toInt() % len + len) % len
                buf[start + head] = pcol(seg, head.toFloat() / len)
                val h = (now xor 0x9E3779B9L).let { (it xor (it shr 16)).toInt() }
                if (h and 0x3F < seg.ix / 4) buf[start + abs(h shr 8) % len] = c[1]
                System.arraycopy(buf, 0, fwBuf, 0, buf.size)
            }

            15 -> {  // fire flicker
                for (i in 0 until len) {
                    val h = ((i * 1103515245L) + now / 50).let { (it xor (it shr 13)) }.toInt()
                    val f = 0.65f + 0.35f * (abs(h) % 100) / 100f
                    buf[start + i] = dimColor(c[0], f)
                }
            }

            16 -> for (i in 0 until len) buf[start + i] =   // gradient
                if (seg.pal == 0) mix(c[0], c[1], i.toFloat() / max(1, len - 1))
                else palColor(seg.pal, i.toFloat() / max(1, len - 1), c)

            17 -> {  // fire 2012
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
                    buf[start + i] = palColor(if (seg.pal == 0) 6 else seg.pal, heat[i] / 255f, c)
                }
            }

            18 -> {  // meteor: head, exponential tail over c1
                fill(buf, start, stop, c[1])
                val tail = 2 + seg.ix * len / 255 / 2
                val head = (t * len * 0.8f).toInt() % (len * 2)   // shoot out and back dark
                for (i in 0 until len) {
                    val d = head - i
                    if (d in 0..tail) buf[start + i] = mix(c[1], pcol(seg, i.toFloat() / len), 1f - d.toFloat() / (tail + 1))
                }
            }

            19 -> for (i in 0 until len) buf[start + i] =   // palette: show palette as gradient
                palColor(seg.pal, i.toFloat() / max(1, len - 1), c)

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
