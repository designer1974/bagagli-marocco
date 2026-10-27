package com.bagaglimarocco.autista

import com.bagaglimarocco.autista.data.Codes
import com.bagaglimarocco.autista.data.SampleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTest {
    @Test
    fun codiceScrittoAManoVieneNormalizzato() {
        assertEquals("BM-26-0412", Codes.normalizeBag("  bm 26 0412 "))
    }

    @Test
    fun impronta_del_codice_uguale_al_server() {
        // Valore calcolato come nel server Go: sha256("1:1234")
        assertEquals("e3ebee0ac16ab73da385eae0743463bbe5917c4ead6389d624ce91ae5360cb5f", Codes.recipientCodeHash(1, "1234"))
    }

    @Test
    fun ogniBagaglioHaUnaFermataValida() {
        val stopIds = SampleData.stops.map { it.id }.toSet()
        assertTrue(SampleData.bags.all { it.stopId in stopIds })
        assertEquals(SampleData.bags.size, SampleData.bags.map { it.code }.toSet().size)
    }
}
