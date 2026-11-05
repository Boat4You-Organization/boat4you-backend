package hr.workspace.boat4you.domains.pr

import hr.workspace.boat4you.domains.pr.service.PrCsv
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** RFC 4180 as the first-wave contact file uses it (there is no CSV library in the build). */
class PrCsvTest {
    @Test
    fun `plain fields, empty fields and CRLF or LF line ends`() {
        PrCsv.parse("a,b,c\r\n1,,3\n,x,\r\n") shouldBe listOf(listOf("a", "b", "c"), listOf("1", "", "3"), listOf("", "x", ""))
    }

    @Test
    fun `quoted fields keep commas, doubled quotes and line breaks`() {
        PrCsv.parse("name,role\n\"Doe, Jane\",\"Says \"\"hi\"\"\r\nand bye\"\n") shouldBe
            listOf(listOf("name", "role"), listOf("Doe, Jane", "Says \"hi\"\r\nand bye"))
    }

    @Test
    fun `last row without a line end, BOM and blank lines`() {
        PrCsv.parse("﻿a,b\n\n1,2") shouldBe listOf(listOf("a", "b"), listOf("1", "2"))
        PrCsv.parse("") shouldBe emptyList()
    }

    @Test
    fun `a quote inside an unquoted field is kept as text`() {
        PrCsv.parse("5\" screen,x\n") shouldBe listOf(listOf("5\" screen", "x"))
    }

    @Test
    fun `an unterminated quoted field is an error`() {
        shouldThrow<IllegalArgumentException> { PrCsv.parse("a,\"b\nc") }
    }
}
