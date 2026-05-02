package com.fxxkad.dailyracing

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        hookClassLoader(lpparam.classLoader)
        hookApplicationAttach()
        hookInetAddress(lpparam.packageName)
        hookAndroidNameService(lpparam.packageName)
        hookShareIntent(lpparam.classLoader)
        hookWeChatSendReq(lpparam.classLoader)
        hookTencentSDK(lpparam.classLoader)
    }

    private fun hookClassLoader(classLoader: ClassLoader) {
        try {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name.contains("tencent.mm") || name.contains("WXApi")
                            || name.contains("wechat") || name.contains("Wechat")
                            || name.contains("WXMedia") || name.contains("WXWebpage")
                            || name.contains("com.tencent.connect")
                            || name.contains("com.tencent.tauth")
                            || name.contains("Tencent") && name.contains("openapi")) {
                            XposedBridge.log("[DailyRacingBlocker] CLASS: $name")
                        }
                    }
                })
            XposedBridge.log("[DailyRacingBlocker] hooked ClassLoader.loadClass")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed ClassLoader hook: ${t.message}")
        }
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

                    // Log every intent that looks share-like to diagnose WeChat interception
                    if (!isQQShare && !isWeChatShare) {
                        val hasInteresting = targetPackage.isNotEmpty() || componentName.isNotEmpty() ||
                            action != null || intent.type != null || dataString.isNotEmpty() ||
                            (intent.extras != null && intent.extras!!.keySet().isNotEmpty())
                        if (hasInteresting) {
                            val trace = Thread.currentThread().stackTrace
                                .dropWhile { it.className.contains("de.robv.android.xposed") ||
                                             it.className.contains("android.app") }
                                .take(6).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                            XposedBridge.log("[DailyRacingBlocker] TRACE — " +
                                "pkg=${targetPackage} comp=${componentName} act=${action} " +
                                "type=${intent.type} data=${dataString.take(120)} " +
                                "keys=${intent.extras?.keySet()?.take(10)} | $trace")
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

        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.content.ContextWrapper", classLoader),
                "startActivity", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked ContextWrapper.startActivity for share")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook ContextWrapper: ${t.message}")
        }

        try {
            XposedBridge.hookAllMethods(
                android.app.Activity::class.java,
                "startActivity", shareHook)
            XposedBridge.log("[DailyRacingBlocker] hooked Activity.startActivity for share")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook Activity.startActivity: ${t.message}")
        }
    }

    private var crLogCount = 0

    private fun hookTencentSDK(classLoader: ClassLoader) {
        val tcClasses = listOf(
            "com.tencent.tauth.Tencent",
            "com.tencent.connect.common.BaseApi",
            "com.tencent.connect.share.QQShare",
            "com.tencent.connect.share.QzoneShare",
        )
        for (clsName in tcClasses) {
            val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
            val methods = try { cls.declaredMethods.map { it.name }.distinct() } catch (_: Throwable) { emptyList() }
            for (m in methods) {
                try {
                    XposedBridge.hookAllMethods(cls, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            XposedBridge.log("[DailyRacingBlocker] TC.${cls.simpleName}.${param.method.name}()")
                        }
                    })
                } catch (_: Throwable) {}
            }
            XposedBridge.log("[DailyRacingBlocker] hooked ${cls.simpleName}: $methods")
        }
    }

    private fun hookWeChatSendReq(classLoader: ClassLoader) {
        // ---------- Path A: hook WeChat SDK's sendReq + openWXApp ----------
        val sdkImplClasses = listOf(
            "com.tencent.mm.opensdk.openapi.BaseWXApiImplV10",
            "com.tencent.mm.opensdk.openapi.WXApiImplV10",
            "com.tencent.mm.opensdk.openapi.WXApiImplV20",
            "com.tencent.mm.sdk.openapi.WXApiImplV10",
            "com.tencent.mm.sdk.openapi.WXApiImplV20",
        )
        for (clsName in sdkImplClasses) {
            try {
                val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
                for (method in listOf("sendReq", "openWXApp")) {
                    try {
                        XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (method == "sendReq") interceptWxSendReq(param)
                                else XposedBridge.log("[DailyRacingBlocker] openWXApp called on $clsName")
                            }
                        })
                        XposedBridge.log("[DailyRacingBlocker] hooked $method on $clsName")
                    } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] skip $clsName: ${t.message}")
            }
        }

        // ---------- Path A2: hook fluwx Flutter channel entry point ----------
        try {
            val fluwxHandler = XposedHelpers.findClassIfExists(
                "com.jarvan.fluwx.handlers.FluwxShareHandler", classLoader)
            if (fluwxHandler != null) {
                XposedBridge.hookAllMethods(fluwxHandler, "onMethodCall", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val call = param.args.getOrNull(0)
                            val callClass = call?.javaClass
                            val method = callClass?.getMethod("method")?.invoke(call) as? String
                            XposedBridge.log("[DailyRacingBlocker] FLUWX share method=$method")
                        } catch (t: Throwable) {
                            XposedBridge.log("[DailyRacingBlocker] FLUWX onMethodCall error: ${t.message}")
                        }
                    }
                })
                XposedBridge.log("[DailyRacingBlocker] hooked FluuwxShareHandler.onMethodCall")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] fluwx hook failed: ${t.message}")
        }

        // ---------- Path B: hook ContentResolver.{insert,call,query} ----------
        val crHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val uri = param.args.getOrNull(0) as? Uri ?: return
                val auth = uri.authority ?: return
                if (crLogCount < 80) {
                    crLogCount++
                    XposedBridge.log("[DailyRacingBlocker] CR.${param.method.name} authority=$auth")
                }
                if (auth.contains("tencent") || auth.contains(".mm.") || auth.contains("wx")
                    || auth.contains("mm.sdk") || auth.contains("mm.opensdk")) {
                    interceptWxContentProvider(param, classLoader)
                }
            }
        }
        for (m in listOf("insert", "call", "query")) {
            try {
                XposedBridge.hookAllMethods(ContentResolver::class.java, m, crHook)
                XposedBridge.log("[DailyRacingBlocker] hooked ContentResolver.$m")
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] failed to hook CR.$m: ${t.message}")
            }
        }

        // ---------- Path C: hook sendBroadcast / bindService / startService ----------
        val cwClass = try {
            XposedHelpers.findClass("android.content.ContextWrapper", classLoader)
        } catch (_: Throwable) { null }
        if (cwClass != null) {
            // sendBroadcast
            try {
                XposedBridge.hookAllMethods(cwClass, "sendBroadcast", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        val pkg = intent.`package` ?: intent.component?.packageName ?: ""
                        if (pkg == "com.tencent.mm")
                            XposedBridge.log("[DailyRacingBlocker] SEND_BROADCAST -> com.tencent.mm")
                        interceptWxBroadcast(intent, classLoader)
                    }
                })
                XposedBridge.log("[DailyRacingBlocker] hooked ContextWrapper.sendBroadcast")
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] failed sendBroadcast: ${t.message}")
            }
            // bindService
            try {
                XposedBridge.hookAllMethods(cwClass, "bindService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        val pkg = intent.`package` ?: intent.component?.packageName ?: ""
                        XposedBridge.log("[DailyRacingBlocker] BIND_SERVICE pkg=$pkg comp=${intent.component?.className}")
                        if (pkg == "com.tencent.mm") {
                            XposedBridge.log("[DailyRacingBlocker] >>> bindService to WeChat detected!")
                        }
                    }
                })
                XposedBridge.log("[DailyRacingBlocker] hooked ContextWrapper.bindService")
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] failed bindService: ${t.message}")
            }
            // startService
            try {
                XposedBridge.hookAllMethods(cwClass, "startService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args.getOrNull(0) as? Intent ?: return
                        val pkg = intent.`package` ?: intent.component?.packageName ?: ""
                        XposedBridge.log("[DailyRacingBlocker] START_SERVICE pkg=$pkg comp=${intent.component?.className}")
                    }
                })
                XposedBridge.log("[DailyRacingBlocker] hooked ContextWrapper.startService")
            } catch (t: Throwable) {
                XposedBridge.log("[DailyRacingBlocker] failed startService: ${t.message}")
            }
        }

        // ---------- Path D: monitor all WXApiImplV10 methods ----------
        val wxImplClass = XposedHelpers.findClassIfExists(
            "com.tencent.mm.opensdk.openapi.WXApiImplV10", classLoader)
        if (wxImplClass != null) {
            val wxMethods = listOf("sendReq", "sendResp", "registerApp", "detach",
                "isWXAppInstalled", "openWXApp", "unregisterApp", "handleWxInternalResp")
            for (m in wxMethods) {
                try {
                    XposedBridge.hookAllMethods(wxImplClass, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args.map { it?.javaClass?.simpleName ?: "null" }
                            XposedBridge.log("[DailyRacingBlocker] WXApi.${param.method.name}($args) called")
                        }
                    })
                } catch (_: Throwable) {}
            }
            XposedBridge.log("[DailyRacingBlocker] hooked WXApiImplV10 methods: ${wxMethods}")
        }
    }

    // --- WeChat interception helpers ---

    private fun interceptWxSendReq(param: XC_MethodHook.MethodHookParam) {
        try {
            val req = param.args.getOrNull(0) ?: return
            if (!req.javaClass.name.contains("SendMessageToWX")) return
            val url = extractUrlFromWxMessage(req) ?: return
            XposedBridge.log("[DailyRacingBlocker] Intercepted WX sendReq, URL=$url")
            param.result = true
            startWxPlainTextShare(url)
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] sendReq hook error: ${t.message}")
        }
    }

    private fun interceptWxContentProvider(param: XC_MethodHook.MethodHookParam, cl: ClassLoader) {
        try {
            val url = when (param.method.name) {
                "insert" -> {
                    val values = param.args.getOrNull(1) as? ContentValues ?: return
                    extractUrlFromContentValues(values)
                }
                "call" -> {
                    val bundle = param.args.getOrNull(3) as? Bundle
                    if (bundle != null) extractUrlFromBundle(bundle, cl) else null
                }
                else -> null
            }
            if (url.isNullOrEmpty()) return
            XposedBridge.log("[DailyRacingBlocker] Intercepted WX ContentProvider ${param.method.name}, URL=$url")
            when (param.method.name) {
                "insert" -> param.result = Uri.parse("content://com.tencent.mm.sdk.comm.provider/response/0")
                "call"   -> param.result = Bundle()
            }
            startWxPlainTextShare(url)
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] ContentProvider hook error: ${t.message}")
        }
    }

    private fun interceptWxBroadcast(intent: Intent, cl: ClassLoader) {
        try {
            intent.extras?.classLoader = cl
            val url = intent.extras?.let { extractUrlFromBundle(it, cl) }
            if (url.isNullOrEmpty()) return
            XposedBridge.log("[DailyRacingBlocker] Intercepted WX broadcast, URL=$url")
            startWxPlainTextShare(url)
            // Don't block the broadcast — just also show our chooser
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] broadcast hook error: ${t.message}")
        }
    }

    private fun extractUrlFromContentValues(values: ContentValues): String? {
        for (key in values.keySet()) {
            val v = values.get(key) ?: continue
            val str = if (v is ByteArray) String(v) else v.toString()
            val m = Regex("https?://[^\\s]+").find(str)
            if (m != null && m.value.length > 20) return m.value
        }
        return null
    }

    private fun extractUrlFromBundle(bundle: Bundle?, cl: ClassLoader): String? {
        if (bundle == null) return null
        try {
            bundle.classLoader = cl
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value is String) {
                    val m = Regex("https?://[^\\s]+").find(value)
                    if (m != null && m.value.length > 20) return m.value
                } else if (value is Bundle) {
                    extractUrlFromBundle(value, cl)?.let { return it }
                } else if (value is ByteArray) {
                    val m = Regex("https?://[^\\s]+").find(String(value))
                    if (m != null && m.value.length > 20) return m.value
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractUrlFromWxMessage(req: Any): String? {
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

    private fun startWxPlainTextShare(url: String) {
        val ctx = resolveContext() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            `package` = "com.tencent.mm"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(Intent.createChooser(intent, null))
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
