package com.nothing.privacyred

object Prefs {
    const val FILE = "red_prefs"

    const val MIC_ENABLED = "mic_enabled"
    const val CAM_ENABLED = "cam_enabled"

    const val MIC_BLINK_MS = "mic_blink_ms" // on==off interval
    const val CAM_BLINK_MS = "cam_blink_ms"

    const val BRIGHTNESS = "brightness" // 51..255 peak red channel

    const val DEF_BLINK_MS = 500
    const val DEF_BRIGHTNESS = 255
}
