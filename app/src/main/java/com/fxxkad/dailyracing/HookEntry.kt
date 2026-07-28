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
        hookWeChatSdkSendReq(lpparam.classLoader)
        AdSdkBlocker.install(lpparam.classLoader)
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
                    BlockRules.loadRules(targetContext)
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
                            dataString.contains("wechat") ||
                            dataString.contains("weixin") ||
                            intent.extras?.keySet()?.any { key ->
                                key.contains("_wxapi_") || key.contains("_wxobject_")
                            } ?: false

                    // Block quick-app (快应用) launches used by ad landing pages.
                    val scheme = intent.data?.scheme ?: ""
                    val isQuickApp = dataString.startsWith("hap://") ||
                            dataString.startsWith("hwfastapp://") ||
                            scheme == "hap" || scheme == "hwfastapp" ||
                            dataString.contains("hapjs") ||
                            targetPackage == "com.huawei.fastapp" ||
                            targetPackage == "org.hapjs.mockup" ||
                            componentName.contains("hapjs")
                    if (isQuickApp) {
                        XposedBridge.log("[DailyRacingBlocker] blocked quick-app launch: $dataString $componentName")
                        param.result = null
                        return
                    }

                    if (!isQQShare && !isWeChatShare) return

                    intent.extras?.classLoader = classLoader

                    // Check if the fix is enabled
                    if (!isFixShareEnabled()) return

                    var urlToShare: String? = null
                    var titleToShare: String? = null

                    if (action == Intent.ACTION_SEND) {
                        urlToShare = intent.getStringExtra(Intent.EXTRA_TEXT)
                        titleToShare = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    } else if (dataString.startsWith("mqqapi://share/")) {
                        val uri = intent.data ?: return
                        // Only the url is Base64-encoded; the title is plain (percent-decoded
                        // by getQueryParameter), so decoding it would produce garbled text.
                        urlToShare = decodeMaybeBase64(uri.getQueryParameter("url"))
                        titleToShare = uri.getQueryParameter("title")
                    } else if (intent.extras != null) {
                        urlToShare = findUrlInBundle(intent.extras, classLoader)
                    }

                    if (urlToShare.isNullOrEmpty()) {
                        val anyText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                        val httpMatch = Regex("https?://[^\\s]+").find(anyText)
                        if (httpMatch != null) urlToShare = httpMatch.value
                    }

                    if (!urlToShare.isNullOrEmpty()) {
                        val appName = if (isWeChatShare) "WeChat" else "QQ"
                        val shareText = buildShareText(titleToShare, urlToShare)
                        XposedBridge.log("[DailyRacingBlocker] Intercepted $appName rich share, converting to text: $shareText")

                        val plainTextIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                        param.args[intentIndex] = if (isWeChatShare) {
                            plainTextIntent.`package` = "com.tencent.mm"
                            Intent.createChooser(plainTextIntent, null)
                        } else {
                            plainTextIntent.component = ComponentName(
                                "com.tencent.mobileqq",
                                "com.tencent.mobileqq.activity.JumpActivity"
                            )
                            plainTextIntent
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
                "execStartActivity", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked Instrumentation.execStartActivity")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Instrumentation: ${t.message}")
        }
        try {
            XposedBridge.hookAllMethods(
                android.app.Activity::class.java,
                "startActivityForResult", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked Activity.startActivityForResult")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Activity: ${t.message}")
        }
        try {
            val cImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", classLoader)
            if (cImpl != null) {
                XposedBridge.hookAllMethods(cImpl, "startActivity", shareHook)
                XposedBridge.log("[DailyRacingBlocker] hooked ContextImpl.startActivity")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook ContextImpl: ${t.message}")
        }
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.content.ContextWrapper", classLoader),
                "startActivity", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked ContextWrapper.startActivity")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook ContextWrapper: ${t.message}")
        }
        try {
            XposedBridge.hookAllMethods(
                android.app.Activity::class.java,
                "startActivity", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked Activity.startActivity")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Activity.startActivity: ${t.message}")
        }
    }

    // ---------- WeChat SDK sendReq interception ----------

    private fun hookWeChatSdkSendReq(classLoader: ClassLoader) {
        val sdkClasses = listOf(
            "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10",
            "com.tencent.mm.opensdk.openapi.WXApiImplV10",
            "com.tencent.mm.sdk.openapi.WXApiImplV10",
        )
        for (clsName in sdkClasses) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
                XposedBridge.hookAllMethods(cls, "sendReq", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        interceptWxSendReq(param)
                    }
                })
                XposedBridge.log("[DailyRacingBlocker] hooked sendReq on $clsName")
                break // One successful hook is enough
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] skip $clsName: ${t.message}")
            }
        }
    }

    private fun interceptWxSendReq(param: XC_MethodHook.MethodHookParam) {
        try {
            val req = param.args.getOrNull(0) ?: return
            if (!req.javaClass.name.contains("SendMessageToWX")) return
            val url = extractUrlFromWxReq(req) ?: return
            val title = extractTitleFromWxReq(req)

            // Respect the "fix share" toggle
            if (!isFixShareEnabled()) return

            val shareText = buildShareText(title, url)
            XposedBridge.log("[DailyRacingBlocker] Intercepted WX sendReq, text=$shareText")
            param.result = true
            startWxPlainTextShare(shareText)
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] sendReq hook error: ${t.message}")
        }
    }

    private fun extractUrlFromWxReq(req: Any): String? {
        val message = XposedHelpers.getObjectField(req, "message") ?: return null
        val mediaObject = try {
            XposedHelpers.getObjectField(message, "mediaObject")
        } catch (_: Exception) { null }

        if (mediaObject != null && mediaObject.javaClass.name.contains("WXWebpageObject")) {
            try {
                return XposedHelpers.getObjectField(mediaObject, "webpageUrl") as? String
            } catch (_: Exception) {}
        }
        try { return XposedHelpers.getObjectField(message, "description") as? String } catch (_: Exception) {}
        try { return XposedHelpers.getObjectField(message, "messageExt") as? String } catch (_: Exception) {}
        return null
    }

    private fun extractTitleFromWxReq(req: Any): String? {
        val message = XposedHelpers.getObjectField(req, "message") ?: return null
        return try {
            XposedHelpers.getObjectField(message, "title") as? String
        } catch (_: Exception) {
            null
        }
    }

    private fun startWxPlainTextShare(text: String) {
        val ctx = resolveContext() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            // Explicit component so the chooser has only one entry; the system
            // may skip the dialog on single-match. Route through chooser to keep
            // the system as the caller (bypasses WeChat caller-signature check).
            component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
        }
        val chooser = Intent.createChooser(intent, null)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

    private fun isFixShareEnabled(): Boolean {
        val context = resolveContext() ?: return true
        return try {
            val bundle = context.contentResolver.call(
                BlockRecordProvider.CONTENT_URI,
                "get_setting",
                "fix_share",
                null
            )
            bundle?.getBoolean("value", true) ?: true
        } catch (_: Exception) {
            try {
                val mc = context.createPackageContext(
                    "com.fxxkad.dailyracing",
                    Context.CONTEXT_IGNORE_SECURITY
                )
                mc.getSharedPreferences("block_stats", Context.MODE_PRIVATE)
                    .getBoolean("fix_share", true)
            } catch (_: Exception) {
                true
            }
        }
    }

    // ---------- Helpers ----------

    /** Combines the article title and URL as "title\nurl"; falls back to the URL alone. */
    private fun buildShareText(title: String?, url: String): String {
        val trimmedTitle = title?.trim()
        return if (!trimmedTitle.isNullOrEmpty() && trimmedTitle != url) {
            "$trimmedTitle\n$url"
        } else {
            url
        }
    }

    /** Decodes a possibly Base64-encoded query value, returning the raw value on failure. */
    private fun decodeMaybeBase64(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return try {
            String(Base64.decode(value, Base64.DEFAULT))
        } catch (_: Exception) {
            value
        }
    }

    @Suppress("DEPRECATION")
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
        } catch (_: Exception) {}
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
        } catch (_: Throwable) {
            // Provider unreachable in target process — will fall back to broadcast
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
                BlockRules.loadRules(it)
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
        } catch (_: Throwable) {}
    }

    private data class BlockEvent(
        val time: Long,
        val packageName: String,
        val host: String,
        val source: String,
        val result: String
    )
}
