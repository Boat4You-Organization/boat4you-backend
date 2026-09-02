package hr.workspace.boat4you.domains.external.nausys

import hr.workspace.boat4you.domains.external.nausys.config.nauSysObjectMapper
import org.openapitools.client.nausys.model.RestFreeYachtList
import org.openapitools.client.nausys.model.RestYachtReservationExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The NauSys RestClient must survive enum strings the spec does not know
 * (P1 of the 429 plan): `INCLUDED_IN_PRICE` killed agency 1981's whole
 * response every night from 28.8.2026.
 */
class NauSysModelDeserializationTests {
    private val mapper = nauSysObjectMapper()

    private fun fixture(calculationType: String) =
        """
        {"status":"OK","freeYachts":[{"yachtId":42,"status":"FREE",
          "additionalExtras":[{"id":1,"extraId":10,"calculationType":"$calculationType","amount":"100"}]}]}
        """.trimIndent()

    @Test
    fun `INCLUDED_IN_PRICE is a known enum value after the spec fix`() {
        val list = mapper.readValue(fixture("INCLUDED_IN_PRICE"), RestFreeYachtList::class.java)
        val extra = list.freeYachts!!.single().additionalExtras!!.single()
        assertEquals(RestYachtReservationExtra.CalculationType.INCLUDED_IN_PRICE, extra.calculationType)
    }

    @Test
    fun `unknown enum string becomes null and the rest of the object survives`() {
        val list = mapper.readValue(fixture("SOMETHING_NEW"), RestFreeYachtList::class.java)
        assertEquals("OK", list.status)
        val yacht = list.freeYachts!!.single()
        assertEquals(42L, yacht.yachtId)
        assertEquals("FREE", yacht.status)
        val extra = yacht.additionalExtras!!.single()
        assertNull(extra.calculationType)
        assertEquals("100", extra.amount)
    }
}
