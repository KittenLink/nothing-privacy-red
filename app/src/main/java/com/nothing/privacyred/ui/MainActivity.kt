package com.nothing.privacyred.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.nothing.privacyred.Action
import com.nothing.privacyred.Prefs
import com.nothing.privacyred.R

class MainActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTick = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

        findViewById<Button>(R.id.btnTestBlink).setOnClickListener {
            sendBroadcast(Intent(Action.TEST_BLINK))
        }
        findViewById<Button>(R.id.btnTestOff).setOnClickListener {
            sendBroadcast(Intent(Action.TEST_OFF))
        }

        bindSwitch(R.id.swCam, Prefs.CAM_ENABLED, true)
        bindSwitch(R.id.swMic, Prefs.MIC_ENABLED, true)

        bindNumSeek(
            R.id.sbCamBlink, R.id.tvCamBlink, R.id.etCamBlink, "闪烁间隔",
            Prefs.CAM_BLINK_MS, Prefs.DEF_BLINK_MS, 100, 2000, "ms"
        )
        bindNumSeek(
            R.id.sbMicBlink, R.id.tvMicBlink, R.id.etMicBlink, "闪烁间隔",
            Prefs.MIC_BLINK_MS, Prefs.DEF_BLINK_MS, 100, 2000, "ms"
        )
        bindNumSeek(
            R.id.sbBright, R.id.tvBright, R.id.etBright, "最高亮度",
            Prefs.BRIGHTNESS, Prefs.DEF_BRIGHTNESS, 51, 255, ""
        )
    }

    override fun onResume() {
        super.onResume()
        pushConfig()
        uiHandler.post(statusTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(statusTick)
    }

    /** Push full config to the hook live (broadcast, no file reads on hook side). */
    private fun pushConfig() {
        sendBroadcast(
            Intent(Action.CONFIG)
                .putExtra(Action.EX_MIC_ENABLED, sp.getBoolean(Prefs.MIC_ENABLED, true))
                .putExtra(Action.EX_CAM_ENABLED, sp.getBoolean(Prefs.CAM_ENABLED, true))
                .putExtra(Action.EX_MIC_BLINK_MS, sp.getInt(Prefs.MIC_BLINK_MS, Prefs.DEF_BLINK_MS))
                .putExtra(Action.EX_CAM_BLINK_MS, sp.getInt(Prefs.CAM_BLINK_MS, Prefs.DEF_BLINK_MS))
                .putExtra(Action.EX_BRIGHTNESS, sp.getInt(Prefs.BRIGHTNESS, Prefs.DEF_BRIGHTNESS))
        )
    }

    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.tvStatus) ?: return
        val raw = try {
            Settings.Global.getString(contentResolver, Action.STATE_KEY)
        } catch (_: Exception) {
            null
        }
        tv.text = if (raw.isNullOrEmpty()) {
            "状态：hook 未上报（模块未加载/未重启？点测试按钮试灯）"
        } else {
            val parts = raw.split("|")
            val age = try {
                (android.os.SystemClock.uptimeMillis() - parts[1].toLong()) / 1000
            } catch (_: Exception) {
                -1
            }
            "状态：${parts[0]}（${age}秒前上报）"
        }
    }

    private fun bindSwitch(id: Int, key: String, def: Boolean) {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = sp.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean(key, v).apply()
            pushConfig()
        }
    }

    /**
     * SeekBar + numeric input synced both ways. Typing commits on
     * IME-Done or focus loss (clamped to range); dragging updates live.
     */
    private fun bindNumSeek(
        barId: Int, labelId: Int, inputId: Int, label: String,
        key: String, def: Int, min: Int, max: Int, unit: String,
    ) {
        val bar = findViewById<SeekBar>(barId)
        val tv = findViewById<TextView>(labelId)
        val input = findViewById<EditText>(inputId)

        fun render(v: Int) {
            tv.text = if (unit.isEmpty()) label else "$label：${v}$unit"
            if (!input.isFocused && input.text.toString() != v.toString()) {
                input.setText(v.toString())
            }
        }
        fun commit(v: Int, fromUser: Boolean) {
            val c = v.coerceIn(min, max)
            render(c)
            bar.progress = c - min
            if (fromUser) {
                sp.edit().putInt(key, c).apply()
                pushConfig()
            }
        }

        bar.max = max - min
        val cur = sp.getInt(key, def).coerceIn(min, max)
        bar.progress = cur - min
        render(cur)
        input.setText(cur.toString())

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) commit(p + min, true)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        input.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit(v.text.toString().toIntOrNull() ?: cur, true)
                hideKeyboard(v)
                v.clearFocus()
                true
            } else {
                false
            }
        }
        input.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val et = v as EditText
                commit(et.text.toString().toIntOrNull() ?: sp.getInt(key, def), true)
            }
        }
    }

    private fun hideKeyboard(v: android.view.View) {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Exception) {}
    }
}
