/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.zachbr.dis4irc.bridge.Bridge
import io.zachbr.dis4irc.bridge.TEST_DISCORD_CHANNEL_ID
import io.zachbr.dis4irc.bridge.TEST_IRC_CHANNEL
import io.zachbr.dis4irc.bridge.message.BridgeMessage
import io.zachbr.dis4irc.bridge.message.IrcMessage
import io.zachbr.dis4irc.bridge.message.IrcSender
import io.zachbr.dis4irc.bridge.message.IrcSource
import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Collections

private val CHANNEL = TEST_DISCORD_CHANNEL_ID.toString()
// what the Discord pier logs when it tries to send while Discord isn't connected, which no test connects
private const val NOT_CONNECTED = "Discord Connection has not been initialized yet!"

/**
 * Checks the Discord pier's side of keeping IRC replies behind their parents, without a Discord connection
 */
class DiscordPierOrderingTest {
    private val bridges = ArrayList<Bridge>()
    private val logger = LoggerFactory.getLogger("test") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private val order = Collections.synchronizedList(ArrayList<String>())

    init {
        logger.addAppender(appender)
    }

    @AfterEach
    fun cleanup() {
        logger.detachAppender(appender)
        bridges.forEach { it.discordConn.onShutdown() }
    }

    private fun bridge(threadIrcReplies: Boolean = true) = testBridge {
        it.node("discord-options", "thread-irc-replies").set(threadIrcReplies)
    }.also { bridges.add(it) }

    private fun ircMessage(msgId: String, replyTo: String? = null) =
        IrcMessage("text", IrcSender("someone", null), IrcSource(TEST_IRC_CHANNEL), Instant.now(), msgId, replyTo)

    private fun sendAttempts() = appender.list.count { it.formattedMessage == NOT_CONNECTED }

    private fun awaitOrder(size: Int) {
        val deadline = System.currentTimeMillis() + 5000
        while (order.size < size && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun withoutThreadIrcRepliesNothingWaits() {
        val bridge = bridge(threadIrcReplies = false)
        // a parent still being sent
        bridge.discordConn.replyOrdering.inFlight.start("parent")

        bridge.discordConn.sendMessage(CHANNEL, BridgeMessage(ircMessage("reply", replyTo = "parent")))

        assertEquals(1, sendAttempts(), "the reply should be sent at once, as upstream does")
        assertNull(bridge.discordConn.replyOrdering.inFlight.pending("reply"), "nothing should be marked in flight")
    }

    @Test
    fun withThreadIrcRepliesTheReplyWaitsForItsParent() {
        val bridge = bridge(threadIrcReplies = true)
        bridge.discordConn.replyOrdering.inFlight.start("parent")

        bridge.discordConn.sendMessage(CHANNEL, BridgeMessage(ircMessage("reply", replyTo = "parent")))
        assertEquals(0, sendAttempts(), "the reply should wait for its parent")

        bridge.discordConn.replyOrdering.inFlight.fail("parent")
        val deadline = System.currentTimeMillis() + 5000
        while (sendAttempts() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(1, sendAttempts(), "a failed parent lets the reply go")
    }

    @Test
    fun replySeesTheIdThePierRecords() {
        val bridge = bridge()
        val ordering = bridge.discordConn.replyOrdering
        var parentSeen: Long? = null

        ordering.submit(CHANNEL, "parent", null) { order.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") {
            parentSeen = bridge.messageIds.getDiscordMessageId("parent")
            order.add("reply")
        }

        // what the webhook or bot send does once Discord answers
        bridge.discordConn.recordBridgedMessage(ircMessage("parent"), 50L)
        awaitOrder(2)

        assertEquals(listOf("parent", "reply"), order)
        assertEquals(50L, parentSeen)
    }

    @Test
    fun aReplyToAQueuedMessageWaitsForThatMessageToo() {
        val bridge = bridge()
        val ordering = bridge.discordConn.replyOrdering
        var laterSeen: Long? = null

        ordering.submit(CHANNEL, "parent", null) { order.add("parent") }
        ordering.submit(CHANNEL, "first reply", "parent") { order.add("first reply") }
        ordering.submit(CHANNEL, "later", null) { order.add("later") }
        // a reply to a message that is itself still queued
        ordering.submit(CHANNEL, "reply to later", "later") {
            laterSeen = bridge.messageIds.getDiscordMessageId("later")
            order.add("reply to later")
        }

        bridge.discordConn.recordBridgedMessage(ircMessage("parent"), 50L)
        awaitOrder(3)
        assertEquals(listOf("parent", "first reply", "later"), order, "the last reply waits for later's id")

        bridge.discordConn.recordBridgedMessage(ircMessage("later"), 60L)
        awaitOrder(4)

        assertEquals(listOf("parent", "first reply", "later", "reply to later"), order)
        assertEquals(60L, laterSeen)
    }
}
