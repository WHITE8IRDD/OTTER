package com.otter.app.engine

/**
 * Small, dependency-free JSON reader.
 *
 * Instagram payloads are ordinary JSON; reading them without org.json keeps the
 * whole extraction path free of platform APIs, which means the exact code that
 * parses a post can be compiled and verified on a plain JVM.
 */
internal object MiniJson {

    sealed class Value

    class Obj(val fields: Map<String, Value>) : Value() {
        fun get(key: String): Value? = fields[key]
        fun has(key: String): Boolean = fields.containsKey(key)
        fun string(key: String): String? = (fields[key] as? Str)?.value
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
        fun int(key: String, default: Int = -1): Int = when (val value = fields[key]) {
            is Num -> value.value.toInt()
            is Str -> value.value.toIntOrNull() ?: default
            else -> default
        }
    }

    class Arr(val items: List<Value>) : Value() {
        val size: Int get() = items.size
        fun obj(index: Int): Obj? = items.getOrNull(index) as? Obj
        fun arr(index: Int): Arr? = items.getOrNull(index) as? Arr
        fun string(index: Int): String? = (items.getOrNull(index) as? Str)?.value
    }

    class Str(val value: String) : Value()

    class Num(val value: Double) : Value()

    class Bool(val value: Boolean) : Value()

    object Null : Value()

    class JsonException(message: String) : Exception(message)

    fun parseOrNull(text: String): Value? = try {
        Reader(text).parseDocument()
    } catch (e: JsonException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    fun parse(text: String): Value = Reader(text).parseDocument()

    private class Reader(private val text: String) {
        private var pos = 0
        private var depth = 0

        fun parseDocument(): Value {
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): Value {
            skipWhitespace()
            if (pos >= text.length) throw JsonException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun parseObject(): Obj {
            enter()
            pos++ // {
            val fields = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                leave()
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("expected a key at $pos")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') throw JsonException("expected ':' at $pos")
                pos++
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        leave()
                        return Obj(fields)
                    }
                    else -> throw JsonException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): Arr {
            enter()
            pos++ // [
            val items = ArrayList<Value>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                leave()
                return Arr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        leave()
                        return Arr(items)
                    }
                    else -> throw JsonException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val builder = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return builder.toString()
                    }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) throw JsonException("unterminated escape")
                        when (val escape = text[pos]) {
                            '"', '\\', '/' -> builder.append(escape)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw JsonException("bad unicode escape")
                                val code = text.substring(pos + 1, pos + 5).toIntOrNull(16)
                                    ?: throw JsonException("bad unicode escape")
                                builder.append(code.toChar())
                                pos += 4
                            }
                            else -> builder.append(escape)
                        }
                        pos++
                    }
                    else -> {
                        builder.append(c)
                        pos++
                    }
                }
            }
            throw JsonException("unterminated string")
        }

        private fun parseNumber(): Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonException("bad number '$raw'")
            return Num(value)
        }

        private fun literal(word: String, value: Value): Value {
            if (!text.startsWith(word, pos)) throw JsonException("expected '$word' at $pos")
            pos += word.length
            return value
        }

        private fun enter() {
            depth++
            if (depth > 200) throw JsonException("payload nested too deeply")
        }

        private fun leave() {
            depth--
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else throw JsonException("unexpected end of input")

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
    }
}
