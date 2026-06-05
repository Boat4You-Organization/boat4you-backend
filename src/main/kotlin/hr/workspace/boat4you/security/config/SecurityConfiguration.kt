package hr.workspace.boat4you.security.config

import hr.workspace.boat4you.security.JwtAuthenticationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.core.GrantedAuthorityDefaults
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    @org.springframework.beans.factory.annotation.Value("\${application.cors.allowed-origins:*}")
    private val allowedOriginsCsv: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val pathMatcher = PathPatternRequestMatcher.withDefaults()

        http
            .sessionManagement { customizer -> customizer.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { customizer -> customizer.disable() }
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        OrRequestMatcher(
                            pathMatcher.matcher("/auth/login"),
                            pathMatcher.matcher("/auth/requestPasswordReset"),
                            pathMatcher.matcher("/auth/resetPassword"),
                            pathMatcher.matcher("/auth/register/**"),
                            pathMatcher.matcher("/auth/oauth/**"),
                            pathMatcher.matcher("/users/invite"),
                            pathMatcher.matcher("/webhooks/stripe"),
                            pathMatcher.matcher("/swagger/**"),
                            pathMatcher.matcher("/swagger-ui/**"),
                            pathMatcher.matcher("/v3/api-docs/swagger-config"),
                            pathMatcher.matcher("/v3/api-docs/**"),
                            pathMatcher.matcher("/boat4you_ws_common.openapi.yaml"),
                            pathMatcher.matcher("/boat4you_ws_entities_crud.openapi.yaml"),
                            pathMatcher.matcher("/nausys_v6.openapi.yaml"),
                            pathMatcher.matcher("/mmk_api_2_1_3.json"),
                            pathMatcher.matcher("/public/**"),
                            // /admin/nausys/** + /admin/mmk/** removed from
                            // permitAll on 23.4.2026 — endpoints were reachable
                            // by anyone (curl -m 3 → 000 = sync actually started).
                            // Now require SYSTEM_ADMIN role enforced via
                            // @PreAuthorize on each controller class.
                        ),
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.addFilterAfter(jwtAuthenticationFilter, BasicAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        // Restrict allowed origins to the configured frontends. `application.
        // cors.allowed-origins` is a comma-separated list (env-overridable
        // via APPLICATION_CORS_ALLOWED_ORIGINS). Default `*` only on dev
        // profile; prod must set explicit hosts.
        configuration.allowedOriginPatterns =
            allowedOriginsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun grantedAuthorityDefaults(): GrantedAuthorityDefaults {
        return GrantedAuthorityDefaults("")
    }
}
