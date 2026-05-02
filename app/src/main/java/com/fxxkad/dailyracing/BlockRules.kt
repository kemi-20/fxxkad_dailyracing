package com.fxxkad.dailyracing

import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    // User can drop .txt files here to add or override rules at runtime.
    private const val EXTERNAL_RULES_DIR = "/sdcard/DailyRacingBlocker/rules"

    private val hosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Call once after a Context is available to reload all rule files. */
    fun loadRules(context: android.content.Context) {
        val moduleContext = try {
            context.createPackageContext("com.fxxkad.dailyracing",
                android.content.Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Exception) { null }

        val newHosts = mutableSetOf<String>()

        // 1. Built-in rules from module assets/rules/*.txt
        if (moduleContext != null) {
            try {
                for (file in moduleContext.assets.list("rules") ?: emptyArray()) {
                    if (!file.endsWith(".txt")) continue
                    moduleContext.assets.open("rules/$file").use { stream ->
                        parseRules(stream.reader().buffered(), file) { newHosts.add(it) }
                    }
                }
            } catch (_: Exception) {}
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
            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) return@forEachLine
            // Skip allowlist rules
            if (trimmed.startsWith("@@")) return@forEachLine

            val domain = extractDomain(trimmed)
            if (domain.isNotEmpty()) {
                onDomain(domain.lowercase())
            }
        }
    }

    private fun extractDomain(line: String): String {
        // Hosts-file style: "0.0.0.0 example.com"
        val hostsMatch = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+(.+)").find(line)
        if (hostsMatch != null) {
            return hostsMatch.groupValues[1].trim()
        }

        // Adblock style: "||example.com^"
        // Strip leading || and trailing ^
        var domain = line.trimStart('|').trimEnd('^')

        // Remove protocol if present
        domain = domain.removePrefix("http://").removePrefix("https://")

        // Remove path, port, wildcards
        domain = domain.split("/", "#", " ", ":")[0]

        // Remove leading wildcards
        domain = domain.trimStart('*', '.')

        return domain.trim()
    }

    fun shouldBlock(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        val list = synchronized(hosts) { hosts.toList() }
        return list.any { normalized == it || normalized.endsWith(".$it") }
    }
}
