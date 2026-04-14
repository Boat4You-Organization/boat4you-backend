package hr.workspace.boat4you.security.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
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
        val localServer = Server().description("LOCAL").url("https://localhost:8443")
        val remoteServer = Server().description("AWS DEV").url("https://boat4you-dev.workspace.hr/api")
        return OpenAPI()
            .servers(listOf(localServer, remoteServer))
            .addSecurityItem(
                SecurityRequirement().addList
                    ("Bearer Authentication"),
            ).components(Components().addSecuritySchemes("Bearer Authentication", createAPIKeyScheme()))
            .info(
                Info()
                    .title("My REST API")
                    .description("Some custom description of API.")
                    .version("1.0"),
            )
    }
}
