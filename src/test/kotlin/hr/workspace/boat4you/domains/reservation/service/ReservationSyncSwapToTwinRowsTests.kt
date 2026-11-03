package hr.workspace.boat4you.domains.reservation.service

import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.mmk.client.MmkRetryableClient
import hr.workspace.boat4you.domains.external.nausys.client.NauSysRetryableClient
import hr.workspace.boat4you.domains.external.nausys.config.NauSysAuthProvider
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMappingRepository
import hr.workspace.boat4you.domains.reservation.jpa.Reservation
import hr.workspace.boat4you.domains.reservation.jpa.ReservationFlow
import hr.workspace.boat4you.domains.reservation.jpa.ReservationRepository
import hr.workspace.boat4you.domains.reservation.jpa.ReservationYachtSwapAuditRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.openapitools.client.nausys.model.RestAuthentication
import org.openapitools.client.nausys.model.RestYachtReservation
import org.openapitools.client.nausys.model.RestYachtReservationsRequest
import org.springframework.dao.IncorrectResultSizeDataAccessException

/**
 * The swap check when the partner moved a reservation to a boat that changed agency (review 3.11.2026): such a boat
 * keeps its NauSys id and two of our rows carry it, the retired one and the active one. The single-row finder threw,
 * the swap was never recorded and the reservation stayed on the old boat.
 */
class ReservationSyncSwapToTwinRowsTests {
    private val nausys = ExternalSystem().apply { id = 2 }
    private val mappings = mock(ExternalMappingRepository::class.java)
    private val yachts = mock(YachtRepository::class.java)
    private val reservations = mock(ReservationRepository::class.java)
    private val partner = mock(NauSysRetryableClient::class.java)
    private val auth = mock(NauSysAuthProvider::class.java)

    private val service =
        ReservationSyncService(
            reservations,
            mappings,
            partner,
            auth,
            mock(MmkRetryableClient::class.java),
            mock(ReservationYachtSwapAuditRepository::class.java),
            mock(ReservationEmailService::class.java),
            yachts,
            autoUpdateEnabled = true,
        )

    private val reservation =
        Reservation().apply {
            id = 1
            externalId = 777
            reservationFlow =
                ReservationFlow().apply {
                    id = 9
                    yacht =
                        Yacht().apply {
                            id = 100
                            agency =
                                Agency().apply {
                                    agencySources =
                                        mutableSetOf(
                                            AgencySource().apply {
                                                primary = true
                                                externalSystem = nausys
                                            },
                                        )
                                }
                        }
                }
        }

    private fun yacht(
        id: Long,
        active: Boolean,
    ) = Yacht().apply {
        this.id = id
        sysActive = active
    }

    private fun mapping(systemId: Long) = ExternalMapping(67732855, systemId, "Yacht", nausys, null)

    private fun partnerMovedTheReservationTo(partnerYachtId: Long) {
        `when`(auth.auth).thenReturn(RestAuthentication())
        `when`(partner.getReservation(RestYachtReservationsRequest(credentials = RestAuthentication(), reservations = listOf(777L))))
            .thenReturn(RestYachtReservation(yachtId = partnerYachtId))
        `when`(mappings.findBySystemIdAndExternalSystemAndType(100, nausys, "Yacht")).thenReturn(ExternalMapping(555, 100, "Yacht", nausys, null))
    }

    @Test
    fun `a swap to a boat with a retired twin moves the reservation to the active row`() {
        partnerMovedTheReservationTo(67732855)
        // what Spring Data does when two rows match a single-result finder
        `when`(mappings.findByExternalIdAndExternalSystemAndType(67732855, nausys, "Yacht")).thenThrow(IncorrectResultSizeDataAccessException(1, 2))
        `when`(mappings.findAllByExternalIdAndExternalSystemAndType(67732855, nausys, "Yacht")).thenReturn(listOf(mapping(2466), mapping(19986)))
        `when`(yachts.findAllById(listOf(2466L, 19986L))).thenReturn(listOf(yacht(2466, active = false), yacht(19986, active = true)))

        service.processReservation(reservation) shouldBe ReservationSyncService.ProcessResult.SWAP_DETECTED
        verify(reservations).updateYachtOnSwap(reservationFlowId = 9, newYachtId = 19986)
    }

    @Test
    fun `a swap to a boat with one row moves the reservation there as before`() {
        partnerMovedTheReservationTo(67732855)
        `when`(mappings.findByExternalIdAndExternalSystemAndType(67732855, nausys, "Yacht")).thenReturn(mapping(19986))
        `when`(mappings.findAllByExternalIdAndExternalSystemAndType(67732855, nausys, "Yacht")).thenReturn(listOf(mapping(19986)))

        service.processReservation(reservation) shouldBe ReservationSyncService.ProcessResult.SWAP_DETECTED
        verify(reservations).updateYachtOnSwap(reservationFlowId = 9, newYachtId = 19986)
    }
}
