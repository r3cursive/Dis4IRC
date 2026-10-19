/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import io.zachbr.dis4irc.bridge.AntiPingStyle
import io.zachbr.dis4irc.bridge.TEST_IRC_CHANNEL
import io.zachbr.dis4irc.bridge.message.DiscordMessage
import io.zachbr.dis4irc.bridge.message.DiscordSender
import io.zachbr.dis4irc.bridge.message.DiscordSource
import io.zachbr.dis4irc.bridge.message.IrcMessage
import io.zachbr.dis4irc.bridge.message.IrcSender
import io.zachbr.dis4irc.bridge.message.IrcSource
import io.zachbr.dis4irc.bridge.mutator.mutators.StripAntiPingCharacters
import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

private const val ZWSP = "​"

class IrcAntiPingTest {
    private fun discordMessage(sender: String, text: String, replyTo: DiscordMessage? = null) = DiscordMessage(
        text, DiscordSender(sender, 1L), DiscordSource("bridged", 2L), Instant.now(), sentTimestamp = OffsetDateTime.now(), referencedMessage = replyTo
    )

    @Test
    fun singleStylePutsOneZeroWidthSpaceInTheName() {
        assertEquals("v${ZWSP}irtual potato", IrcMessageFormatter.rebuildWithAntiPing("virtual potato", AntiPingStyle.SINGLE))
        // a surrogate pair stays whole, and the space goes after the first letter
        assertEquals("🍗b${ZWSP}ob", IrcMessageFormatter.rebuildWithAntiPing("🍗bob", AntiPingStyle.SINGLE))
    }

    @Test
    fun singleStyleGoesAfterTheFirstLetterOrDigit() {
        // a zero width space is a word break, so ".\u200Bkirk" would still highlight "kirk"
        assertEquals(".k${ZWSP}irk", IrcMessageFormatter.rebuildWithAntiPing(".kirk", AntiPingStyle.SINGLE))
        assertEquals("__k${ZWSP}irk", IrcMessageFormatter.rebuildWithAntiPing("__kirk", AntiPingStyle.SINGLE))
        assertEquals("k${ZWSP}irk", IrcMessageFormatter.rebuildWithAntiPing("kirk", AntiPingStyle.SINGLE))
        assertEquals("[7${ZWSP}7]", IrcMessageFormatter.rebuildWithAntiPing("[77]", AntiPingStyle.SINGLE))
        assertEquals("\uD83D\uDD25k${ZWSP}irk", IrcMessageFormatter.rebuildWithAntiPing("\uD83D\uDD25kirk", AntiPingStyle.SINGLE))
    }

    @Test
    fun singleStyleWithoutLettersGoesAfterTheFirstCharacter() {
        assertEquals(".$ZWSP..", IrcMessageFormatter.rebuildWithAntiPing("...", AntiPingStyle.SINGLE))
        assertEquals("\uD83D\uDD25$ZWSP\uD83D\uDD25", IrcMessageFormatter.rebuildWithAntiPing("\uD83D\uDD25\uD83D\uDD25", AntiPingStyle.SINGLE))
    }

    @Test
    fun singleStyleHandlesAstralLettersAndCombiningMarks() {
        // MATHEMATICAL BOLD SMALL K is a letter outside the BMP
        assertEquals("\uD835\uDC24${ZWSP}irk", IrcMessageFormatter.rebuildWithAntiPing("\uD835\uDC24irk", AntiPingStyle.SINGLE))
        // an accent combining with the first letter stays on it
        assertEquals(".e\u0301${ZWSP}lan", IrcMessageFormatter.rebuildWithAntiPing(".e\u0301lan", AntiPingStyle.SINGLE))
    }

    @Test
    fun singleStyleAfterTheFirstLetterInEveryLine() {
        val config = testBridge {
            it.node("irc", "anti-ping").set(true)
            it.node("irc", "anti-ping-style").set("single")
        }.config
        val original = discordMessage(".kirk", "first")
        val reply = discordMessage(".kirk", "second", original)

        assertEquals(listOf("Reply to \".k${ZWSP}irk: first\"", "<.k${ZWSP}irk> second"), IrcMessageFormatter.format(reply, config))
    }

    @Test
    fun oneCharacterNamesGetTheZeroWidthSpaceAfterThem() {
        assertEquals("a$ZWSP", IrcMessageFormatter.rebuildWithAntiPing("a", AntiPingStyle.SINGLE))
        assertEquals("a$ZWSP", IrcMessageFormatter.rebuildWithAntiPing("a", AntiPingStyle.EVERY_OTHER))
        assertEquals("", IrcMessageFormatter.rebuildWithAntiPing("", AntiPingStyle.SINGLE))
    }

    @Test
    fun everyOtherStyleIsUnchanged() {
        assertEquals("k${ZWSP}it${ZWSP}te${ZWSP}n", IrcMessageFormatter.rebuildWithAntiPing("kitten"))
        assertEquals("k${ZWSP}it${ZWSP}te${ZWSP}n", IrcMessageFormatter.rebuildWithAntiPing("kitten", AntiPingStyle.EVERY_OTHER))
    }

    @Test
    fun singleStyleWorksWithNickColours() {
        val sender = DiscordSender("abc", 1L)
        val plain = IrcMessageFormatter.createSenderPrefix(sender, antiPing = false, useColor = true)
        val single = IrcMessageFormatter.createSenderPrefix(sender, antiPing = true, useColor = true, antiPingStyle = AntiPingStyle.SINGLE)

        // the colour is picked from the real name, so only the zero width space differs
        assertEquals(plain.replace("abc", "a${ZWSP}bc"), single)
        assertTrue(single.startsWith("<\u0003") && single.endsWith("\u000F>"))
    }

    @Test
    fun singleStyleAppliesToSenderPrefixAndReplyContext() {
        val config = testBridge {
            it.node("irc", "anti-ping").set(true)
            it.node("irc", "anti-ping-style").set("single")
        }.config
        val original = discordMessage("virtual potato", "a long name used to vanish on IRC")
        val reply = discordMessage("IRCSync_Test_Bot_only", "it did", original)

        val lines = IrcMessageFormatter.format(reply, config)

        assertEquals(listOf("Reply to \"v${ZWSP}irtual potato: a long name used to vanish on IRC\"", "<I${ZWSP}RCSync_Test_Bot_only> it did"), lines)
        assertTrue(lines.all { it.count { c -> c == '​' } == 1 })
    }

    @Test
    fun strippingRemovesBothStyles() {
        val stripper = StripAntiPingCharacters()
        for (style in AntiPingStyle.entries) {
            val text = "<" + IrcMessageFormatter.rebuildWithAntiPing("virtual potato", style) + "> hi"
            val message = IrcMessage(text, IrcSender("someone", null), IrcSource(TEST_IRC_CHANNEL), Instant.now())
            stripper.mutate(message)
            assertEquals("<virtual potato> hi", message.contents)
        }
    }

    @Test
    fun styleIsConfigurable() {
        assertEquals(AntiPingStyle.EVERY_OTHER, testBridge().config.irc.antiPingStyle)
        assertEquals(AntiPingStyle.SINGLE, testBridge { it.node("irc", "anti-ping-style").set("single") }.config.irc.antiPingStyle)
        assertThrows(IllegalArgumentException::class.java) {
            testBridge { it.node("irc", "anti-ping-style").set("sometimes") }
        }
    }
}
