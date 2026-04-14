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
                            pathMatcher.matcher("/users/invite"),
                            pathMatcher.matcher("/webhooks/stripe"),
                            pathMatcher.matcher("/webhooks/viva"),
                            pathMatcher.matcher("/swagger/**"),
                            pathMatcher.matcher("/swagger-ui/**"),
                            pathMatcher.matcher("/v3/api-docs/swagger-config"),
                            pathMatcher.matcher("/v3/api-docs/**"),
                            pathMatcher.matcher("/boat4you_ws_common.openapi.yaml"),
                            pathMatcher.matcher("/boat4you_ws_entities_crud.openapi.yaml"),
                            pathMatcher.matcher("/nausys_v6.openapi.yaml"),
                            pathMatcher.matcher("/mmk_api_2_1_3.json"),
                            pathMatcher.matcher("/public/**"),
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
        configuration.allowedOrigins = listOf("*")
        configuration.allowedMethods = listOf("*")
        configuration.allowedHeaders = listOf("*")
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }

    @Bean
    fun grantedAuthorityDefaults(): GrantedAuthorityDefaults {
        return GrantedAuthorityDefaults("")
    }
}
