package hr.workspace.boat4you.domains.pr.service

/**
 * A small RFC 4180 reader (the build has no CSV library): fields separated by commas, records by CRLF or LF; a field
 * that starts with a quote may hold commas, line breaks and doubled quotes. A quote inside an unquoted field is kept as
 * text. A leading BOM and blank lines are ignored. An unterminated quoted field throws IllegalArgumentException.
 */
object PrCsv {
    fun parse(text: String): List<List<String>> {
        val reader = Reader(text.removePrefix("﻿"))
        reader.run()
        return reader.records.filterNot { it.size == 1 && it[0].isEmpty() }
    }

    private class Reader(
        private val s: String,
    ) {
        val records = mutableListOf<List<String>>()
        private var record = mutableListOf<String>()
        private val field = StringBuilder()
        private var quoted = false
        private var inQuotes = false
        private var i = 0

        fun run() {
            while (i < s.length) {
                if (inQuotes) quotedChar(s[i]) else plainChar(s[i])
                i++
            }
            require(!inQuotes) { "CSV ends inside a quoted field" }
            if (field.isNotEmpty() || quoted || record.isNotEmpty()) endRecord()
        }

        private fun quotedChar(c: Char) {
            if (c != '"') {
                field.append(c)
            } else if (s.getOrNull(i + 1) == '"') {
                field.append('"')
                i++
            } else {
                inQuotes = false
            }
        }

        private fun plainChar(c: Char) {
            when (c) {
                '"' -> if (field.isEmpty() && !quoted) startQuotes() else field.append(c)
                ',' -> endField()
                '\r' -> {
                    if (s.getOrNull(i + 1) == '\n') i++
                    endRecord()
                }
                '\n' -> endRecord()
                else -> field.append(c)
            }
        }

        private fun startQuotes() {
            quoted = true
            inQuotes = true
        }

        private fun endField() {
            record.add(field.toString())
            field.clear()
            quoted = false
        }

        private fun endRecord() {
            endField()
            records.add(record)
            record = mutableListOf()
        }
    }
}
