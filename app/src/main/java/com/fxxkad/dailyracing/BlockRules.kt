package com.fxxkad.dailyracing

object BlockRules {
    const val targetPackage = "com.romielf.mrsc"
    const val zeroAddress = "0.0.0.0"

    val hosts = setOf(
        "adx.adwangmai.com",
        "gdfp.gifshow.com",
        "e.kuaishou.cn",
        "e.kuaishou.com",
        "anythinktech.com",
        "gdt.qq.com",
        "bgg.baidu.com",
        "mobads.baidu.com",
        "e.qq.com",
        "jpush.cn",
        "jpush.io",
        "umengcloud.com",
        "umeng.com",
        "mcc.inf.miui.com",
        "tracking.miui.com",
        "tnc3-aliec2.zijieapi.com",
        "tnc3-alisc1.zijieapi.com",
        "tnc3-bjlgy.zijieapi.com",
        "toblog.ctobsnssdk.com",
        "sf3-fe-tos.pglstatp-toutiao.com",
        "api-access.pangolin-sdk-toutiao.com",
        "api-access.pangolin-sdk-toutiao-b.com",
        "mssdk.volces.com",
        "h.trace.qq.com",
        "dns.weixin.qq.com.cn",
        "szlong.weixin.qq.com",
        "zt.gifshow.com",
        "ulog-sdk.gifshow.com",
        "easytomessage.com"
    )

    fun shouldBlock(host: String?): Boolean {
        val normalized = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        return hosts.any { normalized == it || normalized.endsWith(".$it") }
    }
}
