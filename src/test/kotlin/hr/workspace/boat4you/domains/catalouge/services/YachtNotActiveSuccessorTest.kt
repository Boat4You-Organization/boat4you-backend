package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.common.services.FileSystemService
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.jpa.CountryRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtDetailRepository
import hr.workspace.boat4you.domains.catalouge.jpa.CustomYachtViewRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalBaseRepository
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalReservationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.LocationRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.RegionRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.jpa.YachtTranslationRepository
import hr.workspace.boat4you.domains.catalouge.mapper.OfferMapper
import hr.workspace.boat4you.domains.catalouge.mapper.YachtMapper
import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessor
import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessorLookup
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import jakarta.persistence.Query
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * 7.10.2026: the boat page of a retired boat answers 1502 as before, carrying the live listing of the same boat
 * (yacht_successor). The lookup runs only on that path and fails open.
 */
class YachtNotActiveSuccessorTest {
    private val entityManager: EntityManager = mock(EntityManager::class.java)
    private val yachts: YachtRepository = mock(YachtRepository::class.java)
    private val query: Query = mock(Query::class.java)

    private val service =
        YachtQueryingService(
            entityManager,
            yachts,
            mock(LocationRepository::class.java),
            mock(ExternalReservationRepository::class.java),
            mock(YachtMapper::class.java),
            mock(OfferRepository::class.java),
            mock(CustomYachtViewRepository::class.java),
            mock(CustomYachtDetailRepository::class.java),
            mock(YachtTranslationRepository::class.java),
            mock(OfferMapper::class.java),
            mock(FileSystemService::class.java),
            mock(ExchangeRateCalculationService::class.java),
            mock(YachtExtraRepository::class.java),
            mock(ExternalBaseRepository::class.java),
            mock(RegionRepository::class.java),
            mock(CountryRepository::class.java),
            PassThroughHeavyQueries,
        )

    private fun stored(
        id: Long,
        active: Boolean,
    ) = `when`(yachts.findById(id)).thenReturn(
        Optional.of(
            Yacht().apply {
                this.id = id
                sysActive = active
                entryType = EntryType.EXTERNAL
            },
        ),
    )

    private fun notActive(id: Long): YachtNotActiveException = assertFailsWith<YachtNotActiveException> { service.getYacht(id, null, null, null, LanguageEnum.EN) }

    @Test
    fun `a retired boat answers 1502 with the live listing of the same boat`() {
        stored(4066, active = false)
        `when`(entityManager.createNativeQuery(YachtSuccessorLookup.SQL)).thenReturn(query)
        `when`(query.setParameter("id", 4066L)).thenReturn(query)
        `when`(query.resultList).thenReturn(listOf(arrayOf<Any?>(11681L, "Lagoon", "Lagoon 42", "MASTERPIECE")))

        notActive(4066).successor shouldBe YachtSuccessor(11681, "lagoon-42-masterpiece-11681")
    }

    @Test
    fun `no successor named - the plain 1502`() {
        stored(4067, active = false)
        `when`(entityManager.createNativeQuery(YachtSuccessorLookup.SQL)).thenReturn(query)
        `when`(query.setParameter("id", 4067L)).thenReturn(query)
        `when`(query.resultList).thenReturn(emptyList<Any>())

        notActive(4067).successor shouldBe null
    }

    @Test
    fun `a failing lookup never changes the answer - the plain 1502`() {
        // e.g. the API started before V9_73 created the table
        stored(4068, active = false)
        `when`(entityManager.createNativeQuery(YachtSuccessorLookup.SQL)).thenThrow(PersistenceException("relation \"yacht_successor\" does not exist"))

        notActive(4068).successor shouldBe null
    }

    @Test
    fun `an active boat never reads the successor table`() {
        stored(11681, active = true)
        // the agency check stops the call right after the active check (no agency on the stub)
        runCatching { service.getYacht(11681, null, null, null, LanguageEnum.EN) }
        verify(entityManager, never()).createNativeQuery(anyString())
    }
}
