package hr.workspace.boat4you.security.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiSecurityConfig {
    private fun createAPIKeyScheme(): SecurityScheme {
        return SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .bearerFormat("JWT")
            .scheme("bearer")
    }

    @Bean
    fun openAPI(): OpenAPI? {
        // Server URLs intentionally not hardcoded here — springdoc derives the
        // current host from the request. Hardcoding "LOCAL" / "AWS DEV" leaked
        // the dev host into every prod swagger payload (F1-016).
        return OpenAPI()
            .addSecurityItem(
                SecurityRequirement().addList
                    ("Bearer Authentication"),
            ).components(Components().addSecuritySchemes("Bearer Authentication", createAPIKeyScheme()))
            .info(
                Info()
                    .title("Boat4You Backend API")
                    .description("REST API for the Boat4You yacht booking platform.")
                    .version("1.0"),
            )
    }
}
