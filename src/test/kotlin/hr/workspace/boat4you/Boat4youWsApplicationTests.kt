package hr.workspace.boat4you

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@Disabled("Needs a database and env values that CI does not have; to be run on Testcontainers")
@SpringBootTest
class Boat4youWsApplicationTests {
    @Test
    @Suppress("EmptyFunctionBlock")
    fun contextLoads() {
    }
}
