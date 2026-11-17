package com.example.prodvxled

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private class Preset(val name: String, val swatch: Int, val colors: () -> IntArray)

    private fun solid(c: Int) = { IntArray(LedController.LED_COUNT) { c } }

    private fun sides(c: Int, vararg names: String) = {
        val arr = IntArray(LedController.LED_COUNT)
        names.forEach { n -> LedController.SIDES[n]?.forEach { arr[it] = c } }
        arr
    }

    // Edit this list to change the available presets.
    private val presets = listOf(
        Preset("Off", 0xFF303030.toInt(), solid(0)),
        Preset("White", 0xFFFFFFFF.toInt(), solid(0xFFFFFF)),
        Preset("Warm white", 0xFFFFB46B.toInt(), solid(0xFFB46B)),
        Preset("Red", 0xFFFF0000.toInt(), solid(0xFF0000)),
        Preset("Green", 0xFF00C000.toInt(), solid(0x00FF00)),
        Preset("Blue", 0xFF2040FF.toInt(), solid(0x0000FF)),
        Preset("Amber", 0xFFFF8000.toInt(), solid(0xFF8000)),
        Preset("Purple", 0xFF9030FF.toInt(), solid(0x8000FF)),
        Preset("Sides white", 0xFFB0B0B0.toInt(), sides(0xFFFFFF, "left", "right")),
        Preset("Top/bottom blue", 0xFF2060C0.toInt(), sides(0x0000FF, "top", "bottom")),
        Preset("Rainbow", 0xFFD040A0.toInt(), {
            IntArray(LedController.LED_COUNT) {
                Color.HSVToColor(floatArrayOf(it * 360f / LedController.LED_COUNT, 1f, 1f))
            }
        }),
        Preset("Night light", 0xFF503010.toInt(), solid(0x502800)),
    )

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var levelLabel: TextView
    private val buttons = mutableListOf<Button>()
    private var selected = -1
    private var level = 11
    private var useRoot = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("led", MODE_PRIVATE)
        selected = prefs.getInt("preset", -1).takeIf { it in presets.indices } ?: -1
        level = prefs.getInt("level", 11).coerceIn(1, 15)
        useRoot = prefs.getBoolean("root", true)
        setContentView(buildUi())
        refreshButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "LED presets"
            textSize = 24f
        })

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presets.chunked(2).forEachIndexed { rowIdx, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { colIdx, p ->
                val index = rowIdx * 2 + colIdx
                val b = Button(this).apply {
                    setBackgroundColor(p.swatch)
                    setTextColor(if (isLight(p.swatch)) Color.BLACK else Color.WHITE)
                    textSize = 18f
                    isAllCaps = false
                    setOnClickListener { apply(index) }
                }
                buttons += b
                line.addView(b, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    setMargins(dp(6), dp(6), dp(6), dp(6))
                })
            }
            grid.addView(line)
        }
        root.addView(grid)

        levelLabel = TextView(this).apply { textSize = 16f; setPadding(0, dp(16), 0, 0) }
        root.addView(levelLabel)
        updateLevelLabel()
        root.addView(SeekBar(this).apply {
            max = 14
            progress = level - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    level = p + 1
                    updateLevelLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    prefs.edit().putInt("level", level).apply()
                    if (selected >= 0) apply(selected)
                }
            })
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Switch(this).apply {
            text = "Use root (su) to write to /dev/ttyS1"
            isChecked = useRoot
            setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                useRoot = checked
                prefs.edit().putBoolean("root", checked).apply()
            }
        })

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.START
            text = "Ready"
        }
        root.addView(status)

        return ScrollView(this).apply { addView(root) }
    }

    private fun isLight(c: Int): Boolean {
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return lum > 0.6
    }

    private fun updateLevelLabel() {
        levelLabel.text = "Brightness: $level / 15"
    }

    private fun refreshButtons() {
        buttons.forEachIndexed { i, b ->
            b.text = if (i == selected) "\u2713 ${presets[i].name}" else presets[i].name
        }
    }

    private fun apply(index: Int) {
        selected = index
        prefs.edit().putInt("preset", index).apply()
        refreshButtons()
        val frame = LedController.buildFrame(presets[index].colors(), level)
        val root = useRoot
        val dir = cacheDir
        status.text = "Sending \u201c${presets[index].name}\u201d\u2026"
        io.execute {
            val err = LedController.send(dir, frame, root)
            runOnUiThread {
                status.text = err?.let { "Error: $it" } ?: "Applied \u201c${presets[index].name}\u201d"
            }
        }
    }
}
