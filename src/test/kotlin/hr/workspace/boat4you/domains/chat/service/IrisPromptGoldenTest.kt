package hr.workspace.boat4you.domains.chat.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Today's boat4you system prompt, frozen: later tasks may change it only on purpose, by editing the golden file. */
class IrisPromptGoldenTest {
    private val h = ChatTestHarness()

    @AfterEach
    fun stop() = h.close()

    internal fun normalized(text: String) = text.replace(LocalDate.now().toString(), "{DATE}").replace(h.service.canary, "{CANARY}")

    @Test
    fun `boat4you - the system prompt is today's prompt`() {
        h.service.handleVisitorMessage(h.newSession(name = "Ana"), "catamaran in Croatia in July for 8 people")
        GoldenFile.check("chat/iris-prompt-boat4you.golden.txt", normalized(h.modelSystems.single()))
    }
}
