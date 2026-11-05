package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.branding.BrandRegistry
import hr.workspace.boat4you.domains.pr.dto.PrStudyRequest
import hr.workspace.boat4you.domains.pr.jpa.PrStudyRepository
import hr.workspace.boat4you.domains.pr.jpa.PrStudyStatus
import hr.workspace.boat4you.domains.pr.service.PrException
import hr.workspace.boat4you.domains.pr.service.PrStudyService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import java.time.LocalDate

/** Studies (one data study per number, owned by one of the 7 sites) against the real V9_121 schema. */
@Import(PrStudyService::class, BrandRegistry::class)
class PrStudyServiceTest : PrJpaTest() {
    @Autowired private lateinit var service: PrStudyService

    @Autowired private lateinit var studies: PrStudyRepository

    @Autowired private lateinit var brands: BrandRegistry

    private fun request(
        number: Int? = 1,
        siteId: String? = "catamaran-croatia",
        title: String? = "Charter prices in Croatia rose 12% this summer",
    ) = PrStudyRequest(number = number, siteId = siteId, title = title)

    private fun PrException.shouldBeError(
        status: HttpStatus,
        code: String,
    ) {
        this.status shouldBe status
        this.code shouldBe code
    }

    @Test
    fun createStudyWithKnownSite() {
        val dto =
            service.create(
                PrStudyRequest(
                    number = 7,
                    siteId = " Catamaran-Croatia ",
                    title = "  Charter prices in Croatia rose 12% this summer ",
                    pageUrl = "https://www.catamaran-croatia-charter.com/press/charter-prices-2026",
                    publishedOn = LocalDate.of(2026, 11, 4),
                    notes = "  ",
                ),
            )

        dto.number shouldBe 7
        dto.siteId shouldBe "catamaran-croatia"
        dto.title shouldBe "Charter prices in Croatia rose 12% this summer"
        dto.status shouldBe PrStudyStatus.DRAFT
        dto.publishedOn shouldBe LocalDate.of(2026, 11, 4)
        dto.notes shouldBe null
        val saved = studies.findById(dto.id).get()
        saved.siteId shouldBe "catamaran-croatia"
        saved.pageUrl shouldBe "https://www.catamaran-croatia-charter.com/press/charter-prices-2026"
    }

    @Test
    fun everyRegistrySiteIsAccepted() {
        brands.byId.keys shouldHaveSize 7
        brands.byId.keys.forEachIndexed { i, site -> service.create(request(number = i + 1, siteId = site)).siteId shouldBe site }
    }

    @Test
    fun unknownSiteIsRejected() {
        shouldThrow<PrException> { service.create(request(siteId = "boat4you.com")) }.shouldBeError(HttpStatus.BAD_REQUEST, "SITE_UNKNOWN")
        shouldThrow<PrException> { service.create(request(siteId = null)) }.shouldBeError(HttpStatus.BAD_REQUEST, "SITE_UNKNOWN")
        val study = service.create(request())
        shouldThrow<PrException> { service.update(study.id, PrStudyRequest(siteId = "tripadvisor")) }.shouldBeError(HttpStatus.BAD_REQUEST, "SITE_UNKNOWN")
        studies.findById(study.id).get().siteId shouldBe "catamaran-croatia"
    }

    @Test
    fun duplicateStudyNumberIs409() {
        val first = service.create(request(number = 3))
        shouldThrow<PrException> { service.create(request(number = 3, siteId = "boat4you")) }.shouldBeError(HttpStatus.CONFLICT, "STUDY_NUMBER_EXISTS")

        val second = service.create(request(number = 4))
        shouldThrow<PrException> { service.update(second.id, PrStudyRequest(number = 3)) }.shouldBeError(HttpStatus.CONFLICT, "STUDY_NUMBER_EXISTS")
        // Keeping its own number is not a clash.
        service.update(first.id, PrStudyRequest(number = 3, title = "Renamed")).title shouldBe "Renamed"
        studies.findAll().mapNotNull { it.number }.sorted() shouldBe listOf(3, 4)
    }

    @Test
    fun tripadvisorInTitleIsRejected() {
        listOf("Better than TripAdvisor: real charter prices", "What trip advisor sites miss").forEach {
            shouldThrow<PrException> { service.create(request(title = it)) }.shouldBeError(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        }
        studies.count() shouldBe 0
        val study = service.create(request())
        shouldThrow<PrException> { service.update(study.id, PrStudyRequest(title = "Our data vs Trip-Advisor")) }
            .shouldBeError(HttpStatus.BAD_REQUEST, "TRIPADVISOR_FORBIDDEN")
        studies.findById(study.id).get().title shouldBe "Charter prices in Croatia rose 12% this summer"
    }

    @Test
    fun numberAndTitleAreRequiredOnCreate() {
        shouldThrow<PrException> { service.create(request(number = null)) }.shouldBeError(HttpStatus.BAD_REQUEST, "STUDY_NUMBER_INVALID")
        shouldThrow<PrException> { service.create(request(number = 0)) }.shouldBeError(HttpStatus.BAD_REQUEST, "STUDY_NUMBER_INVALID")
        shouldThrow<PrException> { service.create(request(title = "  ")) }.shouldBeError(HttpStatus.BAD_REQUEST, "TITLE_REQUIRED")
        studies.count() shouldBe 0
    }

    @Test
    fun updateChangesOnlySentFields() {
        val study = service.create(request(number = 2).copy(pageUrl = "https://www.boat4you.com/press/x", notes = "Keep"))

        val dto = service.update(study.id, PrStudyRequest(status = PrStudyStatus.LIVE, publishedOn = LocalDate.of(2026, 11, 5), pageUrl = " "))

        dto.status shouldBe PrStudyStatus.LIVE
        dto.publishedOn shouldBe LocalDate.of(2026, 11, 5)
        dto.pageUrl shouldBe null
        dto.notes shouldBe "Keep"
        dto.number shouldBe 2
        dto.siteId shouldBe "catamaran-croatia"
        dto.title shouldBe "Charter prices in Croatia rose 12% this summer"
        studies.findById(study.id).get().status shouldBe PrStudyStatus.LIVE
    }

    @Test
    fun unknownStudyIs404() {
        shouldThrow<PrException> { service.update(99, PrStudyRequest(title = "x")) }.shouldBeError(HttpStatus.NOT_FOUND, "STUDY_NOT_FOUND")
    }

    @Test
    fun listIsOrderedByNumber() {
        listOf(12, 3, 7).forEach { service.create(request(number = it)) }

        service.list().map { it.number } shouldBe listOf(3, 7, 12)
    }
}
