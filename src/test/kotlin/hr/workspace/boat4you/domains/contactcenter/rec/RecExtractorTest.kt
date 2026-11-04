package hr.workspace.boat4you.domains.contactcenter.rec

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The positional extractor (P0 spike S12, ported) against clarity-decode 0.8.73 on real spike recordings: the same events with
 * the same fields, 0 mismatches. Events of one (file, seq, event) are compared in time order, as tools/parity.mjs did
 * (clarity-decode sorts a payload's events by time before grouping them).
 */
class RecExtractorTest {
    private val mapper = ObjectMapper()
    private val root = File("src/test/resources/rec")

    private fun ranked(records: List<JsonNode>): Map<Triple<String, Int, Int>, List<JsonNode>> =
        records
            .groupBy { Triple(it.path("file").asText(), it.path("seq").asInt(), it.path("event").asInt()) }
            .mapValues { (_, list) -> list.sortedBy { it.path("time").asLong() } }

    @Test
    fun `spike recordings extract with 0 mismatches against clarity-decode`() {
        val golden = File(root, "decode-golden.ndjson").readLines().filter { it.isNotBlank() }.map(mapper::readTree)
        val ours =
            listOf("catamaran-croatia/uyjjbx-4.ndjson", "boat4you/10k8r1d-1.ndjson").flatMap { file ->
                String(File(root, "fixtures/$file").readBytes(), Charsets.UTF_8)
                    .split('\n')
                    .filter { it.isNotBlank() }
                    .flatMap { line -> RecExtractor.records(file, line).map { mapper.valueToTree<JsonNode>(it) } }
            }
        ours.size shouldBe golden.size
        val want = ranked(golden)
        val got = ranked(ours)
        got.keys shouldBe want.keys
        want.forEach { (key, events) -> got.getValue(key) shouldContainExactly events }
    }

    @Test
    fun `a payload gives its envelope, its events and its variables`() {
        val p =
            RecExtractor.parse(
                """{"e":["0.8.73",3,1200,450,"boat4you","u1","s1",2,0,1,0,"https://www.boat4you.com/boat/x"],""" +
                    """"a":[[1300,9,17,100,200,5000,6000,0,1,0,"Book now","",""" + "\"abc.def\"" + """,1,0,120,40,"BUTTON"],""" +
                    """[1310,34,"ccv",["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"]]]}""",
            )
        p.envelope shouldBe
            RecExtractor.Envelope("0.8.73", 3, 1200, 450, "boat4you", "u1", "s1", 2, end = true, url = "https://www.boat4you.com/boat/x")
        p.events.map { it.type } shouldContainExactly listOf(9)
        p.events.single().data["hash"] shouldBe "abc"
        p.variables shouldBe mapOf("ccv" to listOf("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
    }

    @Test
    fun `a mouse move with the element (F8) is kept for the hover map, an older one and a touch are only counted`() {
        val p =
            RecExtractor.parse(
                """{"e":["0.8.73",3,1200,450,"boat4you","u1","s1",2,0,1,0,"https://www.boat4you.com/boat/x"],""" +
                    """"a":[[1400,12,17,100,200,"","true","abc.def",1000,2000],[1450,12,17,120,200,"","true"],[1500,17,17,100,200,"","true"]]}""",
            )
        p.moves shouldContainExactly listOf(RecExtractor.Move(1400, "abc", "def", 1000, 2000))
        p.events shouldBe emptyList()
        p.touches shouldBe 1
    }
}
