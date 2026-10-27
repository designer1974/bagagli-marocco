package com.bagaglimarocco.autista

import com.bagaglimarocco.autista.data.Repository
import com.bagaglimarocco.autista.data.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTest {
    @Test
    fun codiceScrittoAManoVieneNormalizzato() {
        assertEquals("BM-26-0412", Repository.normalize("  bm 26 0412 "))
    }

    @Test
    fun ogniBagaglioHaUnaFermataValida() {
        val stopIds = SampleData.stops.map { it.id }.toSet()
        assertTrue(SampleData.bags.all { it.stopId in stopIds })
        assertEquals(SampleData.bags.size, SampleData.bags.map { it.code }.toSet().size)
    }
}
