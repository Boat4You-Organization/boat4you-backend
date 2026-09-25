package hr.workspace.boat4you.domains.review

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.review.dto.ReviewSubmitRequest
import hr.workspace.boat4you.domains.review.service.ReviewValidation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReviewValidationTests {
    private fun invalid(
        kind: ReviewKind,
        request: ReviewSubmitRequest,
    ): Map<String, String> = shouldThrow<ParameterValidationException> { ReviewValidation.validate(kind, request, "en") }.badOrMissingParameters

    @Test
    fun `minimal review - rating only, consent defaults to false, locale to the link's`() {
        val v = ReviewValidation.validate(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 4), "de")
        v.rating shouldBe 4
        v.scores shouldBe emptyMap()
        v.title shouldBe null
        v.text shouldBe null
        v.publishConsent shouldBe false
        v.locale shouldBe "de"
    }

    @Test
    fun `full booking review is normalised`() {
        val v =
            ReviewValidation.validate(
                ReviewKind.BOOKING,
                ReviewSubmitRequest(
                    rating = 5,
                    scores = mapOf("valueTransparency" to 4, "easeOfBooking" to 5, "communication" to null),
                    title = "  Great\nservice\t ",
                    text = "  Line one\r\nLine two\u0007  ",
                    publishConsent = true,
                    locale = "HR",
                ),
                "en",
            )
        v.scores.toList() shouldBe listOf("easeOfBooking" to 5, "valueTransparency" to 4)
        v.title shouldBe "Great service"
        v.text shouldBe "Line one\nLine two"
        v.publishConsent shouldBe true
        v.locale shouldBe "hr"
    }

    @Test
    fun `rating is required and 1 to 5`() {
        invalid(ReviewKind.BOOKING, ReviewSubmitRequest()) shouldContainKey "rating"
        invalid(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 0)) shouldContainKey "rating"
        invalid(ReviewKind.YACHT, ReviewSubmitRequest(rating = 6)) shouldContainKey "rating"
    }

    @Test
    fun `sub-scores - only the link's kind, 1 to 5`() {
        invalid(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 5, scores = mapOf("cleanliness" to 5))) shouldContainKey "scores.cleanliness"
        invalid(ReviewKind.YACHT, ReviewSubmitRequest(rating = 5, scores = mapOf("communication" to 5))) shouldContainKey "scores.communication"
        invalid(ReviewKind.YACHT, ReviewSubmitRequest(rating = 5, scores = mapOf("boatCondition" to 9))) shouldContainKey "scores.boatCondition"
        ReviewValidation
            .validate(
                ReviewKind.YACHT,
                ReviewSubmitRequest(
                    rating = 3,
                    scores = mapOf("value" to 2, "boatCondition" to 3, "cleanliness" to 4, "checkInOut" to 5, "charterCompany" to 1),
                ),
                "en",
            ).scores.keys
            .toList() shouldBe ReviewKind.YACHT.scoreKeys
    }

    @Test
    fun `text up to 3000 characters, title up to 120, counted in characters not UTF-16 units`() {
        val emoji = "🌊" // one character, two UTF-16 units
        ReviewValidation.validate(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 5, text = emoji.repeat(3000)), "en").text!!.length shouldBe 6000
        invalid(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 5, text = "a".repeat(3001))) shouldContainKey "text"
        invalid(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 5, title = "t".repeat(121))) shouldContainKey "title"
        ReviewValidation.validate(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 5, title = "t".repeat(120), text = "   "), "en").text shouldBe null
    }

    @Test
    fun `unknown locale is refused, and all problems are reported together`() {
        val errors = invalid(ReviewKind.BOOKING, ReviewSubmitRequest(rating = 9, locale = "ja", text = "x".repeat(3001)))
        errors.keys shouldBe setOf("rating", "locale", "text")
    }
}
