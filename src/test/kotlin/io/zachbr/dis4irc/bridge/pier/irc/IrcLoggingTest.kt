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
import io.zachbr.dis4irc.bridge.TEST_IRC_CHANNEL
import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

private const val SECRET = "hunter2"

/**
 * Checks that secrets the bridge sends to IRC never end up in its log, even at debug level
 */
class IrcLoggingTest {
    private val resources = ArrayList<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        resources.reversed().forEach { runCatching { it.close() } }
    }

    @Test
    fun ownEchoesAndInitCommandsAreNotLogged() {
        val logger = LoggerFactory.getLogger("test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        resources.add(AutoCloseable {
            logger.detachAppender(appender)
            logger.level = previousLevel
        })

        // echo-message makes the server send our NickServ IDENTIFY back to us
        val server = FakeIrcServer().also { resources.add(it) }
        val bridge = testBridge(server.port) {
            it.node("irc", "init-commands-list").set(listOf("PRIVMSG NickServ :IDENTIFY $SECRET"))
        }
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(TEST_IRC_CHANNEL))
        assertEquals("IDENTIFY $SECRET", server.awaitSent(1).single().text)

        bridge.ircConn.sendNotice("NickServ", "notice with $SECRET")
        Thread.sleep(2500) // let the echoes come back

        val messages = appender.list.map { it.formattedMessage }
        assertTrue(messages.any { it.startsWith("Sending raw init command 1 of 1 (PRIVMSG)") }, "init command not logged at all: $messages")
        assertTrue(messages.none { SECRET in it }, "secret was logged: ${messages.filter { SECRET in it }}")
    }
}
