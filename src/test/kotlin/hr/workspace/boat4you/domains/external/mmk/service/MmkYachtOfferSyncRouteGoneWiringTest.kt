package hr.workspace.boat4you.domains.external.mmk.service

import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.ExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferExtraRepository
import hr.workspace.boat4you.domains.catalouge.jpa.OfferRepository
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.catalouge.services.ExternalSystemService
import hr.workspace.boat4you.domains.catalouge.services.LocationQueryingService
import hr.workspace.boat4you.domains.external.mmk.model.MmkDateTimeWrapper
import hr.workspace.boat4you.domains.external.service.ExternalMappingService
import hr.workspace.boat4you.domains.external.service.YachtSyncMutex
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import java.util.Optional

/**
 * Which MMK answers may judge routes (review 3.11.2026): only a call that asked for whole yachts. The search warm asks
 * by marina, country and area, so its answer carries only the searched bases and must never mark the others gone.
 */
class MmkYachtOfferSyncRouteGoneWiringTest {
    private val mmk = ExternalSystem().apply { id = 1 }
    private val yacht = Yacht().apply { id = 18643 }
    private val routeGone = mock(MmkRouteGone::class.java)
    private val systems = mock(ExternalSystemService::class.java)
    private val mappings = mock(ExternalMappingService::class.java)
    private val yachts = mock(YachtRepository::class.java)

    /** Runs the per-yacht block as the advisory lock would when it is free. */
    private val mutex =
        mock(YachtSyncMutex::class.java) { inv ->
            if (inv.method.name == "runExclusiveYachtWrite") {
                @Suppress("UNCHECKED_CAST")
                (inv.arguments[1] as () -> Unit)()
                true
            } else {
                Mockito.RETURNS_DEFAULTS.answer(inv)
            }
        }

    private val service =
        MmkYachtOfferSyncService(
            systems,
            mappings,
            mock(OfferRepository::class.java),
            mock(LocationQueryingService::class.java),
            yachts,
            mock(ExtraRepository::class.java),
            mock(OfferExtraRepository::class.java),
            mutex,
            routeGone,
        )

    private val answer =
        listOf(
            mock(org.openapitools.client.mmk.model.Offer::class.java).also {
                `when`(it.yachtId).thenReturn(7622L)
                `when`(it.dateFrom).thenReturn(MmkDateTimeWrapper("2027-07-03 17:00:00"))
                `when`(it.dateTo).thenReturn(MmkDateTimeWrapper("2027-07-10 09:00:00"))
                `when`(it.startBaseId).thenReturn(1L)
                `when`(it.endBaseId).thenReturn(1L)
                `when`(it.product).thenReturn("Bareboat")
            },
        )

    init {
        `when`(systems.findById(1L)).thenReturn(mmk)
        `when`(yachts.findByExternalIdsAndExternalSystemId(listOf(7622L), 1L)).thenReturn(listOf(yacht))
        `when`(yachts.findById(18643L)).thenReturn(Optional.of(yacht))
        `when`(mappings.findAllByTypeAndExternalSystemAndExternalIdIn("Yacht", 1, listOf(7622L)))
            .thenReturn(listOf(ExternalMapping(7622L, 18643L, "Yacht", mmk, null)))
    }

    private fun judgedFrom() = mockingDetails(routeGone).invocations.filter { it.method.name == "apply" }.map { it.arguments[3] }

    @Test
    fun `an answer for whole yachts judges the routes it did not carry`() {
        service.syncOffers(answer, judgeRoutes = true)
        judgedFrom() shouldBe listOf("per-yacht")
    }

    @Test
    fun `a marina-filtered answer, the default, never judges`() {
        service.syncOffers(answer)
        judgedFrom() shouldBe emptyList()
    }
}
