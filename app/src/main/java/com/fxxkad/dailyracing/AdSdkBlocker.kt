package com.fxxkad.dailyracing

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Directly neutralizes the cj.mobile (江湖聚合 / CJ aggregation) ad SDK that the
 * target Flutter app uses for every ad type. All ads flow through the public
 * `cj.mobile.CJ*` classes whose `loadAd(...)` methods take (or hold) a listener
 * from the `cj.mobile.listener` package that always exposes onError(String, String).
 *
 * Strategy: hook every `loadAd`, fire the listener's onError on the main thread so
 * the Flutter plugin treats it as a no-fill, and skip the original method so no ad
 * network request is ever made. `show*` methods are also no-oped as defense in depth.
 * SDK init is intentionally left untouched to avoid initialization crashes.
 */
object AdSdkBlocker {

    private const val TAG = "[DailyRacingBlocker]"
    private const val LISTENER_PACKAGE_PREFIX = "cj.mobile.listener."

    private val mainHandler = Handler(Looper.getMainLooper())

    // CJ ad classes that expose loadAd(...).
    private val LOAD_AD_CLASSES = listOf(
        "cj.mobile.CJSplash",
        "cj.mobile.CJInterstitial",
        "cj.mobile.CJFullScreenVideo",
        "cj.mobile.CJRewardVideo",
        "cj.mobile.CJBanner",
        "cj.mobile.CJNativeExpress",
        "cj.mobile.CJRenderNative",
        "cj.mobile.CJVideoFlow",
        "cj.mobile.CJVideoContent",
        "cj.mobile.CJShortVideo"
    )

    // CJ ad classes whose show* methods should be no-oped as a second line of defense.
    private val SHOW_AD_CLASSES = listOf(
        "cj.mobile.CJSplash",
        "cj.mobile.CJInterstitial",
        "cj.mobile.CJFullScreenVideo",
        "cj.mobile.CJRewardVideo",
        "cj.mobile.CJBanner"
    )

    fun install(classLoader: ClassLoader) {
        hookLoadAd(classLoader)
        hookShowAd(classLoader)
    }

    private fun hookLoadAd(classLoader: ClassLoader) {
        for (className in LOAD_AD_CLASSES) {
            try {
                val cls = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
                val count = XposedBridge.hookAllMethods(cls, "loadAd", loadAdHook)
                XposedBridge.log("$TAG neutralized $count loadAd on $className")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG failed to hook loadAd on $className: ${t.message}")
            }
        }
    }

    private fun hookShowAd(classLoader: ClassLoader) {
        for (className in SHOW_AD_CLASSES) {
            try {
                val cls = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
                XposedBridge.hookAllMethods(cls, "showAd", showAdHook)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG failed to hook showAd on $className: ${t.message}")
            }
        }
    }

    private val loadAdHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val listener = findListenerInArgs(param.args) ?: findListenerInFields(param.thisObject)
                if (listener != null) {
                    fireOnError(listener)
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG loadAd neutralize error: ${t.message}")
            } finally {
                // Always skip the original loadAd so no ad request is made.
                param.result = null
            }
        }
    }

    private val showAdHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            param.result = null
        }
    }

    /** Returns the first argument that implements a cj.mobile.listener.* interface. */
    private fun findListenerInArgs(args: Array<Any?>?): Any? {
        if (args == null) return null
        for (arg in args) {
            if (arg != null && isCjListener(arg)) return arg
        }
        return null
    }

    /** Reflectively scans instance fields for a cj.mobile.listener.* value (e.g. CJRewardVideo). */
    private fun findListenerInFields(target: Any?): Any? {
        if (target == null) return null
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                try {
                    field.isAccessible = true
                    val value = field.get(target) ?: continue
                    if (isCjListener(value)) return value
                } catch (_: Throwable) {
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun isCjListener(obj: Any): Boolean {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (itf in cls.interfaces) {
                if (itf.name.startsWith(LISTENER_PACKAGE_PREFIX)) return true
            }
            cls = cls.superclass
        }
        return false
    }

    private fun fireOnError(listener: Any) {
        val onError = findOnError(listener.javaClass) ?: return
        mainHandler.post {
            try {
                onError.isAccessible = true
                onError.invoke(listener, "-1", "blocked by DailyRacingBlocker")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG onError invoke failed: ${t.message}")
            }
        }
    }

    private fun findOnError(clazz: Class<*>): Method? {
        var cls: Class<*>? = clazz
        while (cls != null && cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                if (m.name == "onError" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1] == String::class.java
                ) {
                    return m
                }
            }
            cls = cls.superclass
        }
        return null
    }
}
