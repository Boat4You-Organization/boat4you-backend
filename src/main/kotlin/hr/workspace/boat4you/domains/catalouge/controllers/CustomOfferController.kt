package hr.workspace.boat4you.domains.catalouge.controllers

import hr.workspace.boat4you.domains.catalouge.jpa.CustomOfferRepository
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/public/custom-offers")
class CustomOfferController(
    private val customOfferRepository: CustomOfferRepository,
    @Value("\${server.host-public}")
    private val serverHostPublic: String,
) {
    @GetMapping("/{hash}")
    fun redirectToOriginalUrl(
        @PathVariable hash: String,
        response: HttpServletResponse,
    ): ResponseEntity<String> {
        val customOffer = customOfferRepository.findByShortUrl(hash)
        return if (customOffer != null) {
            val fullUrl = serverHostPublic + "/search?" + customOffer.longUrl
            response.sendRedirect(fullUrl)
            ResponseEntity.status(HttpStatus.FOUND).build()
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
