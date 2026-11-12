package com.meowmba.shared.workflows

import com.meowmba.shared.core.Decimal

/**
 * Deterministic arithmetic, not a language model's guess at a business total.
 *
 * Ported from `core/workflows/CsvSummary.kt`. The CSV parser and every
 * validation rule are unchanged. The only translation is `java.math.BigDecimal`
 * → [Decimal], which is exact decimal arithmetic over digit strings rather than
 * a fixed-width integer: the accepted input is up to 100 characters and the sum
 * of an unbounded number of rows grows past any `Long`, so a scaled-`Long`
 * substitute would overflow on large inputs while every small-input test still
 * passed.
 *
 * The scale is preserved rather than normalised, because `BigDecimal` behaves
 * that way and the output is user-visible: `0.10 + 0.20` prints as `0.30`.
 */
object CsvSummary {
    fun summarize(
        text: String,
        amountColumn: String,
        filterColumn: String? = null,
        filterValue: String? = null,
        caseSensitive: Boolean = false,
    ): String {
        val rows = parse(text.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "CSV is empty" }
        val header = rows.first()
        require(header.toSet().size == header.size) { "Duplicate column names" }
        // Models frequently capitalize a label copied from a natural-language
        // request. Accept only an unambiguous case alias, never fuzzy matches.
        fun column(name: String): Int {
            val exact = header.indexOf(name)
            if (exact >= 0) return exact
            val aliases = header.indices.filter { header[it].equals(name, ignoreCase = true) }
            require(aliases.size == 1) {
                "Column ${if (aliases.isEmpty()) "not found" else "is ambiguous"}: $name. Use an exact name from: ${header.joinToString()}"
            }
            return aliases.single()
        }
        val amount = column(amountColumn)
        val filter = if (filterColumn.isNullOrBlank()) -1 else column(filterColumn)
        if (filter >= 0) require(filterValue != null) { "filter_value is required" }
        val data = rows.drop(1)
        require(data.all { it.size == header.size }) { "CSV row length does not match header" }
        val selected = data.filter { filter < 0 || it[filter].equals(filterValue, ignoreCase = !caseSensitive) }
        val sum = selected.fold(Decimal.ZERO) { total, row ->
            val number = row[amount].trim()
            require(number.length <= 100 && number.matches(Regex("[-+]?\\d+(\\.\\d+)?"))) {
                "Non-decimal value in $amountColumn; no total calculated"
            }
            total + Decimal.parse(number)
        }
        return "Rows in file: ${data.size}\nSelected rows: ${selected.size}\nSum of ${header[amount]}: $sum\n" +
            if (filter >= 0) {
                "Filter: ${header[filter]} equals $filterValue (${if (caseSensitive) "case-sensitive" else "case-insensitive"})"
            } else {
                "Filter: none"
            }
    }

    internal fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val value = StringBuilder()
        var quoted = false
        var closed = false
        var i = 0
        fun field() {
            row += value.toString(); value.setLength(0); closed = false
        }
        fun record() {
            field(); if (row.size > 1 || row.first().isNotEmpty()) rows += row.toList(); row.clear()
        }
        while (i < text.length) {
            val c = text[i++]
            if (quoted) {
                if (c == '"') {
                    if (i < text.length && text[i] == '"') {
                        value.append('"'); i++
                    } else {
                        quoted = false; closed = true
                    }
                } else {
                    value.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        require(value.isEmpty() && !closed) { "Malformed CSV quote" }; quoted = true
                    }
                    ',' -> field()
                    '\n', '\r' -> {
                        if (c == '\r' && i < text.length && text[i] == '\n') i++
                        record()
                    }
                    else -> {
                        require(!closed) { "Text after a CSV closing quote" }; value.append(c)
                    }
                }
            }
        }
        require(!quoted) { "Unclosed CSV quote" }
        if (value.isNotEmpty() || row.isNotEmpty() || closed) record()
        return rows
    }
}
