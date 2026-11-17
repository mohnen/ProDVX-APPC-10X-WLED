package net.mohnen.prodvxled

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Shared preset definitions used by the local UI buttons and the WLED API. */
object Presets {

    class Entry(val name: String, val swatch: Int, val colors: () -> IntArray)

    private fun solid(c: Int) = { IntArray(LedController.LED_COUNT) { c } }

    private fun sides(c: Int, vararg names: String) = {
        val arr = IntArray(LedController.LED_COUNT)
        names.forEach { n -> LedController.SIDES[n]?.forEach { arr[it] = c } }
        arr
    }

    // Edit this list to change the available presets.
    val list = listOf(
        Entry("Off", 0xFF303030.toInt(), solid(0)),
        Entry("White", 0xFFFFFFFF.toInt(), solid(0xFFFFFF)),
        Entry("Warm white", 0xFFFFB46B.toInt(), solid(0xFFB46B)),
        Entry("Red", 0xFFFF0000.toInt(), solid(0xFF0000)),
        Entry("Green", 0xFF00C000.toInt(), solid(0x00FF00)),
        Entry("Blue", 0xFF2040FF.toInt(), solid(0x0000FF)),
        Entry("Amber", 0xFFFF8000.toInt(), solid(0xFF8000)),
        Entry("Purple", 0xFF9030FF.toInt(), solid(0x8000FF)),
        Entry("Sides white", 0xFFB0B0B0.toInt(), sides(0xFFFFFF, "left", "right")),
        Entry("Top/bottom blue", 0xFF2060C0.toInt(), sides(0x0000FF, "top", "bottom")),
        Entry("Rainbow", 0xFFD040A0.toInt(), {
            IntArray(LedController.LED_COUNT) {
                Color.HSVToColor(floatArrayOf(it * 360f / LedController.LED_COUNT, 1f, 1f)) and 0xFFFFFF
            }
        }),
        Entry("Night light", 0xFF503010.toInt(), solid(0x502800)),
    )

    /** presets.json document: {"1":{...}, "2":{...}} - ids start at 1 (0 = boot state). */
    fun json(): JSONObject = JSONObject().apply {
        list.forEachIndexed { i, p ->
            put("${i + 1}", JSONObject()
                .put("n", p.name)
                .put("ql", true)
                .put("on", true)
                .put("bri", 187)
                .put("transition", 7)
                .put("seg", JSONArray().put(JSONObject()
                    .put("col", JSONArray().put(JSONArray().apply {
                        put((p.swatch shr 16) and 0xFF); put((p.swatch shr 8) and 0xFF); put(p.swatch and 0xFF)
                    }))
                    .put("fx", 0))))
        }
    }
}
