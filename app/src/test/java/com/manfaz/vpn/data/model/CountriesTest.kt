package com.manfaz.vpn.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CountriesTest {
    @Test
    fun detectsNewCountryArtworkTargets() {
        assertEquals("RO", Countries.detect("Bucharest 01").iso)
        assertEquals("BG", Countries.detect("Sofia Bulgaria").iso)
        assertEquals("AU", Countries.detect("Sydney Australia").iso)
        assertEquals("JP", Countries.detect("Tokyo Japan").iso)
        assertEquals("AE", Countries.detect("Dubai UAE").iso)
        assertEquals("AZ", Countries.detect("Baku Azerbaijan").iso)
        assertEquals("SA", Countries.detect("Riyadh Saudi Arabia").iso)
    }
}
