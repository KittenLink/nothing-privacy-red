package com.nothing.privacyred

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs inside system_server. Controls Nothing Phone 2 tally light (id 105) like the
 * stock camera does: vendor ILightsExtension.setLightExtensionState(105, state, brightness).
 * The tally LED is on/off only (no smooth brightness), so only blink is supported.
 * Priority: camera > mic. Config is pushed live from the panel via broadcast.
 */
class RedLightController(
    private val appContext: Context,
    private val extHolder: AtomicReference<HookEntry.ExtTarget?>,
) {
    companion object {
        const val TAG = "PrivacyRed"
        const val TALLY_ID = 105
        const val OP_CAMERA = 26
        const val OP_RECORD_AUDIO = 27
    }

    data class Cfg(
        var micEnabled: Boolean = true,
        var camEnabled: Boolean = true,
        var micBlinkMs: Int = 500,
        var camBlinkMs: Int = 500,
        var brightness: Int = 255,
    )

    private val cfg = Cfg()
    private val handler = Handler(Looper.getMainLooper())

    private var camCount = 0
    private var micCount = 0
    private var camWatch = false
    private var micWatch = false

    private var activeKey: String? = null // null = off, else "cam" | "mic" | "test"
    private var blinkOn = false
    private var lastToggle = 0L
    private var testEndAt = 0L

    private val loop = object : Runnable {
        override fun run() {
            tick()
            if (activeKey != null) handler.postDelayed(this, 50)
        }
    }

    private fun diag(msg: String) {
        try { Log.i(TAG, msg) } catch (_: Throwable) {}
        try { XposedBridge.log("PrivacyRed: $msg") } catch (_: Throwable) {}
    }

    private fun heartbeat(state: String) {
        try {
            Settings.Global.putString(
                appContext.contentResolver, Action.STATE_KEY,
                "$state|${SystemClock.uptimeMillis()}"
            )
        } catch (_: Throwable) {}
    }

    /** Live config push from panel. Takes effect immediately, even mid-blink. */
    @Synchronized
    fun applyConfig(
        micEnabled: Boolean, camEnabled: Boolean,
        micBlinkMs: Int, camBlinkMs: Int, brightness: Int,
    ) {
        cfg.micEnabled = micEnabled
        cfg.camEnabled = camEnabled
        cfg.micBlinkMs = micBlinkMs.coerceIn(100, 2000)
        cfg.camBlinkMs = camBlinkMs.coerceIn(100, 2000)
        cfg.brightness = brightness.coerceIn(51, 255)
        diag(
            "config micEn=$micEnabled camEn=$camEnabled " +
                "micMs=${cfg.micBlinkMs} camMs=${cfg.camBlinkMs} bright=${cfg.brightness}"
        )
        reevaluate()
    }

    /** Manual test from panel, bypasses AppOps. Auto-stops after 4s. */
    @Synchronized
    fun test() {
        testEndAt = SystemClock.uptimeMillis() + 4000
        start("test")
        diag("test blink for 4s")
    }

    @Synchronized
    fun testOff() {
        testEndAt = 0L
        stop()
        diag("test off")
    }

    @Synchronized
    fun onOpStart(op: Int) {
        if (op == OP_CAMERA) camCount++
        else if (op == OP_RECORD_AUDIO) micCount++
        else return
        diag("opStart $op cam=$camCount mic=$micCount")
        reevaluate()
    }

    @Synchronized
    fun onOpFinish(op: Int) {
        if (op == OP_CAMERA) camCount = (camCount - 1).coerceAtLeast(0)
        else if (op == OP_RECORD_AUDIO) micCount = (micCount - 1).coerceAtLeast(0)
        else return
        diag("opFinish $op cam=$camCount mic=$micCount")
        reevaluate()
    }

    @Synchronized
    fun setCamActive(active: Boolean) {
        if (camWatch == active) return
        camWatch = active
        diag("camWatch=$active")
        reevaluate()
    }

    @Synchronized
    fun setMicActive(active: Boolean) {
        if (micWatch == active) return
        micWatch = active
        diag("micWatch=$active")
        reevaluate()
    }

    @Synchronized
    private fun reevaluate() {
        if (activeKey == "test") return // manual test holds the light
        val camOn = (camWatch || camCount > 0) && cfg.camEnabled
        val micOn = (micWatch || micCount > 0) && cfg.micEnabled
        val want: String? = when {
            camOn -> "cam"
            micOn -> "mic"
            else -> null
        }
        diag("reevaluate cam=$camCount/$camWatch mic=$micCount/$micWatch want=$want")
        if (want == null) {
            stop()
            return
        }
        if (want != activeKey) start(want)
    }

    private fun start(key: String) {
        activeKey = key
        blinkOn = false
        lastToggle = 0L
        handler.removeCallbacks(loop)
        handler.post(loop)
        heartbeat(key)
        diag("start $key")
    }

    private fun stop() {
        activeKey = null
        testEndAt = 0L
        handler.removeCallbacks(loop)
        setHardware(0, 0)
        heartbeat("off")
        diag("stop")
    }

    private fun tick() {
        if (testEndAt > 0 && SystemClock.uptimeMillis() >= testEndAt) {
            stop()
            return
        }
        val key = activeKey ?: return
        val peak = cfg.brightness * 16 // 816..4080 on the 0..4095 driver scale
        try {
            val interval = when (key) {
                "cam" -> cfg.camBlinkMs
                "mic" -> cfg.micBlinkMs
                else -> 500
            }.coerceIn(100, 2000)
            val now = SystemClock.uptimeMillis()
            if (now - lastToggle >= interval) {
                lastToggle = now
                blinkOn = !blinkOn
                setHardware(if (blinkOn) peak else 0, if (blinkOn) 1 else 0)
            }
        } catch (e: Exception) {
            diag("tick failed: $e")
        }
    }

    private fun setHardware(brightness4095: Int, state: Int) {
        val t = extHolder.get()
        if (t == null) {
            diag("ext not captured yet")
            return
        }
        try {
            val ext = t.field.get(t.instance)
            if (ext == null) {
                diag("ext field null (HAL not ready)")
                return
            }
            t.method.invoke(ext, TALLY_ID, state.toLong(), brightness4095)
        } catch (e: Exception) {
            diag("setLightExtensionState failed: $e")
        }
    }
}
