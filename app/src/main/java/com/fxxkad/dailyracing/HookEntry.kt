package com.fxxkad.dailyracing

import android.app.Application
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
        hookQQShareIntent(lpparam.classLoader)
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

    private fun hookQQShareIntent(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation",
                classLoader,
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                android.app.Activity::class.java,
                Intent::class.java,
                Int::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args[4] as? Intent ?: return

                            // Check if this is a QQ share intent
                            val action = intent.action
                            val dataString = intent.dataString ?: ""
                            val componentName = intent.component?.className ?: ""
                            val targetPackage = intent.`package` ?: intent.component?.packageName ?: ""

                            val isQQShare = targetPackage == "com.tencent.mobileqq" ||
                                          dataString.startsWith("mqqapi://share/") ||
                                          componentName.contains("com.tencent.connect.common.AssistActivity")

                            if (!isQQShare) return

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
                            // Extract URL from Tencent Open SDK AssistActivity
                            else if (intent.extras != null) {
                                val bundle = intent.extras
                                urlToShare = bundle?.getString("targetUrl") ?:
                                             bundle?.getString("url") ?:
                                             bundle?.getBundle("key_params")?.getString("targetUrl")
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
                                XposedBridge.log("[DailyRacingBlocker] Intercepted QQ rich share, converting to text: $urlToShare")

                                val plainTextIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, urlToShare)
                                    // Set package to QQ to ensure it directly opens QQ instead of chooser
                                    `package` = "com.tencent.mobileqq"
                                }

                                param.args[4] = plainTextIntent
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[DailyRacingBlocker] Error in QQ share hook: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("[DailyRacingBlocker] hooked Instrumentation.execStartActivity for QQ share")
        } catch (t: Throwable) {
            XposedBridge.log("[DailyRacingBlocker] failed to hook QQ share: ${t.message}")
        }
    }

    private fun zeroInetAddress(host: String?): InetAddress {
        return InetAddress.getByAddress(host ?: BlockRules.zeroAddress, byteArrayOf(0, 0, 0, 0))
    }

    private fun recordBlock(packageName: String, host: String?, source: String) {
        val normalizedHost = host ?: return
        XposedBridge.log("[DailyRacingBlocker] blocked $normalizedHost -> ${BlockRules.zeroAddress}")

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
