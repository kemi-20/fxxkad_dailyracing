package com.fxxkad.dailyracing

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    private const val MODULE_PACKAGE = "com.fxxkad.dailyracing"
    private const val EXTERNAL_RULES_DIR = "/sdcard/DailyRacingBlocker/rules"

    private val hosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var loaded = false

    /**
     * Built-in blocklist compiled into the module code. This is the primary source
     * in NPatch integrated mode where the module is NOT installed as a separate app
     * (so createPackageContext fails) and host assets may be unavailable. Keep this
     * in sync with assets/rules/default.txt.
     */
    private val BUILTIN_DOMAINS = listOf(
        // Ad / tracking
        "adx.adwangmai.com",
        "gdfp.gifshow.com",
        "e.kuaishou.cn",
        "e.kuaishou.com",
        "open.e.kuaishou.com",
        "api.e.kuaishou.com",
        "ksapisrv.gifshow.com",
        "zt.gifshow.com",
        "ulog-sdk.gifshow.com",
        "anythinktech.com",
        "toponad.com",
        "da.toponad.com",
        "gdt.qq.com",
        "win.gdt.qq.com",
        "c.gdt.qq.com",
        "v.gdt.qq.com",
        "t.gdt.qq.com",
        "qzs.gdtimg.com",
        "pgdt.gtimg.cn",
        "pgdt.gtimg.com",
        "e.qq.com",
        "bgg.baidu.com",
        "mobads.baidu.com",
        "mobads-logs.baidu.com",
        "afd.baidu.com",
        "als.baidu.com",
        // Pangolin / ByteDance ad
        "pangolin-sdk-toutiao.com",
        "api-access.pangolin-sdk-toutiao.com",
        "api-access.pangolin-sdk-toutiao-b.com",
        "pglstatp-toutiao.com",
        "sf3-fe-tos.pglstatp-toutiao.com",
        "dsp.toutiao.com",
        "ad.toutiao.com",
        "is.snssdk.com",
        "i.snssdk.com",
        "log.snssdk.com",
        "extlog.snssdk.com",
        "mon.snssdk.com",
        "toblog.ctobsnssdk.com",
        "ctobsnssdk.com",
        "pangle.io",
        "pangleglobal.com",
        "mssdk.volces.com",
        // Sigmob
        "sigmob.cn",
        "sigmob.com",
        "adservice.sigmob.cn",
        // Push / analytics
        "jpush.cn",
        "jpush.io",
        "umengcloud.com",
        "umeng.com",
        "ulogs.umeng.com",
        "plbslog.umeng.com",
        "ainfo.umeng.com",
        "msgstat.umengcloud.com",
        // Xiaomi tracking
        "mcc.inf.miui.com",
        "tracking.miui.com",
        // ByteDance tracking
        "tnc3-aliec2.zijieapi.com",
        "tnc3-alisc1.zijieapi.com",
        "tnc3-bjlgy.zijieapi.com",
        // Tencent tracking
        "h.trace.qq.com",
        "dns.weixin.qq.com.cn",
        "szlong.weixin.qq.com",
        // Quick-app distribution
        "fastappjump-drcn.hispace.hicloud.com",
        "fastappjump-drcn.hispace.dbankcloud.cn",
        "hapjs.org",
        "statres.quickapp.cn",
        "cdn.quickapp.cn",
        // Other
        "easytomessage.com"
    )

    fun loadRules(context: Context? = null, force: Boolean = false) {
        if (loaded && !force) return

        val newHosts = mutableSetOf<String>()
        // 1) Always seed with the compiled-in list so blocking works even when no
        //    external source is reachable (e.g. NPatch integrated mode).
        newHosts.addAll(BUILTIN_DOMAINS.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        // 2) Merge additional/updated rules from every reachable source.
        loadBuiltInRules(context, newHosts)
        loadFromModuleApk(newHosts)
        loadExternalRules(newHosts)

        synchronized(hosts) {
            hosts.clear()
            hosts.addAll(newHosts)
        }
        loaded = true
        XposedBridge.log("[DailyRacingBlocker] loaded ${hosts.size} domain rules")
    }

    private fun loadBuiltInRules(context: Context?, output: MutableSet<String>) {
        // Try the host app's own AssetManager first (NPatch may merge module assets),
        // then fall back to createPackageContext (classic LSPosed, module installed).
        val assetManagers = mutableListOf<android.content.res.AssetManager>()
        context?.assets?.let { assetManagers.add(it) }
        try {
            val moduleContext = context?.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            moduleContext?.assets?.let { assetManagers.add(it) }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] module context unavailable: ${e.message}")
        }

        for (assets in assetManagers) {
            try {
                for (fileName in assets.list("rules").orEmpty()) {
                    if (!fileName.endsWith(".txt")) continue
                    assets.open("rules/$fileName").bufferedReader().use { reader ->
                        parseRules(reader) { output.add(it) }
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("[DailyRacingBlocker] failed to load asset rules: ${e.message}")
            }
        }
    }

    /** Reads assets/rules/*.txt directly from the module APK (works in integrated mode). */
    private fun loadFromModuleApk(output: MutableSet<String>) {
        try {
            val location = javaClass.protectionDomain?.codeSource?.location ?: return
            val path = File(location.toURI()).absolutePath
            if (!path.endsWith(".apk", ignoreCase = true) && !path.endsWith(".zip", ignoreCase = true)) return
            ZipFile(path).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("assets/rules/") || !entry.name.endsWith(".txt")) continue
                    zip.getInputStream(entry).bufferedReader().use { reader ->
                        parseRules(reader) { output.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] failed to load rules from module APK: ${e.message}")
        }
    }

    private fun loadExternalRules(output: MutableSet<String>) {
        try {
            val extDir = File(EXTERNAL_RULES_DIR)
            if (extDir.isDirectory) {
                for (file in extDir.listFiles().orEmpty()) {
                    if (!file.name.endsWith(".txt")) continue
                    file.bufferedReader().use { reader -> parseRules(reader) { output.add(it) } }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] failed to load external rules: ${e.message}")
        }
    }

    private fun parseRules(reader: BufferedReader, onDomain: (String) -> Unit) {
        reader.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) return@forEachLine
            if (trimmed.startsWith("@@")) return@forEachLine
            val domain = extractDomain(trimmed)
            if (domain.isNotEmpty()) onDomain(domain.lowercase())
        }
    }

    private fun extractDomain(line: String): String {
        val hostsMatch = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+(.+)").find(line)
        if (hostsMatch != null) return hostsMatch.groupValues[1].trim()

        var domain = line.trimStart('|').trimEnd('^')
        domain = domain.removePrefix("http://").removePrefix("https://")
        domain = domain.split("/", "#", " ", ":")[0]
        domain = domain.trimStart('*', '.')
        return domain.trim()
    }

    val domainCount: Int get() = synchronized(hosts) { hosts.size }

    fun shouldBlock(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        val list = synchronized(hosts) { hosts.toList() }
        return list.any { normalized == it || normalized.endsWith(".$it") }
    }
}
