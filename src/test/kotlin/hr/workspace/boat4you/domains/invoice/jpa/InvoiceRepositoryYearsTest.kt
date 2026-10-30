package hr.workspace.boat4you.domains.invoice.jpa

import hr.workspace.boat4you.domains.invoice.enums.InvoiceLanguageEnum
import hr.workspace.boat4you.domains.invoice.enums.InvoiceRecipientType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Admin year tabs (`GET /admin/invoices/years`) on PostgreSQL 18. `invoice.invoice_date` is VARCHAR(255) in prod (V1_27),
 * and Hibernate stores the LocalDate there as text (prod 30.10.2026: all 110 rows `YYYY-MM-DD +00`), so EXTRACT(YEAR ...)
 * on it failed with "function pg_catalog.extract(unknown, character varying) does not exist".
 * Hibernate builds the schema from the entity, whose columnDefinition keeps the prod VARCHAR type.
 */
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.database-platform=hr.workspace.boat4you.domains.catalouge.jpa.SerialIdPostgreSQLDialect",
    ],
)
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvoiceRepositoryYearsTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer<Nothing> = PostgreSQLContainer<Nothing>("postgres:18-alpine").apply { withDatabaseName("boat4you_db") }
    }

    @Autowired private lateinit var invoices: InvoiceRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clean() {
        jdbc.execute("DELETE FROM invoice")
    }

    @Test
    fun `years come from the stored invoice_date text, newest first and distinct`() {
        listOf(LocalDate.of(2026, 4, 4), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 12, 26)).forEach { invoices.save(invoice(it)) }

        invoices.findDistinctYears() shouldBe listOf(2026, 2025)
    }

    @Test
    fun `prod-shaped and blank invoice_date values do not break the tabs`() {
        val ids = listOf(LocalDate.of(2026, 4, 4), LocalDate.of(2024, 1, 1), LocalDate.of(2023, 1, 1)).map { invoices.save(invoice(it)).id!! }
        jdbc.update("UPDATE invoice SET invoice_date = '2024-01-01 +00' WHERE id = ?", ids[1])
        jdbc.update("UPDATE invoice SET invoice_date = '' WHERE id = ?", ids[2])

        invoices.findDistinctYears() shouldBe listOf(2026, 2024)
    }

    @Test
    fun `no invoices means no tabs`() {
        invoices.findDistinctYears() shouldBe emptyList()
    }

    private fun invoice(date: LocalDate) =
        Invoice().apply {
            recipientType = InvoiceRecipientType.COMPANY
            recipientName = "Test d.o.o."
            recipientCity = "Split"
            recipientStreet = "Obala 1"
            recipientZipCode = "21000"
            recipientCountry = "HR"
            recipientVatCode = "HR00000000000"
            invoiceNumber = "100101/${date.year}"
            invoiceDate = date
            invoiceLanguage = InvoiceLanguageEnum.EN
            invoiceItem = "Charter"
            includeVat = false
            vatPercentage = 0f
            priceWithoutVat = BigDecimal("100.00")
            vatAmount = BigDecimal.ZERO
            totalPrice = BigDecimal("100.00")
        }
}
