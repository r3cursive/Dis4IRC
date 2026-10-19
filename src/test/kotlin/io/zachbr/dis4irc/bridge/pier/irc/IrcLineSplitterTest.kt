/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kitteh.irc.client.library.util.Cutter

// what's left of a 512 byte line for "#bridge", with a typical nick!user@host
private const val BUDGET = 466

class IrcLineSplitterTest {
    private fun assertFits(parts: List<String>, maxBytes: Int) {
        for (part in parts) {
            assertTrue(IrcLineSplitter.utf8Length(part) <= maxBytes, "part is ${IrcLineSplitter.utf8Length(part)} bytes: $part")
            assertFalse(part.isEmpty())
            // no lone surrogates from a split pair
            assertFalse(Character.isLowSurrogate(part.first()) || Character.isHighSurrogate(part.last()), "split a surrogate pair: $part")
        }
    }

    @Test
    fun shortTextIsUntouched() {
        assertEquals(listOf("hello there"), IrcLineSplitter.split("hello there", BUDGET))
    }

    @Test
    fun asciiTextSplitsLikeTheIrcLibrary() {
        val cutter = Cutter.DefaultWordCutter()
        val words = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. ".repeat(12)
        val noSpaces = "IconfessthatIdonotentirelyapproveofthisConstitutionatpresent;but,sir,IamnotsureIshallneverapproveit,for,havinglivedlong"
        for ((input, size) in listOf(words.trim() to 150, words.trim() to BUDGET, noSpaces to 40)) {
            assertEquals(cutter.split(input, size), IrcLineSplitter.split(input, size))
        }
    }

    @Test
    fun emojiRunIsSplitBetweenCodePoints() {
        // the IRC library throws StringIndexOutOfBoundsException on this, losing the whole message
        val emoji = "😀".repeat(120)
        val parts = IrcLineSplitter.split("<WhaTheSht> $emoji", BUDGET)

        assertFits(parts, BUDGET)
        // the long word is cut, so its parts join back without spaces
        assertEquals("<WhaTheSht> $emoji", parts.joinToString(""))
    }

    @Test
    fun japaneseTextFitsInBytes() {
        // 3 bytes a character, so 150 characters don't fit even though they are under 466 chars
        val japanese = "日本語のテキスト".repeat(19)
        val parts = IrcLineSplitter.split("<WhaTheSht> $japanese", BUDGET)

        // the text fits on a line of its own, so it wraps at the space
        assertEquals(listOf("<WhaTheSht>", japanese), parts)

        val longer = "日本語のテキスト".repeat(30)
        val longerParts = IrcLineSplitter.split("<WhaTheSht> $longer", BUDGET)
        assertFits(longerParts, BUDGET)
        assertEquals("<WhaTheSht> $longer", longerParts.joinToString(""))
    }

    @Test
    fun longAccentedWordFitsInBytes() {
        // the IRC library's cutter cuts long words by chars and produced 880 byte parts from this
        val word = "é".repeat(600)
        val parts = IrcLineSplitter.split("<WhaTheSht> $word", BUDGET)

        assertFits(parts, BUDGET)
        // the long word is cut, so its parts join back without spaces
        assertEquals("<WhaTheSht> $word", parts.joinToString(""))
    }

    @Test
    fun countsUtf8Bytes() {
        assertEquals(0, IrcLineSplitter.utf8Length(""))
        assertEquals(3, IrcLineSplitter.utf8Length("abc"))
        assertEquals(2, IrcLineSplitter.utf8Length("é"))
        assertEquals(3, IrcLineSplitter.utf8Length("日"))
        assertEquals(4, IrcLineSplitter.utf8Length("😀"))
        assertEquals(3, IrcLineSplitter.utf8Length("​"))
    }
}
