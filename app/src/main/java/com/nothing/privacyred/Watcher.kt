package com.nothing.privacyred

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Precise hardware-state watcher running in system_server.
 * AppOps start/finish is unreliable on Nothing OS (camera only calls noteOperation),
 * so watch the HALs directly: any camera unavailable = cam active,
 * any live recording config = mic active.
 */
class Watcher(
    private val ctx: Context,
    private val controller: RedLightController,
    private val handler: Handler,
) {
    private val busyCams = ConcurrentHashMap.newKeySet<String>()

    fun register() {
        try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            if (cm == null) {
                XposedBridge.log("PrivacyRed: CameraManager null")
            } else {
                cm.registerAvailabilityCallback(
                    object : CameraManager.AvailabilityCallback() {
                        override fun onCameraAvailable(cameraId: String) {
                            busyCams.remove(cameraId)
                            controller.setCamActive(busyCams.isNotEmpty())
                        }

                        override fun onCameraUnavailable(cameraId: String) {
                            busyCams.add(cameraId)
                            controller.setCamActive(true)
                        }
                    },
                    handler,
                )
                XposedBridge.log("PrivacyRed: camera watcher registered")
            }
        } catch (e: Throwable) {
            XposedBridge.log("PrivacyRed: camera watcher failed: $e")
        }
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            if (am == null) {
                XposedBridge.log("PrivacyRed: AudioManager null")
            } else {
                am.registerAudioRecordingCallback(
                    object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(
                            configs: List<AudioRecordingConfiguration>,
                        ) {
                            controller.setMicActive(configs.isNotEmpty())
                        }
                    },
                    handler,
                )
                XposedBridge.log("PrivacyRed: mic watcher registered")
            }
        } catch (e: Throwable) {
            XposedBridge.log("PrivacyRed: mic watcher failed: $e")
        }
    }
}
