package com.fxxkad.dailyracing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DomainRulesTest {
    @Test
    fun parsesSupportedRuleFormatsAndRejectsUnsafeInput() {
        assertEquals(listOf("gdt.qq.com"), DomainRules.parseLine("||gdt.qq.com^"))
        assertEquals(listOf("gdt.qq.com"), DomainRules.parseLine("0.0.0.0 gdt.qq.com"))
        assertEquals(
            listOf("gdt.qq.com", "e.qq.com"),
            DomainRules.parseLine("127.0.0.1 gdt.qq.com e.qq.com")
        )
        assertEquals(listOf("xn--fsqu00a.xn--0zwm56d"), DomainRules.parseLine("例子.测试"))
        assertTrue(DomainRules.parseLine("@@||gdt.qq.com^").isEmpty())
        assertTrue(DomainRules.parseLine("||*.qq.com^").isEmpty())
        assertTrue(DomainRules.parseLine("com").isEmpty())
        assertTrue(DomainRules.parseLine("https://example.com/path\$third-party").contains("example.com"))
    }

    @Test
    fun matchesExactDomainsAndSubdomainsOnly() {
        val rules = setOf("gdt.qq.com", "example.com")
        assertTrue(DomainRules.matches(rules, "gdt.qq.com"))
        assertTrue(DomainRules.matches(rules, "A.B.GDT.QQ.COM."))
        assertFalse(DomainRules.matches(rules, "notgdt.qq.com"))
        assertFalse(DomainRules.matches(rules, "qq.com"))
        assertFalse(DomainRules.matches(rules, null))
    }

    @Test
    fun builtInListMatchesBundledAsset() {
        val asset = File("src/main/assets/rules/default.txt")
        val parsed = asset.useLines { lines -> lines.flatMap(DomainRules::parseLine).toSet() }
        assertEquals(parsed, DomainRules.builtInDomains)
    }
}
