package hr.workspace.boat4you.common

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.common.services.LogMasking
import org.openapitools.client.nausys.model.RestAuthentication
import org.openapitools.client.nausys.model.RestClient
import org.openapitools.client.nausys.model.RestYachtReservationInfoRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogMaskingTests {
    private val objectMapper = ObjectMapper()

    @Test
    fun `maskEmail keeps first char and domain`() {
        assertEquals("a***@x.hr", LogMasking.maskEmail("ana@x.hr"))
        assertEquals("j***@example.com", LogMasking.maskEmail("jana@example.com"))
        assertEquals("***", LogMasking.maskEmail("no-at-sign"))
        assertEquals("***", LogMasking.maskEmail("@nolocal.hr"))
        assertEquals("***", LogMasking.maskEmail(null))
        assertEquals("***", LogMasking.maskEmail("  "))
    }

    @Test
    fun `redactClientPii strips contact and identity fields but keeps name, surname, countryId`() {
        val request =
            RestYachtReservationInfoRequest(
                credentials = RestAuthentication(username = "u", password = "p"),
                client =
                    RestClient(
                        name = "Ana",
                        surname = "Anić",
                        countryId = 100,
                        email = "ana@x.hr",
                        phone = "+385 1 234",
                        mobile = "+385 91 234",
                        address = "Ulica 1",
                        zip = "10000",
                        city = "Zagreb",
                        skype = "ana.skype",
                    ),
                yachtID = 4711L,
                agencyID = 115L,
            )
        val node = objectMapper.valueToTree<ObjectNode>(request)
        node.remove("credentials")

        val json = objectMapper.writeValueAsString(LogMasking.redactClientPii(node))

        listOf("email", "phone", "mobile", "address", "zip", "skype", "ana@x.hr", "+385", "Ulica").forEach {
            assertFalse(json.contains(it), "'$it' must be redacted: $json")
        }
        listOf("\"name\":\"Ana\"", "\"surname\":\"Anić\"", "\"countryId\":100", "\"city\":\"Zagreb\"", "\"yachtID\":4711").forEach {
            assertTrue(json.contains(it), "'$it' must survive: $json")
        }
    }

    @Test
    fun `redactClientPii handles the RestClient2 field names and a missing client`() {
        val node =
            objectMapper.readTree(
                """{"client":{"name":"A","passportNumber":"P1","birthday":"1990-01-01","zipCode":"10000","instagram":"@a"},"yachtID":1}""",
            ) as ObjectNode
        val json = objectMapper.writeValueAsString(LogMasking.redactClientPii(node))
        assertEquals("""{"client":{"name":"A"},"yachtID":1}""", json)

        val noClient = objectMapper.readTree("""{"yachtID":1}""") as ObjectNode
        assertEquals("""{"yachtID":1}""", objectMapper.writeValueAsString(LogMasking.redactClientPii(noClient)))
    }
}
