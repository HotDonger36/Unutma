package com.example.unutma.util

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow

/**
 * Maths in notes: a line that ends with "=" gets its answer shown after it, e.g.
 * "Groceries 12,50 + 7,20 + 3 =" shows "22,7". Supports + − × ÷ ^ %, brackets, and
 * both decimal commas and points (the answer uses whichever you typed).
 */
object MathEval {
    data class Result(val insertAt: Int, val text: String)

    private val allowed = "0123456789.,+-*/x×÷^%() \t−".toSet()

    /** One result per line ending in "=" whose expression is valid. */
    fun results(text: String): List<Result> {
        val out = ArrayList<Result>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, nl)
            val trimmedEnd = line.trimEnd()
            if (trimmedEnd.endsWith("=") && trimmedEnd.length > 1) {
                val exprPart = trimmedEnd.dropLast(1)
                var i = exprPart.length
                while (i > 0 && exprPart[i - 1] in allowed) i--
                var expr = exprPart.substring(i).trim()
                // Drop leading operators or stray "x" that belonged to a word.
                expr = expr.trimStart { it == 'x' || it == '×' || it == '*' || it == '/' || it == '÷' || it == '^' || it == '+' || it == '%' || it.isWhitespace() }
                if (expr.any { it.isDigit() } && expr.any { it in "+-*/x×÷^%−" }) {
                    val value = evaluate(expr)
                    if (value != null) {
                        val useComma = Regex("\\d,\\d").containsMatchIn(expr)
                        out += Result(lineStart + trimmedEnd.length, " " + format(value, useComma))
                    }
                }
            }
            lineStart = nl + 1
        }
        return out
    }

    fun format(v: Double, comma: Boolean): String {
        if (v.isNaN() || v.isInfinite()) return "?"
        val bd = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString()
        return if (comma) s.replace('.', ',') else s
    }

    fun evaluate(expr: String): Double? = try {
        val p = Parser(expr.replace('−', '-').replace('×', '*').replace('÷', '/'))
        val v = p.parseExpr()
        p.skip()
        if (p.pos != p.s.length) null else v
    } catch (_: Exception) { null }

    private class Parser(val s: String) {
        var pos = 0
        fun skip() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun peek(): Char? { skip(); return if (pos < s.length) s[pos] else null }

        fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                when (peek()) {
                    '*', 'x' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; val d = parseFactor(); if (d == 0.0) throw ArithmeticException(); v /= d }
                    else -> return v
                }
            }
        }

        fun parseFactor(): Double {
            var v = parseUnary()
            if (peek() == '^') { pos++; v = v.pow(parseFactor()) }
            if (peek() == '%') { pos++; v /= 100.0 }
            return v
        }

        fun parseUnary(): Double = when (peek()) {
            '-' -> { pos++; -parseUnary() }
            '+' -> { pos++; parseUnary() }
            else -> parseAtom()
        }

        fun parseAtom(): Double {
            if (peek() == '(') {
                pos++
                val v = parseExpr()
                if (peek() != ')') throw IllegalStateException()
                pos++
                return v
            }
            skip()
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == ',')) pos++
            val num = s.substring(start, pos)
            if (num.isEmpty()) throw IllegalStateException()
            // "1.234,5" (European thousands) or "1,5" → decimal comma; "1,234.5" → thousands comma.
            val normal = when {
                num.contains(',') && num.contains('.') ->
                    if (num.lastIndexOf(',') > num.lastIndexOf('.')) num.replace(".", "").replace(',', '.')
                    else num.replace(",", "")
                num.contains(',') -> num.replace(',', '.')
                else -> num
            }
            return normal.toDouble()
        }
    }
}
