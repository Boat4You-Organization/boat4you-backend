package hr.workspace.boat4you.domains.external.service

import com.zaxxer.hikari.HikariDataSource
import hr.workspace.boat4you.domains.catalouge.enums.EntryType
import hr.workspace.boat4you.domains.catalouge.jpa.Agency
import hr.workspace.boat4you.domains.catalouge.jpa.AgencySource
import hr.workspace.boat4you.domains.catalouge.jpa.ExternalSystem
import hr.workspace.boat4you.domains.catalouge.jpa.Yacht
import hr.workspace.boat4you.domains.catalouge.jpa.YachtRepository
import hr.workspace.boat4you.domains.external.enums.ExternalSystemEnum
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.mmk.service.MmkYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationService
import hr.workspace.boat4you.domains.external.nausys.service.NauSysYachtOfferIntegrationServiceAsync
import hr.workspace.boat4you.domains.external.sync.jpa.ExternalMapping
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.Optional
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the TX-HIKARI P1 contract of the per-yacht warm
 * (`ExternalSyncService.syncYachtOffers(yachtId, ...)`): the partner call
 * must run with NO ambient transaction and with exactly ONE pooled
 * connection checked out (the advisory-lock session), so Postgres'
 * idle_in_transaction_session_timeout (180 s role setting) can never kill it.
 *
 * `@Transactional(NOT_SUPPORTED)` is mandatory: @DataJpaTest's own test
 * transaction would otherwise wrap the call and mask the assertion.
 *
 * Flyway is disabled: every repository that touches a table is mocked here,
 * the only real SQL is the advisory lock (`pg_try_advisory_lock`), and the
 * migration chain contains prod-data steps (V9_18 region ids) that cannot
 * replay on an empty container database.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none"])
