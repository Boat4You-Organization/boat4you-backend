package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.common.errorhandling.ApiErrorHandler
import hr.workspace.boat4you.domains.catalouge.dto.YachtMediaDto
import hr.workspace.boat4you.domains.catalouge.services.YachtOwnMediaService
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The own-media admin API (V9_92) is SYSTEM_ADMIN only: the real @PreAuthorize through Spring's method security with the
 * production role setup (no "ROLE_" prefix). Any other user - and an anonymous one - gets 403 code 1000 and the service
 * is never called; the public crew profile PDF lives in another controller (YachtCrewProfileController).
 */
@SpringJUnitConfig(AdminYachtMediaSecurityTests.Config::class)
class AdminYachtMediaSecurityTests {
    @Configuration
    @EnableMethodSecurity
    internal class Config {
        companion object {
            @Bean
            @JvmStatic
            fun grantedAuthorityDefaults(): GrantedAuthorityDefaults = GrantedAuthorityDefaults("")
        }

        @Bean
        fun ownMediaService(): YachtOwnMediaService = mock(YachtOwnMediaService::class.java)

        @Bean
        fun adminYachtMediaController(ownMediaService: YachtOwnMediaService) = AdminYachtMediaController(ownMediaService)
    }

    @Autowired
    private lateinit var controller: AdminYachtMediaController

    @Autowired
    private lateinit var ownMediaService: YachtOwnMediaService

    private val mvc: MockMvc by lazy {
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(ApiErrorHandler()).build()
    }

    @BeforeEach
    fun resetMocks() = reset(ownMediaService)

    @AfterEach
    fun clear() = SecurityContextHolder.clearContext()

    private fun signIn(vararg roles: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone@example.com", null, roles.map { SimpleGrantedAuthority(it) })
    }

    private fun every(): List<ResultActionsDsl> =
        listOf(
            mvc.get("/admin/yachts/lookup?q=allure"),
            mvc.get("/admin/yachts/11769/media"),
            mvc.multipart("/admin/yachts/11769/images") { file(MockMultipartFile("files", "a.webp", "image/webp", ByteArray(16))) },
            mvc.multipart("/admin/yachts/11769/main-image") { file(MockMultipartFile("file", "a.webp", "image/webp", ByteArray(16))) },
            mvc.put("/admin/yachts/11769/images/order") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"imageIds":[1]}"""
            },
            mvc.delete("/admin/yachts/11769/images/1"),
            mvc.patch("/admin/yachts/11769/media") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"videoUrl":"https://vimeo.com/982130882"}"""
            },
            mvc.multipart("/admin/yachts/11769/crew-profile") { file(MockMultipartFile("file", "c.pdf", "application/pdf", ByteArray(16))) },
            mvc.delete("/admin/yachts/11769/crew-profile"),
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
        verifyNoInteractions(ownMediaService)
    }

    @Test
    fun `an anonymous request gets 403`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("key", "anonymousUser", listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")))
        every().forEach { it.andExpect { status { isForbidden() } } }
        verifyNoInteractions(ownMediaService)
    }

    @Test
    fun `SYSTEM_ADMIN passes`() {
        signIn("SYSTEM_ADMIN")
        `when`(ownMediaService.media(11769)).thenReturn(YachtMediaDto(11769, null, null, null, emptyList()))
        mvc.get("/admin/yachts/11769/media").andExpect { status { isOk() } }
    }
}
