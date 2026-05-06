package com.fxxkad.dailyracing

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.File
import java.util.Collections

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    private const val MODULE_PACKAGE = "com.fxxkad.dailyracing"
    private const val EXTERNAL_RULES_DIR = "/sdcard/DailyRacingBlocker/rules"

    private val hosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var loaded = false

    fun loadRules(context: Context? = null, force: Boolean = false) {
        if (loaded && !force) return

        val newHosts = mutableSetOf<String>()
        loadBuiltInRules(context, newHosts)
        loadExternalRules(newHosts)

        synchronized(hosts) {
            hosts.clear()
            hosts.addAll(newHosts)
        }
        loaded = true
        XposedBridge.log("[DailyRacingBlocker] loaded ${hosts.size} domain rules")
    }

    private fun loadBuiltInRules(context: Context?, output: MutableSet<String>) {
        try {
            val moduleContext = context?.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            ) ?: context
            val assets = moduleContext?.assets ?: return
            for (fileName in assets.list("rules").orEmpty()) {
                if (!fileName.endsWith(".txt")) continue
                assets.open("rules/$fileName").bufferedReader().use { reader ->
                    parseRules(reader) { output.add(it) }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] failed to load built-in rules: ${e.message}")
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
