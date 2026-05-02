package com.fxxkad.dailyracing

import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.zip.ZipFile

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    private const val EXTERNAL_RULES_DIR = "/sdcard/DailyRacingBlocker/rules"

    private val hosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Load all rule files. Call once after module initialisation. */
    fun loadRules() {
        val newHosts = mutableSetOf<String>()

        // 1. Built-in rules — read directly from the module APK zip
        try {
            val apkPath = BlockRules::class.java.protectionDomain
                .codeSource.location.path
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.startsWith("assets/rules/") && name.endsWith(".txt")) {
                        val fileName = name.removePrefix("assets/rules/")
                        zip.getInputStream(entry).use { stream ->
                            parseRules(stream.reader().buffered(), fileName) { newHosts.add(it) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[DailyRacingBlocker] failed to load built-in rules: ${e.message}")
        }

        // 2. External rules from /sdcard/DailyRacingBlocker/rules/*.txt
        try {
            val extDir = File(EXTERNAL_RULES_DIR)
            if (extDir.isDirectory) {
                for (file in extDir.listFiles() ?: emptyArray()) {
                    if (!file.name.endsWith(".txt")) continue
                    file.bufferedReader().use { reader ->
                        parseRules(reader, file.name) { newHosts.add(it) }
                    }
                }
            }
        } catch (_: Exception) {}

        synchronized(hosts) {
            hosts.clear()
            hosts.addAll(newHosts)
        }
        XposedBridge.log("[DailyRacingBlocker] loaded ${hosts.size} domain rules")
    }

    private fun parseRules(reader: BufferedReader, source: String, onDomain: (String) -> Unit) {
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
