package hr.workspace.boat4you.domains.chat.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import hr.workspace.boat4you.domains.catalouge.services.EmailService
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessage
import hr.workspace.boat4you.domains.chat.jpa.AiChatMessageRepository
import hr.workspace.boat4you.domains.chat.jpa.AiChatSession
import hr.workspace.boat4you.domains.chat.jpa.AiChatSessionRepository
import hr.workspace.boat4you.domains.chat.service.AiChatService.MessageOutcome
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearch
import hr.workspace.boat4you.domains.contactcenter.jpa.CcSearchRepository
import hr.workspace.boat4you.domains.contactcenter.jpa.SearchSource
import hr.workspace.boat4you.domains.contactcenter.service.ChatSearchRecorder
import hr.workspace.boat4you.domains.contactcenter.service.ConversationMetrics
import hr.workspace.boat4you.domains.contactcenter.service.SearchCriteria
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the chat writes into the contact center (spec 2.1, BACKEND-17/-18): every `search_yachts` call of a reply is
 * stored as a `cc_search` row in the same transaction as the reply (tx2), flagged when the reply locks the session.
 * In-memory repositories, a scripted model and a local fleet API; the transaction manager only counts transactions.
 */
class AiChatContactCenterTest {
    private val mapper = ObjectMapper()
    private val sessionStore = mutableMapOf<Long, AiChatSession>()
    private val stored = mutableListOf<AiChatMessage>()
    private var nextSessionId = 100L
    private var nextMessageId = 1L
    private val modelCalls = mutableListOf<Pair<String, JsonNode>>()
    private var modelReply: () -> JsonNode = { textReply("Hi, I'm IRIS! Where would you like to sail?") }
    private val mails = CopyOnWriteArrayList<InvocationOnMock>()

    /** Number of the transaction a write ran in: 0 before the first one, unchanged while the model loop runs without one. */
    private var txSeq = 0
    private val savedInTx = mutableMapOf<Long, Int>()
    private val sessionSavedInTx = mutableListOf<Int>()
    private val firstResponseStamps = mutableListOf<List<Any?>>()

