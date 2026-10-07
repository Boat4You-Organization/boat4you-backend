package hr.workspace.boat4you.domains.catalouge.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.enums.CurrencyEnum
import hr.workspace.boat4you.domains.catalouge.enums.LanguageEnum
import hr.workspace.boat4you.domains.catalouge.exceptions.YachtNotActiveException
import hr.workspace.boat4you.domains.catalouge.services.OfferQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtQueryingService
import hr.workspace.boat4you.domains.catalouge.services.YachtTwinCanonicalService
import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessor
import hr.workspace.boat4you.domains.external.service.ExternalSyncService
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * 7.10.2026, contract with the web sites (b4y + 6 sisters): GET /public/yachts/{idOrSlug} of a retired boat keeps
 * answering 400 {"code":1502,"message":"Yacht is not active"} and adds successorSlug / successorId when the boat page
 * names the live listing of the same boat - for the old slug and for any slug ending in the old id. Without a
 * successor the two fields are absent, not null. No field name may hint at an agency or partner.
 */
class YachtControllerSuccessorTests {
    private val yachtQueryingService: YachtQueryingService = mock(YachtQueryingService::class.java)
    private val twins: YachtTwinCanonicalService = mock(YachtTwinCanonicalService::class.java)

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(
                YachtController(
                    yachtQueryingService,
                    mock(OfferQueryingService::class.java),
                    mock(ExternalSyncService::class.java),
                    mock(UserRepository::class.java),
                    twins,
                ),
            ).setControllerAdvice(ApiErrorHandler())
            .build()

    private val currency = CurrencyEnum.getCurrency(null, null)
    private val language = LanguageEnum.getLanguage(null, null)

    @BeforeEach
    fun anonymous() {
        SecurityContextHolder.clearContext()
        // no twin group: the requested id is served as is
        listOf(4066L, 4067L).forEach { `when`(twins.resolve(it)).thenReturn(it) }
        doThrow(YachtNotActiveException(YachtSuccessor(11681, "lagoon-42-masterpiece-11681")))
            .`when`(yachtQueryingService)
            .getYacht(4066L, null, null, currency, language)
        doThrow(YachtNotActiveException()).`when`(yachtQueryingService).getYacht(4067L, null, null, currency, language)
    }

    @Test
    fun `the old slug, and any slug ending in the old id, answer 1502 with the successor`() {
        listOf("lagoon-bnteau-lagoon-42-4-2-cab-masterpiece-4066", "lagoon-42-masterpiece-4066", "wrong-slug-4066", "4066")
            .forEach { slug ->
                mvc.get("/public/yachts/$slug").andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(1502) }
                    jsonPath("$.message") { value("Yacht is not active") }
                    jsonPath("$.successorSlug") { value("lagoon-42-masterpiece-11681") }
                    jsonPath("$.successorId") { value(11681) }
                }
            }
    }

    @Test
    fun `no successor - the plain 1502 body, without the fields`() {
        val body =
            mvc
                .get("/public/yachts/sun-odyssey-380-princess-anja-4067")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(1502) }
                    jsonPath("$.successorSlug") { doesNotExist() }
                    jsonPath("$.successorId") { doesNotExist() }
                }.andReturn()
                .response.contentAsString
        ObjectMapper().readTree(body).fieldNames().asSequence().toList() shouldContainExactly listOf("code", "message")
    }

    @Test
    fun `a 1502 never names the boat asked for - no redirect to itself`() {
        // review 7.10.: a retired twin copy (4066) served for the live one (11681) would name 11681 as its successor,
        // and the site would redirect 11681 to itself forever; the twin pick skips retired copies, this keeps it so
        `when`(twins.resolve(11681L)).thenReturn(4066L)
        val body =
            mvc
                .get("/public/yachts/lagoon-42-masterpiece-11681")
                .andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value(1502) }
                }.andReturn()
                .response.contentAsString
        ObjectMapper().readTree(body).fieldNames().asSequence().toList() shouldContainExactly listOf("code", "message")
    }

    @Test
    fun `the public body names no agency, partner or source`() {
        val body = mvc.get("/public/yachts/masterpiece-4066").andReturn().response.contentAsString
        val fields = ObjectMapper().readTree(body).fieldNames().asSequence().toList()
        fields shouldContainExactly listOf("code", "message", "successorSlug", "successorId")
        fields.filter { Regex("agency|external|partner|company|source|mmk|nausys|operator", RegexOption.IGNORE_CASE).containsMatchIn(it) } shouldBe
            emptyList()
    }
}
