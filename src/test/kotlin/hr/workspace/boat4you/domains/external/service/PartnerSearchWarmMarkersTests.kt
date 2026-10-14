package hr.workspace.boat4you.domains.external.service

import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.enums.MethodCacheEnum
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCallCache
import hr.workspace.boat4you.domains.external.sync.jpa.ServiceCallCacheRepository
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** B3: per-partner 3 h search-warm markers live in the YACHT_SEARCH rows under a partner-salted hash. */
class PartnerSearchWarmMarkersTests {
    private val repository: ServiceCallCacheRepository = mock(ServiceCallCacheRepository::class.java)
    private val markers = PartnerSearchWarmMarkers(repository)
    private val legacy = ServiceCallCacheService(repository)

    private val from = LocalDate.of(2027, 6, 5)
    private val to = from.plusWeeks(2)
    private val locations = listOf("r-12", "c-115")

    @Test
    fun `the partner hashes differ from each other and from the combined marker, and ignore location order`() {
        val nausys = PartnerSearchWarmMarkers.hash(ExternalSystemEnum.NAUSYS, from, to, locations)
        val mmk = PartnerSearchWarmMarkers.hash(ExternalSystemEnum.MMK, from, to, locations)
        val combined = legacy.createSyncYachtOffersHashSorted(from, to, locations)
        assertNotEquals(nausys, mmk)
        assertNotEquals(nausys, combined)
        assertNotEquals(mmk, combined)
        assertEquals(nausys, PartnerSearchWarmMarkers.hash(ExternalSystemEnum.NAUSYS, from, to, locations.reversed()))
    }

    @Test
    fun `due without a marker or with one older than 3 h, not due with a younger one`() {
        val hash = PartnerSearchWarmMarkers.hash(ExternalSystemEnum.NAUSYS, from, to, locations)
        assertTrue(markers.isDue(ExternalSystemEnum.NAUSYS, from, to, locations))

        `when`(repository.findByMethodAndHashCode(MethodCacheEnum.YACHT_SEARCH, hash)).thenReturn(Instant.now().minus(Duration.ofHours(2)))
        assertFalse(markers.isDue(ExternalSystemEnum.NAUSYS, from, to, locations))
        assertTrue(markers.isDue(ExternalSystemEnum.MMK, from, to, locations), "the other partner's marker is separate")

        `when`(repository.findByMethodAndHashCode(MethodCacheEnum.YACHT_SEARCH, hash)).thenReturn(Instant.now().minus(Duration.ofMinutes(181)))
        assertTrue(markers.isDue(ExternalSystemEnum.NAUSYS, from, to, locations))
    }

    @Test
    fun `save writes a YACHT_SEARCH row with the partner hash`() {
        markers.save(ExternalSystemEnum.MMK, from, to, locations)

        val captor = ArgumentCaptor.forClass(ServiceCallCache::class.java)
        verify(repository).save(captor.capture())
        assertEquals(MethodCacheEnum.YACHT_SEARCH, captor.value.method)
        assertEquals(PartnerSearchWarmMarkers.hash(ExternalSystemEnum.MMK, from, to, locations), captor.value.hashCode)
    }
}
