package com.nothing.privacyred

/** Broadcast actions for panel -> system_server hook. */
object Action {
    const val TEST_BLINK = "com.nothing.privacyred.TEST_BLINK"
    const val TEST_OFF = "com.nothing.privacyred.TEST_OFF"
    const val CONFIG = "com.nothing.privacyred.CONFIG"

    const val EX_MIC_ENABLED = "mic_enabled"
    const val EX_CAM_ENABLED = "cam_enabled"
    const val EX_MIC_BLINK_MS = "mic_blink_ms"
    const val EX_CAM_BLINK_MS = "cam_blink_ms"
    const val EX_BRIGHTNESS = "brightness"

    /** Heartbeat key in Settings.Global written by hook, read by panel. */
    const val STATE_KEY = "privacyred_state"
}
