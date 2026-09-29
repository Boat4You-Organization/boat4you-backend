package hr.workspace.boat4you.domains.catalouge

import hr.workspace.boat4you.domains.catalouge.services.InquiryMutationService.Companion.submitKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** One inquiry per submit (Mario 27.9.2026): a double tap or a retry collapses, a corrected field does not. */
class InquirySubmitKeyTests {
    private val from = LocalDate.of(2027, 9, 18)
    private val to = LocalDate.of(2027, 9, 25)

    private fun key(
        email: String? = "Guest@Example.com",
        yachtId: Long? = 4788,
        phone: String? = "+385 91 234 5678",
        name: String? = "Ana",
        surname: String? = "Horvat",
        message: String? = "Hello,\n we are  4 people.",
    ) = submitKey(email, yachtId, from, to, phone, name, surname, message)

    @Test
    fun `the same submit typed or retried differently is one inquiry`() {
        key() shouldBe key(email = "  guest@example.com ", phone = "00385912345678".drop(2).let { "+$it" }, message = "Hello, we are 4 people. ")
        key() shouldBe key(phone = "385-91-234-5678", name = " ana ", surname = "HORVAT")
    }

    @Test
    fun `a corrected phone, name, message or boat is a new inquiry`() {
        key() shouldNotBe key(phone = "+385 91 234 5679")
        key() shouldNotBe key(name = "Anna")
        key() shouldNotBe key(message = "Hello, we are 5 people.")
        key() shouldNotBe key(yachtId = 1883)
    }

    @Test
    fun `missing optional fields still give a stable key`() {
        key(yachtId = null, name = null, surname = null, message = null) shouldBe key(yachtId = null, name = "", surname = " ", message = "  ")
    }
}
