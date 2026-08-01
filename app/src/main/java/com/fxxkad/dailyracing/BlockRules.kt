package com.fxxkad.dailyracing

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.File
import java.util.zip.ZipFile

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    private const val MODULE_PACKAGE = "com.fxxkad.dailyracing"
    @Volatile
    private var hosts: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    @Synchronized
    fun loadRules(context: Context? = null, force: Boolean = false) {
        if (loaded && !force) return

        val newHosts = mutableSetOf<String>()
        newHosts.addAll(DomainRules.builtInDomains)
        loadBuiltInRules(context, newHosts)
        loadFromModuleApk(newHosts)

        hosts = newHosts.toSet()
        loaded = true
        XposedBridge.log("[DailyRacingBlocker] loaded ${newHosts.size} domain rules")
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

    /** Reads bundled rule files under assets/rules from the module APK (works in integrated mode). */
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

    private fun parseRules(reader: BufferedReader, onDomain: (String) -> Unit) {
        reader.forEachLine { line ->
            DomainRules.parseLine(line).forEach(onDomain)
        }
    }

    val domainCount: Int get() = hosts.size

    fun shouldBlock(host: String?): Boolean {
        return DomainRules.matches(hosts, host)
    }
}
