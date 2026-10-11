package com.otter.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dependency-free JSON reader that the carousel payload parsing relies on. */
class MiniJsonTest {

    @Test
    fun readsNestedStructuresAndScalars() {
        val value = MiniJson.parse("""{"a":[1,2.5,-3e2,true,false,null,"s"],"b":{"c":"d"}}""") as MiniJson.Obj
        val array = value.arr("a")!!
        assertEquals(7, array.size)
        assertTrue(array.items[3] is MiniJson.Bool)
        assertTrue(array.items[5] is MiniJson.Null)
        assertEquals("d", value.obj("b")!!.string("c"))
    }

    @Test
    fun handlesEscapesIncludingUnicodeAndSurrogatePairs() {
        val value = MiniJson.parse(
            """{"url":"https:\/\/x.cdninstagram.com\/v\/a.jpg?stp=1\u0026oe=2","emoji":"\uD83D\uDE00"}"""
        ) as MiniJson.Obj
        assertEquals("https://x.cdninstagram.com/v/a.jpg?stp=1&oe=2", value.string("url"))
        assertEquals("😀", value.string("emoji"))
    }

    @Test
    fun acceptsWhitespaceAndEscapedQuotes() {
        val value = MiniJson.parse("{\"text\" : \"say \\\"hi\\\"\"}") as MiniJson.Obj
        assertEquals("say \"hi\"", value.string("text"))
    }

    @Test
    fun returnsNullForMalformedPayloadsInsteadOfThrowing() {
        assertNull(MiniJson.parseOrNull("{"))
        assertNull(MiniJson.parseOrNull("{\"a\":}"))
        assertNull(MiniJson.parseOrNull("<html>not json</html>"))
        assertNull(MiniJson.parseOrNull(""))
    }

    @Test
    fun refusesAbsurdlyNestedPayloads() {
        val deep = "[".repeat(500) + "]".repeat(500)
        assertNull(MiniJson.parseOrNull(deep))
    }
}
