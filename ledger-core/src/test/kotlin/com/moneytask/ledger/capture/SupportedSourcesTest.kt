package com.moneytask.ledger.capture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportedSourcesTest {

    @Test
    fun `known wallets and banks are allowed`() {
        assertTrue(SupportedSources.isSupported("com.tencent.mm"))
        assertTrue(SupportedSources.isSupported("com.eg.android.AlipayGphone"))
        assertTrue(SupportedSources.isSupported("com.cmbchina.ccd.pluto.cmbActivity"))
        assertTrue(SupportedSources.isSupported("com.sankuai.meituan"))
    }

    @Test
    fun `unknown packages and null are rejected`() {
        assertFalse(SupportedSources.isSupported("com.example.unrelated"))
        assertFalse(SupportedSources.isSupported(""))
        assertFalse(SupportedSources.isSupported(null))
    }

    @Test
    fun `withExtra appends without mutating default set`() {
        val extended = SupportedSources.withExtra(setOf("com.custom.bankx"))
        assertTrue("com.custom.bankx" in extended)
        assertTrue("com.tencent.mm" in extended)           // 默认项保留
        assertTrue("com.custom.bankx" !in SupportedSources.packages) // 默认集未变
    }
}
