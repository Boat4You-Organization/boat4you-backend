package hr.workspace.boat4you.domains.catalouge.dto

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.RegexPatternTypeFilter
import java.util.regex.Pattern

/**
 * R02 (owner rule 25.9.2026, design D6): the raw partner yacht name (yacht.partner_name) is internal - no DTO carries it, so
 * no API answer can show an agency's trade name or a charter brand the public name replaced.
 */
class NoPartnerNameInDtoTest {
    @Test
    fun `no DTO class has a partner name field`() {
        val scanner =
            ClassPathScanningCandidateComponentProvider(false).apply {
                addIncludeFilter(RegexPatternTypeFilter(Pattern.compile(".*\\.dto\\..*")))
            }
        val dtos = scanner.findCandidateComponents("hr.workspace.boat4you").map { Class.forName(it.beanClassName) }
        dtos.shouldNotBeEmpty()
        dtos
            .flatMap { c -> c.declaredFields.map { "${c.name}.${it.name}" } }
            .filter { it.substringAfterLast('.').lowercase().contains("partnername") }
            .shouldBeEmpty()
    }
}
