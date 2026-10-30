package hr.workspace.boat4you.domains.calls

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.converter.ByteArrayHttpMessageConverter
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.ResourceHttpMessageConverter
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter

/** Standalone MockMvc with the JSON mapping Spring Boot gives the app (Kotlin + java.time), so request DTOs bind as in prod. */
object CallsTestJson {
    fun converters(): Array<HttpMessageConverter<*>> =
        arrayOf(
            ByteArrayHttpMessageConverter(),
            StringHttpMessageConverter(),
            ResourceHttpMessageConverter(),
            MappingJackson2HttpMessageConverter(
                jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
            ),
        )
}
