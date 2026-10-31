package hr.workspace.boat4you.domains.chat.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** E-mail and phone found in what the visitor typed (kept even while the AI is silent, Nihad 31.10.2026). */
class ContactSnifferTest {
    @Test
    fun `an email anywhere in the message is found, without trailing punctuation`() {
        ContactSniffer.email("kruger78@gmail.com") shouldBe "kruger78@gmail.com"
        ContactSniffer.email("write to ana.b@x-y.hr.") shouldBe "ana.b@x-y.hr"
        ContactSniffer.email("<a@b.com>") shouldBe "a@b.com"
        ContactSniffer.email("no mail here @ all").shouldBeNull()
    }

    @Test
    fun `a phone number is found and normalised, 00 becomes +`() {
        ContactSniffer.phone("+385 91 234 5678") shouldBe "+385 91 234 5678"
        ContactSniffer.phone("0038591234567") shouldBe "+38591234567"
        ContactSniffer.phone("call 091/234-5678 please") shouldBe "091 234 5678"
    }

    @Test
    fun `prices, date ranges and short numbers are never taken for a phone`() {
        listOf(
            "Budget range 1500 to 1800 EUR for the boat, charter fee not included",
            "Dates between 18 september 2027 and 23 october 2027",
            "1500-1800",
            "2027-09-18",
            "18.09.2027.",
            "ref 123456",
            "five cabins, ten people",
        ).forEach { ContactSniffer.phone(it).shouldBeNull() }
    }

    @Test
    fun `a phone typed in the contact form needs only 6 digits and the same characters`() {
        ContactSniffer.formPhone(" 0044 7700-900 123 ") shouldBe "+44 7700 900 123"
        ContactSniffer.formPhone("12").shouldBeNull()
        ContactSniffer.formPhone("call me").shouldBeNull()
    }
}
