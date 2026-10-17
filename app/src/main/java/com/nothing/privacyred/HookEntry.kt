package com.nothing.privacyred

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HookEntry : IXposedHookLoadPackage {

    private val extHolder = AtomicReference<ExtTarget?>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only system_server hosts AppOpsService + NtGlyphServiceImpl.
        if (lpparam.packageName != "android") return
        try {
            val appOpsCls = Class.forName(
                "com.android.server.appop.AppOpsService", false, lpparam.classLoader
            )
            val prefs = XSharedPreferences("com.nothing.privacyred", Prefs.FILE)
            try {
                prefs.makeWorldReadable()
            } catch (e: Throwable) {
                XposedBridge.log("PrivacyRed: makeWorldReadable failed: $e")
            }

            val state = SharedState(prefs, extHolder)

            val startCb = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        if (p.args.isEmpty() || p.args[0] !is Int) return
                        val op = p.args[0] as Int
                        // Diag: log every long-running op start (these are rare).
                        XposedBridge.log("PrivacyRed: START op=$op via ${p.method.name}")
                        if (op != RedLightController.OP_CAMERA &&
                            op != RedLightController.OP_RECORD_AUDIO
                        ) return
                        val sysCtx = HookCtx.systemContext()
                        if (sysCtx == null) {
                            XposedBridge.log("PrivacyRed: systemContext NULL, skip")
                            return
                        }
                        state.controller(sysCtx).onOpStart(op)
                    } catch (e: Throwable) {
                        XposedBridge.log("PrivacyRed: start hook failed: $e")
                    }
                }
            }
            val finishCb = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        if (p.args.isEmpty() || p.args[0] !is Int) return
                        val op = p.args[0] as Int
                        XposedBridge.log("PrivacyRed: FINISH op=$op via ${p.method.name}")
                        if (op != RedLightController.OP_CAMERA &&
                            op != RedLightController.OP_RECORD_AUDIO
                        ) return
                        val sysCtx = HookCtx.systemContext()
                        if (sysCtx == null) {
                            XposedBridge.log("PrivacyRed: systemContext NULL, skip")
                            return
                        }
                        state.controller(sysCtx).onOpFinish(op)
                    } catch (e: Throwable) {
                        XposedBridge.log("PrivacyRed: finish hook failed: $e")
                    }
                }
            }
            // Diag: noteOperation is a one-shot check; log cam/mic only to avoid spam.
            val noteCb = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try {
                        if (p.args.isEmpty() || p.args[0] !is Int) return
                        val op = p.args[0] as Int
                        if (op != RedLightController.OP_CAMERA &&
                            op != RedLightController.OP_RECORD_AUDIO
                        ) return
                        XposedBridge.log("PrivacyRed: NOTE op=$op via ${p.method.name}")
                    } catch (e: Throwable) {
                        XposedBridge.log("PrivacyRed: note hook failed: $e")
                    }
                }
            }

            // NOTE: noteOperation is only a permission *check*, not active use.
            for (name in arrayOf("startOperation", "startOperationNoThrow", "noteOperation")) {
                try {
                    XposedBridge.hookAllMethods(appOpsCls, name, if (name == "noteOperation") noteCb else startCb)
                } catch (e: Throwable) {
                    XposedBridge.log("PrivacyRed: hook $name failed: $e")
                }
            }
            try {
                XposedBridge.hookAllMethods(appOpsCls, "finishOperation", finishCb)
            } catch (e: Throwable) {
                XposedBridge.log("PrivacyRed: hook finishOperation failed: $e")
            }
            XposedBridge.log("PrivacyRed: hooked AppOpsService")

            // Capture Nothing's glyph service + its vendor HAL handle so we can
            // drive the tally LED (id 105) exactly like the stock camera does:
            // ILightsExtension.setLightExtensionState(105, brightness, state).
            try {
                val ntCls = Class.forName(
                    "com.android.server.lights.NtGlyphServiceImpl",
                    false, lpparam.classLoader
                )
                XposedBridge.hookAllConstructors(ntCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            captureExt(p.thisObject)
                        } catch (e: Throwable) {
                            XposedBridge.log("PrivacyRed: captureExt failed: $e")
                        }
                    }
                })
                XposedBridge.log("PrivacyRed: hooked NtGlyphServiceImpl ctor")
            } catch (e: Throwable) {
                XposedBridge.log("PrivacyRed: hook NtGlyphServiceImpl failed: $e")
            }
            snoopExtCalls(lpparam.classLoader)

            // Manual test channel registered once the system is ready.
            val ready = AtomicBoolean(false)
            fun registerTestReceiver() {
                if (!ready.compareAndSet(false, true)) return
                try {
                    val sysCtx = HookCtx.systemContext()
                    if (sysCtx == null) {
                        ready.set(false)
                        XposedBridge.log("PrivacyRed: systemContext NULL for receiver")
                        return
                    }
                    val recv = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            try {
                                when (intent.action) {
                                    Action.TEST_BLINK -> state.controller(ctx).test()
                                    Action.TEST_OFF -> state.controller(ctx).testOff()
                                    Action.CONFIG -> state.controller(ctx).applyConfig(
                                        intent.getBooleanExtra(Action.EX_MIC_ENABLED, true),
                                        intent.getBooleanExtra(Action.EX_CAM_ENABLED, true),
                                        intent.getIntExtra(Action.EX_MIC_BLINK_MS, 500),
                                        intent.getIntExtra(Action.EX_CAM_BLINK_MS, 500),
                                        intent.getIntExtra(Action.EX_BRIGHTNESS, 255),
                                    )
                                }
                            } catch (e: Throwable) {
                                XposedBridge.log("PrivacyRed: test recv failed: $e")
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction(Action.TEST_BLINK)
                        addAction(Action.TEST_OFF)
                        addAction(Action.CONFIG)
                    }
                    sysCtx.registerReceiver(recv, filter, Context.RECEIVER_EXPORTED)
                    XposedBridge.log("PrivacyRed: test receiver registered")
                } catch (e: Throwable) {
                    ready.set(false)
                    XposedBridge.log("PrivacyRed: test receiver failed: $e")
                }
            }
            try {
                val amsCls = Class.forName(
                    "com.android.server.am.ActivityManagerService", false, lpparam.classLoader
                )
                XposedBridge.hookAllMethods(amsCls, "systemReady", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        XposedBridge.log("PrivacyRed: systemReady, registering receiver")
                        registerTestReceiver()
                        try {
                            val sysCtx = HookCtx.systemContext()
                            if (sysCtx == null) {
                                XposedBridge.log("PrivacyRed: systemContext NULL for watcher")
                            } else {
                                val h = android.os.Handler(android.os.Looper.getMainLooper())
                                Watcher(sysCtx, state.controller(sysCtx), h).register()
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("PrivacyRed: watcher init failed: $e")
                        }
                    }
                })
                XposedBridge.log("PrivacyRed: hooked systemReady")
            } catch (e: Throwable) {
                XposedBridge.log("PrivacyRed: hook systemReady failed: $e")
            }
        } catch (e: Throwable) {
            XposedBridge.log("PrivacyRed init failed: $e")
        }
    }

    private fun captureExt(instance: Any?) {
        if (instance == null) return
        var field: Field? = null
        var target: Any? = null
        for (f in instance.javaClass.declaredFields) {
            if (f.type.name == "vendor.hardware.light.ILightsExtension") {
                f.isAccessible = true
                field = f
                try {
                    target = f.get(instance)
                } catch (_: Throwable) {}
                break
            }
        }
        if (field == null) {
            XposedBridge.log("PrivacyRed: ILightsExtension field not found")
            return
        }
        var method: Method? = null
        try {
            method = field.type.getMethod(
                "setLightExtensionState",
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
            XposedBridge.log("PrivacyRed: setLightExtensionState lookup failed: $e")
            return
        }
        extHolder.set(ExtTarget(instance, field, method))
        XposedBridge.log("PrivacyRed: captured ILightsExtension (bound=${target != null})")
    }

    /** Class-wide snoop: logs every stock setLightExtensionState(id, val, state) call. */
    private fun snoopExtCalls(loader: ClassLoader) {
        val candidates = arrayOf(
            "vendor.hardware.light.ILightsExtension\$Stub\$Proxy",
            "vendor.hardware.light.ILightsExtension\$Stub",
        )
        for (name in candidates) {
            try {
                val cls = Class.forName(name, false, loader)
                XposedBridge.hookAllMethods(cls, "setLightExtensionState", object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        try {
                            // Skip our own calls (same process, but ours come from RedLightController).
                            val st = Thread.currentThread().stackTrace
                            var ours = false
                            for (e in st) {
                                if (e.className.contains("RedLightController")) {
                                    ours = true
                                    break
                                }
                            }
                            if (!ours) {
                                XposedBridge.log(
                                    "PrivacyRed: STOCK setLightExtensionState(" +
                                        "${p.args[0]}, ${p.args[1]}, ${p.args[2]})"
                                )
                            }
                        } catch (_: Throwable) {}
                    }
                })
                XposedBridge.log("PrivacyRed: snooping $name")
                return
            } catch (e: Throwable) {
                XposedBridge.log("PrivacyRed: snoop $name unavailable: $e")
            }
        }
    }

    data class ExtTarget(val instance: Any, val field: Field, val method: Method)

    private class SharedState(
        val prefs: XSharedPreferences,
        val extHolder: AtomicReference<ExtTarget?>,
    ) {
        var controller: RedLightController? = null
        fun controller(ctx: Context): RedLightController {
            return controller ?: RedLightController(
                ctx.applicationContext,
                extHolder,
            ).also { controller = it }
        }
    }
}
