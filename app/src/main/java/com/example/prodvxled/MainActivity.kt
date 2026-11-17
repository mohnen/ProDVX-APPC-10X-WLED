package com.example.prodvxled

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    // Edit the shared list in Presets.kt to change the available presets.
    private val presets = Presets.list

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var levelLabel: TextView
    private val buttons = mutableListOf<Button>()
    private var selected = -1
    private var useRoot = true
    private val handler = Handler(Looper.getMainLooper())

    private val statusPoll = object : Runnable {
        override fun run() {
            if (::status.isInitialized) status.text = WledState.statusText
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("led", MODE_PRIVATE)
        selected = prefs.getInt("preset", -1).takeIf { it in presets.indices } ?: -1
        WledState.bri = prefs.getInt("level", 11).coerceIn(1, 15) * 17
        useRoot = prefs.getBoolean("root", true)
        WledState.useRoot = useRoot
        setContentView(buildUi())
        refreshButtons()

        if (prefs.getBoolean("server", true)) WledService.start(this)
        handler.post(statusPoll)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusPoll)
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
            progress = WledState.bri / 17 - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { WledState.bri = (p + 1) * 17; WledState.touch() }
                    updateLevelLabel()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    prefs.edit().putInt("level", s.progress + 1).apply()
                }
            })
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Switch(this).apply {
            text = "WLED API server (HTTP :80, UDP :21324, mDNS)"
            isChecked = prefs.getBoolean("server", true)
            setPadding(0, dp(16), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("server", checked).apply()
                if (checked) WledService.start(this@MainActivity) else WledService.stop(this@MainActivity)
            }
        })

        root.addView(Switch(this).apply {
            text = "Use root (su) to write to /dev/ttyS1"
            isChecked = useRoot
            setPadding(0, dp(4), 0, dp(8))
            setOnCheckedChangeListener { _, checked ->
                useRoot = checked
                WledState.useRoot = checked
                prefs.edit().putBoolean("root", checked).apply()
            }
        })

        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.START
            text = WledState.statusText
        }
        root.addView(status)

        return ScrollView(this).apply { addView(root) }
    }

    private fun isLight(c: Int): Boolean {
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        return lum > 0.6
    }

    private fun updateLevelLabel() {
        levelLabel.text = "Brightness: ${WledState.bri / 17} / 15"
    }

    private fun refreshButtons() {
        buttons.forEachIndexed { i, b ->
            b.text = if (i == selected) "✓ ${presets[i].name}" else presets[i].name
        }
    }

    private fun apply(index: Int) {
        selected = index
        prefs.edit().putInt("preset", index).apply()
        refreshButtons()
        WledState.setCustom(presets[index].colors(), index + 1)
        status.text = "Applied “${presets[index].name}”"
    }
}
