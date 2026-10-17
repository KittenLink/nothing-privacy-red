# PrivacyRed: keep Xposed entry + LightsManager reflective calls
-keep class com.nothing.privacyred.HookEntry { *; }
-keep class com.nothing.privacyred.RedLightController { *; }
-keep class com.nothing.privacyred.HookCtx { *; }
-dontwarn de.robv.android.xposed.**