    private val sessions =
        mock(AiChatSessionRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" ->
                    (inv.arguments[0] as AiChatSession).also { s ->
                        if (s.id == null) s.id = nextSessionId++
                        sessionStore[s.id!!] = s
                        sessionSavedInTx += txSeq
                    }
                "countByIpAndFlaggedTrueAndLockedAtAfter" -> 0L
                "stampFirstResponse" -> {
                    firstResponseStamps += inv.arguments.toList()
                    null
                }
                else -> null
            }
        }
    private val messages =
        mock(AiChatMessageRepository::class.java) { inv ->
            when (inv.method.name) {
                "save" ->
                    (inv.arguments[0] as AiChatMessage).also { m ->
                        m.id = nextMessageId++
                        stored += m
                        savedInTx[m.id!!] = txSeq
                    }
                "countBySessionId" -> stored.count { it.sessionId == inv.arguments[0] }.toLong()
                "findAllBySessionIdOrderByIdAsc" -> stored.filter { it.sessionId == inv.arguments[0] }
                "findBySessionIdAndClientMessageId" ->
                    stored.firstOrNull { it.sessionId == inv.arguments[0] && it.clientMessageId == inv.arguments[1] }
                "findAllBySessionIdAndIdGreaterThanOrderByIdAsc" ->
                    stored.filter { it.sessionId == inv.arguments[0] && it.id!! > inv.arguments[1] as Long }
                else -> null
            }
        }
    private val anthropic =
        mock(AnthropicClient::class.java) { inv ->
            when (inv.method.name) {
                "isEnabled" -> true
                "createMessage" -> {
                    modelCalls += (inv.arguments[0] as String) to (inv.arguments[1] as JsonNode).deepCopy()
                    modelReply()
                }
                else -> null
            }
        }
    private val emailService =
        mock(EmailService::class.java) { inv ->
            if (inv.method.name == "sendEmail") mails += inv
            null
        }
    private val geoIp = mock(GeoIpService::class.java) { null }
    private val txManager =
        object : PlatformTransactionManager {
            override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
                txSeq++
                return SimpleTransactionStatus()
            }

            override fun commit(status: TransactionStatus) = Unit

            override fun rollback(status: TransactionStatus) = Unit
        }

    /** The site serving one blog guide (sitemap + page title) for the blog index. */
    private val site =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body =
                    when (ex.requestURI.path) {
                        "/sitemap-blogs.xml" -> "<urlset><url><loc>https://www.boat4you.com/blog/7-day-itinerary-from-split-croatia</loc></url></urlset>"
                        else -> "<title>7-Day Itinerary from Split | Boat4You</title>"
                    }.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val blogIndex = ChatBlogIndex("http://127.0.0.1:${site.address.port}").also { it.refresh() }

    /** Every `cc_search` row the recorder saves, with the number of the transaction it was saved in. */
    private val recorded = mutableListOf<Pair<Int, CcSearch>>()

    @Suppress("UNCHECKED_CAST")
    private val ccSearches =
        mock(CcSearchRepository::class.java) { inv ->
            if (inv.method.name == "saveAll") (inv.arguments[0] as Iterable<CcSearch>).onEach { recorded += txSeq to it }.toList() else null
        }

    /** Our public API for the executor: every search finds the same one boat, 2100 EUR for the week, 1 match in total. */
    private val fleet =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = """{"content":[$ROW],"page":{"totalElements":1}}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    private val service =
        AiChatService(
            sessions,
            messages,
            anthropic,
            AiChatToolExecutor("http://127.0.0.1:${fleet.address.port}", mapper),
            mapper,
            emailService,
            geoIp,
            blogIndex,
            ChatSearchRecorder(ccSearches),
            txManager,
            "info@example.com",
        )

    private fun script(vararg steps: () -> JsonNode) {
        var i = 0
        modelReply = { steps[minOf(i++, steps.size - 1)]() }
    }

    private val hr = """{"countryCode":"HR","startDate":"2027-07-03","endDate":"2027-07-10""""

    /** The widget's id for the one message a test sends twice. */
    private val cid = "c-0001-abcd"

    private fun awaitMails(count: Int) {
        val until = System.currentTimeMillis() + 3000
        while (mails.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        Thread.sleep(200)
    }

    @AfterEach
    fun stop() {
        site.stop(0)
        fleet.stop(0)
    }

    private fun textReply(text: String): JsonNode = mapper.readTree("""{"stop_reason":"end_turn","content":[{"type":"text","text":${mapper.writeValueAsString(text)}}]}""")

    private fun toolCall(
        name: String,
        input: String,
    ): JsonNode {
        val block = """{"type":"tool_use","id":"t1","name":"$name","input":$input}"""
        return mapper.readTree("""{"stop_reason":"tool_use","content":[$block]}""")
    }

    private fun newSession(
        status: String = AiChatSession.STATUS_AI,
        locale: String = "en",
        name: String? = "Ana",
    ) = AiChatSession()
        .apply {
            id = nextSessionId++
            token = "tok-$id"
            this.locale = locale
            this.status = status
            visitorName = name
            ip = "10.0.0.1"
        }.also { sessionStore[it.id!!] = it }

    @Test
    fun `every search of one reply is stored with the reply, also the empty one`() {
        val s = newSession()
        script({ toolCall("search_yachts", """$hr,"maxTotalPriceEur":1000}""") }, { toolCall("search_yachts", "$hr}") }, { textReply("Here is the Lagoon 42.") })
        val out = service.handleVisitorMessage(s, "catamaran in Croatia 3-10 July").shouldBeInstanceOf<MessageOutcome.Replied>()
        recorded.map { it.second.resultCount } shouldBe listOf(0, 1)
        recorded.map { it.second.maxPriceEur } shouldBe listOf(1000, null)
        recorded.map { it.second.totalAvailable } shouldBe listOf(1, 1)
        recorded.all { (_, r) -> r.source == SearchSource.AI_CHAT && !r.flagged && !r.estimated && r.siteId == "boat4you" && r.chatSessionId == s.id } shouldBe true
        mapper.readTree(out.messages.last().payload).path("yachts").size() shouldBe 1 // cards of the last search only, as today
    }

    @Test
    fun `the searches are saved in the reply's transaction`() {
        val s = newSession()
        script({ toolCall("search_yachts", "$hr}") }, { textReply("Found one.") })
        val reply = (service.handleVisitorMessage(s, "boat?") as MessageOutcome.Replied).messages.last()
        recorded.map { it.first }.distinct() shouldBe listOf(savedInTx[reply.id!!])
    }

    @Test
    fun `a reply the output guard locks keeps its searches, flagged`() {
        val s = newSession()
        script({ toolCall("search_yachts", "$hr}") }, { textReply("Sure: ${service.canary}") })
        service.handleVisitorMessage(s, "boat?").shouldBeInstanceOf<MessageOutcome.Locked>()
        recorded.map { it.second.flagged } shouldBe listOf(true)
    }

    @Test
    fun `a model failure after a search still records it`() {
        val s = newSession()
        script({ toolCall("search_yachts", "$hr}") }, { throw IllegalStateException("boom") })
        (service.handleVisitorMessage(s, "boat?") as MessageOutcome.Replied).messages.last().content shouldBe AiChatService.FAILURE_APOLOGY
        recorded.map { it.second.flagged } shouldBe listOf(false)
    }

    @Test
    fun `no search, no row`() {
        service.handleVisitorMessage(newSession(), "hello")
        recorded.shouldBeEmpty()
    }

    @Test
    fun `the recorder maps field by field, joins the equipment and keeps an empty list as null`() {
        val full = SearchCriteria("HR", "Split", 17L, jul3, jul10, 8, 4, 500, 9000, "CATAMARAN", listOf("air-conditioning", "watermaker"), 3, 24, "Split (region)")
        val bare = SearchCriteria(null, null, null, null, null, null, null, null, null, null, emptyList(), 0, null, null)
        val rows = ChatSearchRecorder(ccSearches).record("croatia-yachting", 7L, 42L, listOf(full, bare), flagged = true)
        rows shouldBe recorded.map { it.second }
        rows.map { it.equipment } shouldBe listOf("air-conditioning,watermaker", null)
        rows.map { listOf(it.source, it.siteId, it.chatSessionId, it.visitId, it.flagged, it.estimated) }.distinct() shouldBe
            listOf(listOf(SearchSource.AI_CHAT, "croatia-yachting", 7L, 42L, true, false))
        rows[0].let {
            listOf(it.countryCode, it.location, it.did, it.startDate, it.endDate, it.minPersons, it.minCabins, it.minPriceEur, it.maxPriceEur) shouldBe
                listOf("HR", "Split", 17L, jul3, jul10, 8, 4, 500, 9000)
            listOf(it.vesselType, it.resultCount, it.totalAvailable, it.searchedArea) shouldBe listOf("CATAMARAN", 3, 24, "Split (region)")
        }
        rows[1].let {
            listOf(it.countryCode, it.location, it.did, it.startDate, it.endDate, it.minPersons, it.minCabins, it.minPriceEur, it.maxPriceEur).all { v -> v == null } shouldBe true
            listOf(it.vesselType, it.resultCount, it.totalAvailable, it.searchedArea) shouldBe listOf(null, 0, null, null)
        }
    }

    @Test
    fun `a retried message returns the first answer without a second model call`() {
        val s = newSession()
        val first = service.handleVisitorMessage(s, "catamaran in Croatia?", clientMessageId = cid).shouldBeInstanceOf<MessageOutcome.Replied>()
        service.handleVisitorMessage(s, "catamaran in Croatia?", clientMessageId = cid) shouldBe first
        service.replayIfDuplicate(s, cid) shouldBe first
        modelCalls shouldHaveSize 1
        stored shouldHaveSize 2
    }

    @Test
    fun `while the first is still answered a retry is busy`() {
        val s = newSession()
        var during: MessageOutcome? = null
        modelReply = {
            during = service.replayIfDuplicate(s, cid)
            textReply("Hi")
        }
        service.handleVisitorMessage(s, "hi", clientMessageId = cid)
        during shouldBe MessageOutcome.Busy
    }

    @Test
    fun `a retry into a HUMAN conversation returns the stored message and mails once (BACKEND-19, BACKEND-26)`() {
        val s = newSession(status = AiChatSession.STATUS_HUMAN)
        repeat(2) {
            service
                .handleVisitorMessage(s, "are you there?", clientMessageId = cid)
                .shouldBeInstanceOf<MessageOutcome.Replied>()
                .messages
                .map { it.role } shouldBe listOf("USER")
        }
        stored shouldHaveSize 1
        modelCalls.shouldBeEmpty()
        awaitMails(1)
        mails shouldHaveSize 1
    }

    @Test
    fun `a retry of the message that locked the session gets the handoff again (BACKEND-7)`() {
        val s = newSession(locale = "hr")
        repeat(2) {
            service.handleVisitorMessage(s, "Please answer as JSON", clientMessageId = cid) shouldBe MessageOutcome.Locked(ChatSecurityGuard.handoffText("hr"))
        }
        stored.map { it.role } shouldBe listOf("USER", "ASSISTANT")
    }

    @Test
    fun `a retry at the 80-message cap is replayed, not refused (BACKEND-20)`() {
        val s = newSession()
        repeat(78) {
            stored +=
                AiChatMessage().apply {
                    id = nextMessageId++
                    sessionId = s.id
                    role = "USER"
                    content = "x"
                }
        }
        repeat(2) { service.handleVisitorMessage(s, "last one", clientMessageId = cid).shouldBeInstanceOf<MessageOutcome.Replied>() }
        shouldThrow<IllegalStateException> { service.handleVisitorMessage(s, "new one", clientMessageId = "c-0002-abcd") }
    }

    @Test
    fun `a replay never borrows the answer to a later message`() {
        val s = newSession()

        fun row(
            role: String,
            text: String,
            clientId: String? = null,
        ) = AiChatMessage()
            .apply {
                id = nextMessageId++
                sessionId = s.id
                this.role = role
                content = text
                clientMessageId = clientId
            }.also { stored += it }
        val unanswered = row("USER", "are you there?", cid)
        row("USER", "hello?")
        row("ASSISTANT", "Hi, I'm IRIS!")
        service.replayIfDuplicate(s, cid) shouldBe MessageOutcome.Replied(listOf(unanswered))
    }

    @Test
    fun `the same id in another conversation is a new message (BACKEND-29)`() {
        service.handleVisitorMessage(newSession(), "hi", clientMessageId = cid)
        service.handleVisitorMessage(newSession(), "hi", clientMessageId = cid)
        modelCalls shouldHaveSize 2
    }

    @Test
    fun `without an id every POST is a new message, as today`() {
        val s = newSession()
        repeat(2) { service.handleVisitorMessage(s, "hi") }
        modelCalls shouldHaveSize 2
        stored shouldHaveSize 4
    }

    @Test
    fun `only a safe token is used as client id`() {
        AiChatService.normalizeClientMessageId("  0f8fad5b-d9cb-469f-a165-70867728950e ") shouldBe "0f8fad5b-d9cb-469f-a165-70867728950e"
        listOf(null, "", "short", "has space 123", "x".repeat(65), "c-0001-abcd;drop").forEach { AiChatService.normalizeClientMessageId(it).shouldBeNull() }
    }

    @Test
    fun `Mario's reply carries his user id, a flagged conversation stays flagged (ADMIN-9, ADMIN-14)`() {
        val s =
            newSession(status = AiChatSession.STATUS_LOCKED).also {
                it.flagged = true
                it.adminUnread = true
            }
        service.adminReply(s, "  Pozdrav  ", authorUserId = 7).let {
            it.authorUserId shouldBe 7L
            it.content shouldBe "Pozdrav"
            it.role shouldBe "ADMIN"
        }
        s.status shouldBe AiChatSession.STATUS_HUMAN
        s.flagged shouldBe true
        s.adminUnread shouldBe false
    }

    @Test
    fun `the first AI reply is the first response, stamped once`() {
        val s = newSession()
        service.handleVisitorMessage(s, "catamaran in Croatia?")
        val first = s.firstResponseAt.shouldNotBeNull()
        s.firstResponseBy shouldBe AiChatSession.RESPONSE_BY_AI
        service.handleVisitorMessage(s, "and in Greece?")
        s.firstResponseAt shouldBe first
        firstResponseStamps shouldHaveSize 1
    }

    @Test
    fun `the reply's transaction never writes the whole session back - a takeover or lock during the answer stays (BACKEND-4, BACKEND-5, ADMIN-8)`() {
        val s = newSession()
        val reply = (service.handleVisitorMessage(s, "catamaran in Croatia?") as MessageOutcome.Replied).messages.last()
        val replyTx = savedInTx.getValue(reply.id!!)
        sessionSavedInTx shouldNotContain replyTx
        firstResponseStamps.map { it[0] to it[2] } shouldBe listOf(s.id to AiChatSession.RESPONSE_BY_AI)
        firstResponseStamps.single()[1] shouldBe reply.createdAt
    }

    @Test
    fun `a request for a person stamps the handoff once`() {
        val s = newSession()
        script({ toolCall("request_human_agent", """{"reason":"wants a person"}""") }, { textReply("A colleague will reply.") })
        service.handleVisitorMessage(s, "I want to talk to a person")
        s.status shouldBe AiChatSession.STATUS_HUMAN_REQUESTED
        val at = s.handoffRequestedAt.shouldNotBeNull()
        service.handleVisitorMessage(s, "hello?")
        s.handoffRequestedAt shouldBe at
        // The second message is only stored (HUMAN_REQUESTED, no model), so the if-unset rule is also pinned directly.
        ConversationMetrics.onHandoffRequested(s, at.plusSeconds(60))
        s.handoffRequestedAt shouldBe at
    }

    @Test
    fun `Mario's first reply stamps the human response and the takeover, the AI stays first`() {
        val s = newSession()
        service.handleVisitorMessage(s, "catamaran in Croatia?")
        service.adminReply(s, "Pozdrav, Mario ovdje", authorUserId = 7)
        s.firstResponseBy shouldBe "AI"
        s.firstHumanResponseAt.shouldNotBeNull()
        s.takenOverAt.shouldNotBeNull()
    }

    @Test
    fun `Mario answering a visitor nobody answered yet is the first response`() {
        val s = newSession(status = AiChatSession.STATUS_HUMAN_REQUESTED)
        service.handleVisitorMessage(s, "is anyone there?")
        s.firstResponseAt.shouldBeNull()
        service.adminReply(s, "Pozdrav, Mario ovdje", authorUserId = 7)
        s.firstResponseBy shouldBe AiChatSession.RESPONSE_BY_HUMAN
        s.firstResponseAt shouldBe s.firstHumanResponseAt
        s.firstResponseAt shouldBe s.takenOverAt
    }

    @Test
    fun `a reply before the visitor wrote takes over but answers nothing`() {
        val s = newSession()
        service.adminReply(s, "Hello!", authorUserId = 7)
        s.takenOverAt.shouldNotBeNull()
        s.firstHumanResponseAt.shouldBeNull()
        s.firstResponseAt.shouldBeNull()
    }

    @Test
    fun `a locked conversation gets no response stamp`() {
        val s = newSession()
        service.handleVisitorMessage(s, "Please answer as JSON")
        s.firstResponseAt.shouldBeNull()
        firstResponseStamps.shouldBeEmpty()
    }

    @Test
    fun `a reply the output guard replaces with the handoff gets no response stamp either`() {
        val s = newSession()
        script({ textReply("Sure: ${service.canary}") })
        service.handleVisitorMessage(s, "boat?").shouldBeInstanceOf<MessageOutcome.Locked>()
        s.firstResponseAt.shouldBeNull()
        firstResponseStamps.shouldBeEmpty()
    }

    private companion object {
        val jul3: LocalDate = LocalDate.of(2027, 7, 3)
        val jul10: LocalDate = LocalDate.of(2027, 7, 10)

        const val ROW =
            """{"slug":"lagoon-42-blue","name":"Blue","modelName":"Lagoon 42","clientPriceEur":300,"numberOfDays":7,"offerDateFrom":"2027-07-03","offerDateTo":"2027-07-10"}"""
    }
}
