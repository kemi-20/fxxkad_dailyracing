package com.fxxkad.dailyracing

import java.net.IDN
import java.util.Locale

internal object DomainRules {
    val builtInDomains: Set<String> = setOf(
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
        "sigmob.cn",
        "sigmob.com",
        "adservice.sigmob.cn",
        "jpush.cn",
        "jpush.io",
        "umengcloud.com",
        "umeng.com",
        "ulogs.umeng.com",
        "plbslog.umeng.com",
        "ainfo.umeng.com",
        "msgstat.umengcloud.com",
        "mcc.inf.miui.com",
        "tracking.miui.com",
        "tnc3-aliec2.zijieapi.com",
        "tnc3-alisc1.zijieapi.com",
        "tnc3-bjlgy.zijieapi.com",
        "h.trace.qq.com",
        "dns.weixin.qq.com.cn",
        "szlong.weixin.qq.com",
        "fastappjump-drcn.hispace.hicloud.com",
        "fastappjump-drcn.hispace.dbankcloud.cn",
        "hapjs.org",
        "statres.quickapp.cn",
        "cdn.quickapp.cn",
        "easytomessage.com"
    )

    fun parseLine(line: String): List<String> {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#") || trimmed.startsWith("@@")) {
            return emptyList()
        }

        val withoutOptions = trimmed.substringBefore('$')
        val hostsMatch = HOSTS_LINE.matchEntire(withoutOptions)
        val candidates = if (hostsMatch != null) {
            hostsMatch.groupValues[1].trim().split(WHITESPACE)
        } else {
            listOf(withoutOptions)
        }

        return candidates.mapNotNull(::normalizeRule).distinct()
    }

    fun matches(rules: Set<String>, host: String?): Boolean {
        val normalized = normalizeHost(host) ?: return false
        var candidate = normalized
        while (true) {
            if (candidate in rules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return normalizeDomain(host.trim().trimEnd('.'))
    }

    private fun normalizeRule(raw: String): String? {
        var domain = raw.trim()
            .removePrefix("||")
            .trimStart('|')
            .trimEnd('^')
        if (domain.startsWith("*.")) return null
        domain = domain
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('#')
            .substringBefore(':')
            .trimStart('.')
            .trimEnd('.')
        if (domain.contains('*') || domain.contains('|') || domain.contains('^')) return null
        domain = domain.trim()
        return normalizeDomain(domain)
    }

    private fun normalizeDomain(raw: String): String? {
        if (raw.isEmpty()) return null
        val ascii = try {
            IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (ascii.length > MAX_DOMAIN_LENGTH || '.' !in ascii) return null
        val labels = ascii.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > MAX_LABEL_LENGTH ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }
        ) {
            return null
        }
        return ascii
    }

    private val HOSTS_LINE = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+(.+)$")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_DOMAIN_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
}
