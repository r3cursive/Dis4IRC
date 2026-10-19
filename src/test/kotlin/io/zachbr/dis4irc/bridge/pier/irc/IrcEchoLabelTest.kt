/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import io.zachbr.dis4irc.bridge.Bridge
import io.zachbr.dis4irc.bridge.TEST_DISCORD_CHANNEL_ID
import io.zachbr.dis4irc.bridge.TEST_IRC_CHANNEL
import io.zachbr.dis4irc.bridge.message.BridgeMessage
import io.zachbr.dis4irc.bridge.message.DiscordMessage
import io.zachbr.dis4irc.bridge.message.DiscordSender
import io.zachbr.dis4irc.bridge.message.DiscordSource
import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kitteh.irc.client.library.util.Format
import java.time.Instant
import java.time.OffsetDateTime

private val LABELED_CAPS = listOf("message-tags", "echo-message", "labeled-response", "batch")

/**
 * Runs the real IRC pier against [FakeIrcServer] to check echoes the server altered still map to their Discord message
 */
class IrcEchoLabelTest {
    private val resources = ArrayList<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        resources.reversed().forEach { runCatching { it.close() } }
    }

    private fun startBridge(server: FakeIrcServer, nickColors: Boolean = false): Bridge {
        resources.add(server)
        val bridge = testBridge(server.port) { it.node("irc", "use-nickname-colors").set(nickColors) }
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(TEST_IRC_CHANNEL), "bridge never joined $TEST_IRC_CHANNEL")
        return bridge
    }

    private fun Bridge.send(id: Long, text: String) {
        val message = DiscordMessage(text, DiscordSender("WhaTheSht", 5L), DiscordSource("bridged", TEST_DISCORD_CHANNEL_ID), Instant.now(),
            sentTimestamp = OffsetDateTime.now(), messageId = id)
        ircConn.sendMessage(TEST_IRC_CHANNEL, BridgeMessage(message))
    }

    private fun Bridge.awaitIrcMsgId(discordMessageId: Long, timeoutMillis: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            messageIds.getIrcMsgId(discordMessageId)?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    @Test
    fun labeledEchoWithAlteredTextStillMaps() {
        val server = FakeIrcServer(LABELED_CAPS, rewriteEcho = { it.replace("darn", "****") })
        val bridge = startBridge(server)

        bridge.send(1, "darn that referee")
        val sent = server.awaitSent(1).single()

        assertNotNull(sent.label, "tracked lines should carry a label")
        assertEquals(sent.msgId, bridge.awaitIrcMsgId(1))
    }

    @Test
    fun batchedLabeledEchoStillMaps() {
        val server = FakeIrcServer(LABELED_CAPS, rewriteEcho = { "$it [edited]" }, batchLabeledEchoes = true)
        val bridge = startBridge(server)

        bridge.send(1, "first")
        val sent = server.awaitSent(1).single()

        assertEquals(sent.msgId, bridge.awaitIrcMsgId(1))
    }

    @Test
    fun echoWithStrippedColoursMapsWithoutLabels() {
        // no labeled-response, and the channel strips colours (+S) from the coloured nick prefix
        val server = FakeIrcServer(rewriteEcho = Format::stripAll)
        val bridge = startBridge(server, nickColors = true)

        bridge.send(1, "colourful")
        val sent = server.awaitSent(1).single()

        assertNull(sent.label)
        assertNotEquals(Format.stripAll(sent.text), sent.text, "the test needs a coloured line")
        assertEquals(sent.msgId, bridge.awaitIrcMsgId(1))
    }

    @Test
    fun antiPingedMentionsAreSentAndTrackedAsIs() {
        // a Discord mention whose name was anti-pinged, matched by text as there are no labels
        val server = FakeIrcServer()
        val bridge = startBridge(server)

        bridge.send(1, "@.k\u200Birk look at this")
        val sent = server.awaitSent(1).single()

        assertEquals("<WhaTheSht> @.k\u200Birk look at this", sent.text)
        assertEquals(sent.msgId, bridge.awaitIrcMsgId(1))
    }
}
