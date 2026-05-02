package com.fxxkad.dailyracing

import android.app.Application
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.net.InetAddress
import java.util.Collections

class HookEntry : IXposedHookLoadPackage {
    @Volatile
    private var targetContext: Context? = null
    private val pendingRecords = Collections.synchronizedList(mutableListOf<BlockEvent>())

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != BlockRules.targetPackage) return

        XposedBridge.log("[DailyRacingBlocker] enabled for ${lpparam.packageName}")
        hookApplicationAttach()
        hookInetAddress(lpparam.packageName)
        hookAndroidNameService(lpparam.packageName)
        hookShareIntent(lpparam.classLoader)
        hookWeChatSendReq(lpparam.classLoader)
    }

    private fun hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    targetContext = (param.args.firstOrNull() as? Context)?.applicationContext
                    XposedBridge.log("[DailyRacingBlocker] context attached")
                    flushPendingRecords()
                }
            }
        )
    }

    private fun hookInetAddress(packageName: String) {
        XposedHelpers.findAndHookMethod(
            InetAddress::class.java,
            "getByName",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = param.args.firstOrNull() as? String
                    if (!BlockRules.shouldBlock(host)) return
                    param.result = zeroInetAddress(host)
                    recordBlock(packageName, host, "InetAddress.getByName")
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            InetAddress::class.java,
            "getAllByName",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = param.args.firstOrNull() as? String
                    if (!BlockRules.shouldBlock(host)) return
                    param.result = arrayOf(zeroInetAddress(host))
                    recordBlock(packageName, host, "InetAddress.getAllByName")
                }
            }
        )
    }

    private fun hookAndroidNameService(packageName: String) {
        try {
            val inet6Class = XposedHelpers.findClassIfExists("java.net.Inet6AddressImpl", null) ?: return
            XposedBridge.hookAllMethods(inet6Class, "lookupAllHostAddr", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = param.args.firstOrNull() as? String
                    if (!BlockRules.shouldBlock(host)) return
                    param.result = arrayOf(zeroInetAddress(host))
                    recordBlock(packageName, host, "Inet6AddressImpl.lookupAllHostAddr")
                }
            })
            XposedBridge.log("[DailyRacingBlocker] hooked Inet6AddressImpl.lookupAllHostAddr")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Android name service: ${t.message}")
        }
    }

    private fun hookShareIntent(classLoader: ClassLoader) {
        val shareHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val intentIndex = param.args.indexOfFirst { it is Intent }
                    if (intentIndex == -1) return
                    val intent = param.args[intentIndex] as Intent

                    val action = intent.action
                    val dataString = intent.dataString ?: ""
                    val componentName = intent.component?.className ?: ""
                    val targetPackage = intent.`package` ?: intent.component?.packageName ?: ""

                    val isQQShare = targetPackage == "com.tencent.mobileqq" ||
                            dataString.startsWith("mqqapi://share/") ||
                            componentName.contains("com.tencent.connect.common.AssistActivity")

                    val isWeChatShare = targetPackage == "com.tencent.mm" ||
                            componentName.startsWith("com.tencent.mm.") ||
                            action == "com.tencent.mm.action.SEND" ||
                            intent.hasExtra("_wxapi_command_type") ||
                            // Catch Tencent Open SDK dispatching to WeChat (its bundled WeChat SDK
                            // may use different stub activity names or leave the intent implicit)
                            dataString.contains("wechat") ||
                            dataString.contains("weixin") ||
                            intent.extras?.keySet()?.any { key ->
                                key.contains("_wxapi_") || key.contains("_wxobject_")
                            } ?: false

                    // Log any intent that has extras but matches neither QQ nor WeChat,
                    // so we can diagnose missing detection patterns from Xposed logs.
                    if (!isQQShare && !isWeChatShare) {
                        if (intent.extras != null && intent.extras!!.keySet().isNotEmpty()) {
                            XposedBridge.log("[DailyRacingBlocker] SKIP intent w/ extras — " +
                                "pkg=${targetPackage} comp=${componentName} act=${action} " +
                                "data=${dataString.take(80)} keys=${intent.extras!!.keySet()}")
                        }
                        return
                    }

                    intent.extras?.classLoader = classLoader

                    // Check if the fix is enabled via the host app's ContentProvider
                    val context = resolveContext()
                    if (context != null) {
                        try {
                            val uri = android.net.Uri.parse("content://com.fxxkad.dailyracing.records/records")
                            val bundle = context.contentResolver.call(uri, "get_setting", "fix_share", null)
                            val isEnabled = bundle?.getBoolean("value", true) ?: true
                            if (!isEnabled) {
                                return // User disabled the share fix
                            }
                        } catch (_: Exception) {}
                    }

                    var urlToShare: String? = null

                    // Extract URL from standard ACTION_SEND
                    if (action == Intent.ACTION_SEND) {
                        urlToShare = intent.getStringExtra(Intent.EXTRA_TEXT)
                    }
                    // Extract URL from mqqapi rich share
                    else if (dataString.startsWith("mqqapi://share/")) {
                        val uri = intent.data ?: return
                        val encodedUrl = uri.getQueryParameter("url")
                        if (!encodedUrl.isNullOrEmpty()) {
                            try {
                                urlToShare = String(Base64.decode(encodedUrl, Base64.DEFAULT))
                            } catch (_: Exception) {
                                urlToShare = encodedUrl // Maybe not base64
                            }
                        }
                    }
                    // Extract URL from Tencent Open SDK AssistActivity or WeChat intent
                    else if (intent.extras != null) {
                        urlToShare = findUrlInBundle(intent.extras, classLoader)
                    }

                    if (urlToShare.isNullOrEmpty()) {
                        // If it's a URL wrapped in some other text, try to extract just the http(s) part
                        val anyText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                        val httpMatch = Regex("https?://[^\\s]+").find(anyText)
                        if (httpMatch != null) {
                            urlToShare = httpMatch.value
                        }
                    }

                    if (!urlToShare.isNullOrEmpty()) {
                        val appName = if (isWeChatShare) "WeChat" else "QQ"
                        XposedBridge.log("[DailyRacingBlocker] Intercepted $appName rich share, converting to text: $urlToShare")

                        val plainTextIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, urlToShare)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                        param.args[intentIndex] = if (isWeChatShare) {
                            // Route through system chooser to bypass WeChat SDK caller-signature checks.
                            // WeChat verifies the calling package's signature even for plain text intents
                            // when targeted directly, so the system must be the caller.
                            plainTextIntent.`package` = "com.tencent.mm"
                            Intent.createChooser(plainTextIntent, null)
                        } else {
                            // QQ: bypass the intermediate QQ chooser dialog and jump directly to friends list
                            plainTextIntent.component = ComponentName(
                                "com.tencent.mobileqq",
                                "com.tencent.mobileqq.activity.JumpActivity"
                            )
                            plainTextIntent
                        }
                    } else {
                        val appName = if (isWeChatShare) "WeChat" else "QQ"
                        XposedBridge.log("[DailyRacingBlocker] WARNING: $appName share detected but URL extraction failed!")
                        intent.extras?.keySet()?.forEach { key ->
                            XposedBridge.log("[DailyRacingBlocker] Extra key: $key")
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[DailyRacingBlocker] Error in share hook: ${t.message}")
                }
            }
        }

        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.app.Instrumentation", classLoader),
                "execStartActivity",
                shareHook
            )
            XposedBridge.log("[DailyRacingBlocker] hooked Instrumentation.execStartActivity for share")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Instrumentation: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                android.app.Activity::class.java,
                "startActivityForResult",
                shareHook
            )
            XposedBridge.log("[DailyRacingBlocker] hooked Activity.startActivityForResult for share")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Activity: ${t.message}")
        }

        try {
            val contextImplClass = XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader)
            if (contextImplClass != null) {
                XposedBridge.hookAllMethods(contextImplClass, "startActivity", shareHook)
                XposedBridge.log("[DailyRacingBlocker] hooked ContextImpl.startActivity for share")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook ContextImpl: ${t.message}")
        }
    }

    private fun hookWeChatSendReq(classLoader: ClassLoader) {
        try {
            val iwxApi = XposedHelpers.findClassIfExists(
                "com.tencent.mm.opensdk.openapi.IWXAPI", classLoader) ?: return
            XposedBridge.hookAllMethods(iwxApi, "sendReq", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val req = param.args.getOrNull(0) ?: return
                        if (!req.javaClass.name.contains("SendMessageToWX")) return
                        val message = XposedHelpers.getObjectField(req, "message") ?: return

                        val mediaObject = try {
                            XposedHelpers.getObjectField(message, "mediaObject")
                        } catch (_: Exception) { null }

                        val url = if (mediaObject != null &&
                            mediaObject.javaClass.name.contains("WXWebpageObject")) {
                            try {
                                XposedHelpers.getObjectField(mediaObject, "webpageUrl") as? String
                            } catch (_: Exception) { null }
                        } else null

                        if (url.isNullOrEmpty()) return

                        XposedBridge.log("[DailyRacingBlocker] Intercepted WX sendReq, URL=$url")

                        // Block the original sendReq (which would use ContentProvider →
                        // WeChat SDK checks signature and WeChat rejects the self-signed caller)
                        param.result = true

                        // Fire a plain-text share through the system chooser instead
                        val ctx = resolveContext() ?: return
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                            `package` = "com.tencent.mm"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        ctx.startActivity(Intent.createChooser(intent, null))
                    } catch (t: Throwable) {
                        XposedBridge.log("[DailyRacingBlocker] WX sendReq hook error: ${t.message}")
                    }
                }
            })
            XposedBridge.log("[DailyRacingBlocker] hooked IWXAPI.sendReq")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook WX sendReq: ${t.message}")
        }
    }

    private fun findUrlInBundle(bundle: Bundle?, classLoader: ClassLoader): String? {
        if (bundle == null) return null
        try {
            bundle.classLoader = classLoader
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value is String) {
                    val match = Regex("https?://[^\\s]+").find(value)
                    if (match != null) return match.value
                } else if (value is Bundle) {
                    findUrlInBundle(value, classLoader)?.let { return it }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] Bundle unparceling error: ${e.message}")
        }
        return null
    }

    private fun zeroInetAddress(host: String?): InetAddress {
        return InetAddress.getByAddress(host ?: BlockRules.zeroAddress, byteArrayOf(0, 0, 0, 0))
    }

    private fun recordBlock(packageName: String, host: String?, source: String) {
        val normalizedHost = host ?: return

        val event = BlockEvent(
            time = System.currentTimeMillis(),
            packageName = packageName,
            host = normalizedHost,
            source = source,
            result = BlockRules.zeroAddress
        )

        val context = resolveContext()
        if (context == null) {
            pendingRecords.add(event)
            return
        }
        saveRecord(context, event)
    }

    private fun flushPendingRecords() {
        val context = resolveContext() ?: return
        val copy = synchronized(pendingRecords) {
            pendingRecords.toList().also { pendingRecords.clear() }
        }
        copy.forEach { saveRecord(context, it) }
    }

    private fun saveRecord(context: Context, event: BlockEvent) {
        var providerSaved = false
        try {
            val values = ContentValues().apply {
                put(BlockRecordStore.COL_TIME, event.time)
                put(BlockRecordStore.COL_PACKAGE, event.packageName)
                put(BlockRecordStore.COL_HOST, event.host)
                put(BlockRecordStore.COL_SOURCE, event.source)
                put(BlockRecordStore.COL_RESULT, event.result)
            }
            providerSaved = context.contentResolver.insert(BlockRecordProvider.CONTENT_URI, values) != null
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to save record: ${t.message}")
        }
        if (!providerSaved) {
            sendRecordBroadcast(context, event)
        }
    }

    private fun resolveContext(): Context? {
        targetContext?.let { return it }
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null) as? Application
            app?.applicationContext?.also {
                targetContext = it
                flushPendingRecords()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun sendRecordBroadcast(context: Context, event: BlockEvent) {
        try {
            val intent = Intent(BlockRecordReceiver.ACTION_RECORD_BLOCK).apply {
                setPackage("com.fxxkad.dailyracing")
                putExtra(BlockRecordReceiver.EXTRA_TIME, event.time)
                putExtra(BlockRecordReceiver.EXTRA_PACKAGE, event.packageName)
                putExtra(BlockRecordReceiver.EXTRA_HOST, event.host)
                putExtra(BlockRecordReceiver.EXTRA_SOURCE, "${event.source}/broadcast")
                putExtra(BlockRecordReceiver.EXTRA_RESULT, event.result)
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to broadcast record: ${t.message}")
        }
    }

    private data class BlockEvent(
        val time: Long,
        val packageName: String,
        val host: String,
        val source: String,
        val result: String
    )
}
