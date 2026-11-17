package com.example.prodvxled

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * Shared WLED-compatible state. The service render loop, the HTTP/UDP/WebSocket
 * frontends and the local preset buttons all go through this object.
 */
object WledState {
    const val LED_COUNT = LedController.LED_COUNT

    class Seg {
        @Volatile var start = 0
        @Volatile var stop = LED_COUNT
        // 3 color slots, packed 0xRRGGBB
        val col = intArrayOf(0xFFA000, 0x000000, 0x000000)
        @Volatile var fx = 0
        @Volatile var sx = 128
        @Volatile var ix = 128
        @Volatile var pal = 0
        @Volatile var on = true
        @Volatile var bri = 255
        @Volatile var tt = 7   // transition, 100ms units
    }

    @Volatile var on = true
    @Volatile var bri = 187          // 0..255 master brightness
    @Volatile var transition = 7     // 100ms units
    val seg = Seg()

    /** Manual per-LED pattern set by the local preset buttons. Null = use fx engine. */
    @Volatile var customLeds: IntArray? = null

    /** Realtime (UDP) data overrides everything until liveUntil passes. */
    @Volatile var liveLeds: IntArray? = null
    @Volatile var liveUntil = 0L

    /** Set by frontends to request a re-render. */
    @Volatile var dirty = true

    /** Human-readable status shown in the app UI. */
    @Volatile var statusText = "starting"

    @Volatile var useRoot = true

    val isLive get() = liveLeds != null && System.currentTimeMillis() < liveUntil

    /** Global brightness byte for the LED chip (1..15) plus a dim factor for the rest. */
    fun level(): Int = ceil(bri.coerceIn(0, 255) / 17f).toInt().coerceIn(1, 15)

    fun dim(): Float = if (bri <= 0) 0f else (bri / 255f) / (level() / 15f)

    fun touch() { dirty = true }

    /** Preset buttons: show a fixed per-LED pattern. */
    fun setCustom(colors: IntArray) {
        customLeds = colors.copyOf()
        seg.fx = 0
        on = true
        touch()
    }

    fun isAnimated(): Boolean =
        isLive || (on && customLeds == null && seg.on && Effects.isAnimated(seg.fx))

    // ---------- WLED JSON ----------

    fun segJson(): JSONObject = JSONObject()
        .put("id", 0)
        .put("start", seg.start)
        .put("stop", seg.stop)
        .put("len", seg.stop - seg.start)
        .put("grp", 1).put("spc", 0).put("of", 0)
        .put("on", seg.on)
        .put("bri", seg.bri)
        .put("col", JSONArray().apply {
            for (i in 0..2) put(JSONArray().apply {
                put((seg.col[i] shr 16) and 0xFF); put((seg.col[i] shr 8) and 0xFF); put(seg.col[i] and 0xFF)
            })
        })
        .put("fx", seg.fx).put("sx", seg.sx).put("ix", seg.ix).put("pal", seg.pal)
        .put("tt", transition)
        .put("sel", true).put("rev", false).put("mi", false)
        .put("cct", -1).put("set", 0).put("o1", false).put("o2", false).put("o3", false)

    fun stateJson(): JSONObject = JSONObject()
        .put("on", on)
        .put("bri", bri)
        .put("transition", transition)
        .put("ps", -1).put("pl", -1)
        .put("nl", JSONObject().put("on", false).put("dur", 60).put("mode", 1).put("tbri", 0).put("rem", -1))
        .put("udpn", JSONObject().put("send", false).put("recv", true).put("sgrp", 1).put("rgrp", 1))
        .put("lor", if (isLive) 1 else 0)
        .put("mainseg", 0)
        .put("seg", JSONArray().put(segJson()))

    fun infoJson(): JSONObject = JSONObject()
        .put("ver", "0.14.4").put("vid", 2412190)
        .put("leds", JSONObject()
            .put("count", LED_COUNT).put("pwr", 0).put("fps", 30)
            .put("maxpwr", 0).put("maxseg", 1).put("seglc", JSONArray().put(1))
            .put("lc", 1).put("rgbw", false).put("wv", false).put("cct", 0))
        .put("str", false).put("name", "WLED-ProDVX")
        .put("udpport", 21324).put("simplifiedui", false)
        .put("live", isLive).put("liveseg", -1).put("lm", "").put("lip", "")
        .put("ws", 0).put("fxcount", Effects.FX_COUNT).put("palcount", Effects.PAL_COUNT)
        .put("cpalcount", 0)
        .put("wifi", JSONObject().put("bssid", "").put("rssi", -50).put("signal", 100).put("channel", 1))
        .put("fs", JSONObject().put("u", 0).put("t", 0).put("pmt", 0))
        .put("ndc", 0).put("arch", "android").put("core", "prodvx").put("lwip", 0)
        .put("freeheap", Runtime.getRuntime().freeMemory())
        .put("uptime", (System.currentTimeMillis() - startedAt) / 1000)
        .put("opt", 0).put("brand", "WLED").put("product", "FOSS")
        .put("mac", "deadc0de0001")

    private val startedAt = System.currentTimeMillis()

    /** Apply a WLED /json/state body. Returns true if anything changed. */
    @Synchronized
    fun applyJson(o: JSONObject): Boolean {
        var changed = false
        if (o.has("on")) { on = o.getBoolean("on"); changed = true }
        if (o.has("bri")) { bri = o.getInt("bri").coerceIn(0, 255); changed = true }
        if (o.has("transition")) { transition = o.getInt("transition").coerceIn(0, 255) }
        // preset recall (id >= 1); local pattern presets, not segment-based
        if (o.has("ps")) {
            val id = o.getInt("ps")
            Presets.list.getOrNull(id - 1)?.let { setCustom(it.colors()); changed = true }
        }
        val segs = o.optJSONArray("seg")
        if (segs != null && segs.length() > 0) {
            // explicit segment config overrides the manual preset pattern
            customLeds = null
            val s = segs.getJSONObject(0)   // single segment supported
            if (s.has("start")) { seg.start = s.getInt("start").coerceIn(0, LED_COUNT); changed = true }
            if (s.has("stop")) { seg.stop = s.getInt("stop").coerceIn(seg.start, LED_COUNT); changed = true }
            if (s.has("on")) { seg.on = s.getBoolean("on"); changed = true }
            if (s.has("bri")) { seg.bri = s.getInt("bri").coerceIn(0, 255); changed = true }
            if (s.has("fx")) { seg.fx = s.getInt("fx").coerceIn(0, Effects.FX_COUNT - 1); changed = true }
            if (s.has("sx")) { seg.sx = s.getInt("sx").coerceIn(0, 255); changed = true }
            if (s.has("ix")) { seg.ix = s.getInt("ix").coerceIn(0, 255); changed = true }
            if (s.has("pal")) { seg.pal = s.getInt("pal").coerceIn(0, Effects.PAL_COUNT - 1); changed = true }
            if (s.has("tt")) { seg.tt = s.getInt("tt").coerceIn(0, 255); transition = seg.tt }
            s.optJSONArray("col")?.let { cols ->
                for (i in 0 until minOf(3, cols.length())) {
                    val c = cols.getJSONArray(i)
                    if (c.length() >= 3) {
                        seg.col[i] = Color.rgb(
                            c.getInt(0).coerceIn(0, 255),
                            c.getInt(1).coerceIn(0, 255),
                            c.getInt(2).coerceIn(0, 255))
                        changed = true
                    }
                }
            }
        }
        if (changed) touch()
        return changed
    }
}
