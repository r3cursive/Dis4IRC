/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime

private const val SPAM = "Mixed character spam"

/**
 * Checks that lines the IRC server refuses are logged, rather than silently lost
 */
class IrcRefusalTest {
    private val resources = ArrayList<AutoCloseable>()
    private val logger = LoggerFactory.getLogger("test") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    @AfterEach
    fun cleanup() {
        resources.reversed().forEach { runCatching { it.close() } }
    }

    private fun startBridge(server: FakeIrcServer, initCommands: List<String> = emptyList()): Bridge {
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        resources.add(AutoCloseable {
            logger.detachAppender(appender)
            logger.level = previousLevel
        })

        resources.add(server)
        val bridge = testBridge(server.port) { it.node("irc", "init-commands-list").set(initCommands) }
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

    private fun logged(level: Level) = appender.list.filter { it.level == level }.map { it.formattedMessage }

    private fun awaitLogged(text: String) {
        val deadline = System.currentTimeMillis() + 5000
        while (appender.list.none { text in it.formattedMessage } && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
    }

    @Test
    fun aRefusedLabeledLineIsLoggedWithItsStart() {
        val server = FakeIrcServer(listOf("message-tags", "echo-message", "labeled-response", "batch"), refuse = { if ("spam" in it) SPAM else null })
        val bridge = startBridge(server)

        bridge.send(1, "spam spam lovely spam, wonderful spam, lovely spam")
        bridge.send(2, "more spam")
        server.awaitSent(2)
        awaitLogged("more spam")

        // the start of the line, clipped to 40 characters
        val warnings = logged(Level.WARN).filter { "refused" in it }
        assertEquals(listOf("Fake refused a line in #bridge: $SPAM (\"<WhaTheSht> spam spam lovely spam, wonde...\")"), warnings)
        assertTrue(logged(Level.DEBUG).any { it.startsWith("Fake refused a line in #bridge: $SPAM") && "more spam" in it },
            "repeats of the same refusal are only debug lines")
        assertNull(bridge.messageIds.getIrcMsgId(1), "a refused line has no msgid")
    }

    @Test
    fun aRefusedUnlabeledLineIsLoggedWithoutItsText() {
        val server = FakeIrcServer(refuse = { if ("hunter2" in it || "spam" in it) SPAM else null })
        val bridge = startBridge(server, initCommands = listOf("PRIVMSG NickServ :IDENTIFY hunter2"))

        bridge.send(1, "spam")
        server.awaitSent(2)
        awaitLogged("#bridge")

        val warnings = logged(Level.WARN).filter { "refused" in it }
        assertTrue("Fake refused a line in #bridge: $SPAM" in warnings, "got $warnings")
        assertTrue(appender.list.none { "hunter2" in it.formattedMessage }, "a NickServ line must never be logged")
    }
}