@Import(ExternalSyncService::class, YachtSyncMutex::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExternalSyncServiceTxBoundaryTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> =
            PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
                withDatabaseName("boat4you_db")
                withInitScript("init/00_roles.sql")
            }

        private const val YACHT_ID = 4242L
        private const val EXTERNAL_YACHT_ID = 777L
        private val FROM = LocalDate.of(2027, 5, 1)
        private val TO = LocalDate.of(2027, 5, 8)
    }

    @Autowired
    private lateinit var externalSyncService: ExternalSyncService

    @Autowired
    private lateinit var dataSource: DataSource

    @MockitoBean
    private lateinit var yachtRepository: YachtRepository

    @MockitoBean
    private lateinit var externalMappingService: ExternalMappingService

    @MockitoBean
    private lateinit var serviceCallCacheService: ServiceCallCacheService

    @MockitoBean
    private lateinit var mmkYachtOfferIntegrationService: MmkYachtOfferIntegrationService

    @MockitoBean
    private lateinit var nauSysYachtOfferIntegrationService: NauSysYachtOfferIntegrationService

    @MockitoBean
    private lateinit var mmkYachtOfferIntegrationServiceAsync: MmkYachtOfferIntegrationServiceAsync

    @MockitoBean
    private lateinit var nauSysYachtOfferIntegrationServiceAsync: NauSysYachtOfferIntegrationServiceAsync

    // Mockito's any() returns null, which Kotlin rejects for non-null params
    // (LocalDate) at the call site; the generic indirection defers the check.
    private fun <T> anyK(): T = any()

    private fun activeConnections(): Int = (dataSource as HikariDataSource).hikariPoolMXBean.activeConnections

    private fun stubYacht(externalSystemId: Int?): Yacht {
        val externalSystem = externalSystemId?.let { ExternalSystem().apply { id = it } }
        val agency =
            Agency().apply {
                id = 9L
                if (externalSystem != null) {
                    agencySources =
                        mutableSetOf(
                            AgencySource().apply {
                                this.externalSystem = externalSystem
                                primary = true
                                externalId = 55L
                            },
                        )
                }
            }
        val yacht =
            Yacht().apply {
                id = YACHT_ID
                entryType = EntryType.EXTERNAL
                this.agency = agency
            }
        `when`(yachtRepository.findById(YACHT_ID)).thenReturn(Optional.of(yacht))
        if (externalSystem != null) {
            `when`(externalMappingService.findBySystemIdAndExternalSystemAndType(YACHT_ID, externalSystem, "Yacht"))
                .thenReturn(ExternalMapping(EXTERNAL_YACHT_ID, YACHT_ID, "Yacht", externalSystem, null))
        }
        return yacht
    }

    @Test
    fun `partner call runs outside any transaction with only the advisory-lock connection held`() {
        `when`(serviceCallCacheService.shouldCallOffer(YACHT_ID, FROM, TO)).thenReturn(true)
        stubYacht(ExternalSystemEnum.MMK.value)

        var observedTxActive: Boolean? = null
        var observedActive: Int? = null
        doAnswer {
            observedTxActive = TransactionSynchronizationManager.isActualTransactionActive()
            observedActive = activeConnections()
            null
        }.`when`(mmkYachtOfferIntegrationService).syncOffersForYachtIdAndDateRage(EXTERNAL_YACHT_ID, FROM, TO)

        assertEquals(0, activeConnections(), "precondition: pool idle before the warm")

        externalSyncService.syncYachtOffers(YACHT_ID, FROM, TO)

        assertEquals(false, observedTxActive, "partner call must not see an ambient transaction")
        assertEquals(1, observedActive, "only the advisory-lock session may be checked out during the partner call")
        verify(mmkYachtOfferIntegrationService).syncOffersForYachtIdAndDateRage(EXTERNAL_YACHT_ID, FROM, TO)
        verify(nauSysYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(serviceCallCacheService).saveOfferSync(YACHT_ID, FROM, TO)
        assertEquals(0, activeConnections(), "advisory-lock connection returned to the pool")
    }

    @Test
    fun `nausys yacht is routed to the nausys integration service`() {
        `when`(serviceCallCacheService.shouldCallOffer(YACHT_ID, FROM, TO)).thenReturn(true)
        stubYacht(ExternalSystemEnum.NAUSYS.value)

        var observedTxActive: Boolean? = null
        doAnswer {
            observedTxActive = TransactionSynchronizationManager.isActualTransactionActive()
            null
        }.`when`(nauSysYachtOfferIntegrationService).syncOffersForYachtIdAndDateRage(EXTERNAL_YACHT_ID, FROM, TO)

        externalSyncService.syncYachtOffers(YACHT_ID, FROM, TO)

        assertEquals(false, observedTxActive)
        verify(nauSysYachtOfferIntegrationService).syncOffersForYachtIdAndDateRage(EXTERNAL_YACHT_ID, FROM, TO)
        verify(mmkYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(serviceCallCacheService).saveOfferSync(YACHT_ID, FROM, TO)
    }

    @Test
    fun `missing agency chain skips the partner call and writes no cache marker`() {
        `when`(serviceCallCacheService.shouldCallOffer(YACHT_ID, FROM, TO)).thenReturn(true)
        stubYacht(externalSystemId = null)

        externalSyncService.syncYachtOffers(YACHT_ID, FROM, TO)

        verify(mmkYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(nauSysYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(serviceCallCacheService, never()).saveOfferSync(anyLong(), anyK(), anyK())
        assertEquals(0, activeConnections())
    }

    @Test
    fun `fresh cache marker short-circuits before touching the database`() {
        `when`(serviceCallCacheService.shouldCallOffer(YACHT_ID, FROM, TO)).thenReturn(false)

        externalSyncService.syncYachtOffers(YACHT_ID, FROM, TO)

        verify(yachtRepository, never()).findById(anyLong())
        verify(mmkYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(serviceCallCacheService, never()).saveOfferSync(anyLong(), anyK(), anyK())
    }

    @Test
    fun `resolve failure is logged and swallowed, no partner call`() {
        `when`(serviceCallCacheService.shouldCallOffer(YACHT_ID, FROM, TO)).thenReturn(true)
        `when`(yachtRepository.findById(YACHT_ID)).thenReturn(Optional.empty())

        externalSyncService.syncYachtOffers(YACHT_ID, FROM, TO)

        verify(mmkYachtOfferIntegrationService, never()).syncOffersForYachtIdAndDateRage(anyLong(), anyK(), anyK())
        verify(serviceCallCacheService, never()).saveOfferSync(anyLong(), anyK(), anyK())
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertTrue(activeConnections() == 0)
    }
}
