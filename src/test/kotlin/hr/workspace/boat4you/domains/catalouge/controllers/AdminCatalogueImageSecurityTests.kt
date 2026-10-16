package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.dto.MainImageFixResultDto
import hr.workspace.boat4you.domains.catalouge.services.AdminCatalogueImageService
import hr.workspace.boat4you.domains.catalouge.services.YachtMainImageFixService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The marina-photo admin API and the layout-main repair are SYSTEM_ADMIN only: the real @PreAuthorize through Spring's
 * method security, with the production role setup (no "ROLE_" prefix - SecurityConfiguration.grantedAuthorityDefaults).
 * Any other user - and an anonymous one - gets 403 code 1000 and the service is never called.
 */
@SpringJUnitConfig(AdminCatalogueImageSecurityTests.Config::class)
class AdminCatalogueImageSecurityTests {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean
        fun catalogueImageService(): AdminCatalogueImageService = mock(AdminCatalogueImageService::class.java)

        @Bean
        fun mainImageFixService(): YachtMainImageFixService = mock(YachtMainImageFixService::class.java)

        @Bean
        fun adminCatalogueImageController(
            catalogueImageService: AdminCatalogueImageService,
            mainImageFixService: YachtMainImageFixService,
        ) = AdminCatalogueImageController(catalogueImageService, mainImageFixService)
    }

    @Autowired
    private lateinit var controller: AdminCatalogueImageController

    @Autowired
    private lateinit var catalogueImageService: AdminCatalogueImageService

    @Autowired
    private lateinit var mainImageFixService: YachtMainImageFixService

    private val mvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build()
    }

    @BeforeEach
    fun resetMocks() = reset(catalogueImageService, mainImageFixService)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(vararg roles: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, roles.map { SimpleGrantedAuthority(it) })
    }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/locations/63/images"),
            mvc.multipart("/admin/locations/63/images") {
                file(MockMultipartFile("files", "a.webp", "image/webp", ByteArray(16)))
                param("credit", "Foto: M")
            },
            mvc.post("/admin/locations/63/images/from-url") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"urls":[{"url":"https://marina.hr/a.jpg","credit":"Foto: M"}]}"""
            },
            mvc.put("/admin/catalogue-images/2000000000") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"active":false}"""
            },
            mvc.delete("/admin/catalogue-images/2000000000"),
            mvc.post("/admin/yachts/main-image/fix-layout?dryRun=false"),
        )

    @Test
    fun `a non-admin user gets 403 on every endpoint`() {
        signIn("USER", "MANAGER")
        every().forEach {
            it.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(1000) }
            }
        }
        verifyNoInteractions(catalogueImageService, mainImageFixService)
    }

    @Test
    fun `an anonymous request gets 403`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(catalogueImageService, mainImageFixService)
    }

    @Test
    fun `SYSTEM_ADMIN passes`() {
        signIn("SYSTEM_ADMIN")
        `when`(catalogueImageService.list(63)).thenReturn(emptyList())
        `when`(mainImageFixService.fixLayoutMainImages(true)).thenReturn(MainImageFixResultDto(true, 0, emptyList(), emptyList()))
        mvc.get("/admin/locations/63/images").andExpect { status { isOk() } }
        mvc.post("/admin/yachts/main-image/fix-layout").andExpect { status { isOk() } }
        mvc.delete("/admin/catalogue-images/2000000000").andExpect { status { isNoContent() } }
    }
}
