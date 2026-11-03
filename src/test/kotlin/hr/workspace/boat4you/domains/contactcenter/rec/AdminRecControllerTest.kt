package hr.workspace.boat4you.domains.contactcenter.rec

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.ConversionService
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** The recording switches in the admin (P6 R1.6): SYSTEM_ADMIN only, a known site, shares 0 to 100, the change is stored. */
@SpringJUnitConfig(AdminRecControllerTest.Config::class)
class AdminRecControllerTest {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")

            // The store mock still carries its @Value Duration field: Boot's converters, as in the application.
            @Bean
            @JvmStatic
            fun conversionService(): ConversionService = ApplicationConversionService.getSharedInstance()
        }

        @Bean fun updates(): MutableList<RecSiteSetting> = mutableListOf()

        @Bean
        fun store(updates: MutableList<RecSiteSetting>): RecSettingsStore =
            mock(RecSettingsStore::class.java) { inv ->
                when (inv.method.name) {
                    "all" -> listOf(RecSiteSetting("boat4you", true, 100, 25))
                    "forSite" -> updates.lastOrNull() ?: RecSettingsStore.defaults(inv.arguments[0] as String)
                    "update" -> updates.add(inv.arguments[0] as RecSiteSetting).let { null }
                    else -> null
                }
            }

        @Bean fun adminRecController(store: RecSettingsStore) = AdminRecController(store, mock(RecQuery::class.java), RecFiles("/nonexistent"))
    }

    @Autowired private lateinit var controller: AdminRecController

    @Autowired private lateinit var store: RecSettingsStore

    @Autowired private lateinit var updates: MutableList<RecSiteSetting>

    private val mvc by lazy { MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build() }

    private fun admin() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mario@example.com", null, listOf(SimpleGrantedAuthority("SYSTEM_ADMIN")))
    }

    private fun put(
        site: String,
        body: String,
    ) = mvc.put("/admin/cc/rec/settings/$site") {
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
        updates.clear()
        reset(store)
    }

    @Test
    fun `an admin reads every site and switches one, a wrong site or share is 400`() {
        admin()
        mvc.get("/admin/cc/rec/settings").andExpect {
            status { isOk() }
            jsonPath("$[0].siteId") { value("boat4you") }
            jsonPath("$[0].mobilePct") { value(25) }
        }
        put("catamaran-greece", """{"enabled":true,"samplePct":100,"mobilePct":25}""").andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(true) }
        }
        updates shouldBe listOf(RecSiteSetting("catamaran-greece", true, 100, 25))
        put("evil", """{"enabled":true,"samplePct":100,"mobilePct":100}""").andExpect { status { isBadRequest() } }
        put("boat4you", """{"enabled":true,"samplePct":101,"mobilePct":100}""").andExpect { status { isBadRequest() } }
        put("boat4you", """{"samplePct":100,"mobilePct":100}""").andExpect { status { isBadRequest() } }
        updates.size shouldBe 1
    }

    @Test
    fun `anyone but a system admin gets 403 and nothing is read or stored`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, listOf(SimpleGrantedAuthority("MANAGER")))
        mvc.get("/admin/cc/rec/settings").andExpect { status { isForbidden() } }
        put("boat4you", """{"enabled":true,"samplePct":100,"mobilePct":100}""").andExpect { status { isForbidden() } }
        verifyNoInteractions(store)
    }
}
