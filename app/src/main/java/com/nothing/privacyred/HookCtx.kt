package com.nothing.privacyred

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build

/** System context inside system_server via ActivityThread. */
object HookCtx {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun systemContext(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getDeclaredMethod("currentActivityThread").invoke(null)
            val m = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            m.invoke(current) as Context
        } catch (_: Throwable) {
            try {
                val at = Class.forName("android.app.ActivityThread")
                val f = at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }
                val current = f.get(null) ?: return null
                val m = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
                m.invoke(current) as Context
            } catch (_: Throwable) {
                null
            }
        }
    }
}
