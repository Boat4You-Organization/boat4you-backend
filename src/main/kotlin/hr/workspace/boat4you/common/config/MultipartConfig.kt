package hr.workspace.boat4you.common.config

import jakarta.servlet.MultipartConfigElement
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.MultipartConfigFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

@Configuration
class MultipartConfig {
    @Value("\${application.upload.max-file-size-mb:10485760}") // 10MB default
    private val maxFileSizeMb: Long = 10

    @Value("\${application.upload.max-file-size-mb:10485760}") // 10MB default
    private val maxRequestSizeMb: Long = 50

    @Bean
    fun multipartConfigElement(): MultipartConfigElement {
        val factory = MultipartConfigFactory()
        factory.setMaxFileSize(DataSize.ofMegabytes(maxFileSizeMb))
        factory.setMaxRequestSize(DataSize.ofMegabytes(maxRequestSizeMb))
        return factory.createMultipartConfig()
    }
}
