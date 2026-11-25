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
import io.zachbr.dis4irc.bridge.message.IrcMessage
import io.zachbr.dis4irc.bridge.message.IrcSender
import io.zachbr.dis4irc.bridge.message.IrcSource
import io.zachbr.dis4irc.bridge.testBridge
import net.engio.mbassy.listener.Handler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

private const val CHANNEL = TEST_IRC_CHANNEL
private const val NICK = "bridge" // testBridge's nickname
private const val PARENT_TEXT = "https://example.com/products/workstation/"
private const val REPLY_TEXT = "the photos on that page are something else"

/**
 * Checks the bridge keeps recognising its own echoes after the server moves it onto its nick.
 *
 * In production (2026-11-24) the bridge posted its own IRC lines back to Discord as replies. It had reconnected while
 * a ghost held its nick, registered as nick + '`', and NickServ RECOVER (init commands) moved it onto its nick.
 * UnrealIRCd tells a client about its own nick change with sendto_one, so the prefix is a bare ":oldnick" with no
 * !user@host (src/modules/nick.c, svsnick.c, send.c:1043). The IRC library drops that line and kept the old nick.
 */
class IrcOwnNickTest {
    private val resources = ArrayList<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        resources.reversed().forEach { runCatching { it.close() } }
    }

    private data class Echo(val nick: String, val text: String, val replyTo: String?)

    private class Run(val bridge: Bridge, val server: FakeIrcServer, val logs: List<String>, val echoes: List<Echo>, val parentMsgId: String)

    private fun captureLogs(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        resources.add(AutoCloseable {
            logger.detachAppender(appender)
            logger.level = previousLevel
        })
        return appender
    }

    private fun run(nicksInUse: Set<String>, bareSelfNickPrefix: Boolean): Run {
        val appender = captureLogs()
        val server = FakeIrcServer(nicksInUse = nicksInUse, bareSelfNickPrefix = bareSelfNickPrefix).also { resources.add(it) }
        // prod's init commands, prod's reply settings
        val bridge = testBridge(server.port) {
            it.node("irc", "init-commands-list").set(listOf("PRIVMSG NickServ :RECOVER $NICK pw", "NICK $NICK", "PRIVMSG NickServ :IDENTIFY pw"))
            it.node("irc", "discord-reply-tags").set(true)
            it.node("irc", "discord-reply-context-with-tags").set(true)
            it.node("irc", "discord-reply-context-limit").set(250)
        }
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoinSent(CHANNEL), "bridge never sent JOIN $CHANNEL")

        // watch every channel message the IRC library hands the bridge, with its reply tag
        val echoes = CopyOnWriteArrayList<Echo>()
        val field = IrcPier::class.java.getDeclaredField("ircConn").apply { isAccessible = true }
        (field.get(bridge.ircConn) as Client).eventManager.registerEventListener(object {
            @Handler
            fun onMessage(event: ChannelMessageEvent) {
                val replyTo = event.source.getTag("+draft/reply").flatMap { it.value }.orElse(null)
                echoes.add(Echo(event.actor.nick, event.message, replyTo))
            }
        })
        Thread.sleep(1500) // let the init commands and JOIN settle

        // linkposter posts a link on IRC; the bridge posted it to Discord as message 50
        val parentMsgId = server.sendFromUser("linkposter", CHANNEL, PARENT_TEXT)
        bridge.discordConn.recordBridgedMessage(IrcMessage(PARENT_TEXT, IrcSender("linkposter", null), IrcSource(CHANNEL), Instant.now(), parentMsgId), 50)

        // replier replies to it on Discord
        val parent = DiscordMessage(PARENT_TEXT, DiscordSender("linkposter", 0L), DiscordSource("general", TEST_DISCORD_CHANNEL_ID), Instant.now(), sentTimestamp = OffsetDateTime.now(), messageId = 50)
        val reply = DiscordMessage(REPLY_TEXT, DiscordSender("replier", 7L), DiscordSource("general", TEST_DISCORD_CHANNEL_ID), Instant.now(), sentTimestamp = OffsetDateTime.now(), referencedMessage = parent, messageId = 51)
        bridge.ircConn.sendMessage(CHANNEL, BridgeMessage(reply))

        server.awaitSent(4) // RECOVER, IDENTIFY, context line, content line
        Thread.sleep(2500) // let the echoes come back
        return Run(bridge, server, appender.list.map { it.formattedMessage + (it.throwableProxy?.let { t -> " | " + t.message } ?: "") }, echoes.toList(), parentMsgId)
    }

    @Test
    fun recoveredNickAnnouncedWithBarePrefixIsFollowed() {
        val r = run(nicksInUse = setOf(NICK), bareSelfNickPrefix = true)

        // the IRC library still can't read the line, so the bridge follows the change itself
        assertTrue(r.logs.any { it.startsWith("Exception from IRC API") && "NICK message sent for non-user" in it }, "no KICL NICK exception: ${r.logs}")
        assertTrue(r.logs.contains("IRC nick is now $NICK (was $NICK`)"), "bare nick change not followed: ${r.logs}")
        assertEquals(NICK, r.server.serverNick)
        assertEquals(NICK, r.bridge.ircConn.getBotNick())

        // both lines still come back from "bridge" as replies to linkposter's message, and both are kept out
        val contextLine = "Reply to \"linkposter: $PARENT_TEXT\""
        val contentLine = "<replier> $REPLY_TEXT"
        val own = r.echoes.filter { it.nick == NICK }
        assertEquals(listOf(contextLine, contentLine), own.map { it.text })
        assertTrue(own.all { it.replyTo == r.parentMsgId }, "echoes not tagged as replies to the parent: $own")
        assertTrue(r.logs.none { it.startsWith("IRC MSG $CHANNEL $NICK:") }, "own echo reached sendToBridge: ${r.logs}")

        // the echo was matched as our own, so IRC replies to the Discord message can thread
        assertNotNull(r.bridge.messageIds.getIrcMsgId(51))
    }

    @Test
    fun barePrefixNickChangesAreOnlyFollowedForUs() {
        val appender = captureLogs()
        val server = FakeIrcServer().also { resources.add(it) }
        val bridge = testBridge(server.port)
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(CHANNEL), "bridge never joined $CHANNEL")

        // someone else's, and a server's, through the real event path
        server.sendRaw(":someoneelse NICK :hijack")
        server.sendRaw(":irc.example.net NICK :hijack")
        Thread.sleep(500)
        assertEquals(NICK, bridge.ircConn.getBotNick())

        // ours, even with different case
        server.sendRaw(":${NICK.uppercase()} NICK :${NICK}2")
        val deadline = System.currentTimeMillis() + 5000
        while (bridge.ircConn.getBotNick() != "${NICK}2" && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("${NICK}2", bridge.ircConn.getBotNick())
        assertEquals(1, appender.list.count { it.formattedMessage.startsWith("IRC nick is now") }, "followed the wrong change")
    }

    @Test
    fun fullPrefixSelfNickChangeKeepsEchoesOut() {
        val r = run(nicksInUse = setOf(NICK), bareSelfNickPrefix = false)

        assertEquals(NICK, r.bridge.ircConn.getBotNick())
        assertTrue(r.logs.none { it.startsWith("IRC MSG $CHANNEL $NICK:") }, "own echo reached sendToBridge: ${r.logs}")
        assertNotNull(r.bridge.messageIds.getIrcMsgId(51))
    }

    @Test
    fun noGhostKeepsEchoesOut() {
        val r = run(nicksInUse = emptySet(), bareSelfNickPrefix = true)

        assertEquals(NICK, r.bridge.ircConn.getBotNick())
        assertTrue(r.logs.none { it.startsWith("IRC MSG $CHANNEL $NICK:") }, "own echo reached sendToBridge: ${r.logs}")
        assertNotNull(r.bridge.messageIds.getIrcMsgId(51))
    }

    /**
     * Sends a Discord message through a bridge whose echoes come back from another nick, as after any drift in the IRC
     * library's idea of our nick, and returns the logs
     */
    private fun runWithDriftedEchoes(batched: Boolean): Pair<Bridge, List<String>> {
        val appender = captureLogs()
        val caps = listOf("message-tags", "echo-message", "labeled-response", "batch")
        val server = FakeIrcServer(caps, batchLabeledEchoes = batched, echoNick = "${NICK}_").also { resources.add(it) }
        val bridge = testBridge(server.port)
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(CHANNEL), "bridge never joined $CHANNEL")

        repeat(2) { i ->
            val message = DiscordMessage("line $i", DiscordSender("replier", 7L), DiscordSource("general", TEST_DISCORD_CHANNEL_ID), Instant.now(),
                sentTimestamp = OffsetDateTime.now(), messageId = 60L + i)
            bridge.ircConn.sendMessage(CHANNEL, BridgeMessage(message))
        }
        assertTrue(server.awaitSent(2).all { it.label != null }, "lines should carry labels")
        Thread.sleep(2500) // let the echoes come back
        return bridge to appender.list.map { "[${it.level}] ${it.formattedMessage}" }
    }

    @Test
    fun labeledEchoFromAnotherNickIsStillOurs() {
        val (bridge, logs) = runWithDriftedEchoes(batched = false)

        assertTrue(logs.none { it.contains("IRC MSG $CHANNEL ${NICK}_:") }, "labeled echo reached sendToBridge: $logs")
        assertNotNull(bridge.messageIds.getIrcMsgId(60))
        assertNotNull(bridge.messageIds.getIrcMsgId(61))
        // the drift is reported loudly once, then quietly
        val drift = "The IRC server echoed our line from ${NICK}_, but the IRC library has our nick as $NICK"
        assertEquals(1, logs.count { it == "[WARN] $drift" }, "drift not warned once: $logs")
        assertEquals(1, logs.count { it == "[DEBUG] $drift" }, "second drift not logged quietly: $logs")
    }

    @Test
    fun batchedLabeledEchoFromAnotherNickIsStillOurs() {
        val (bridge, logs) = runWithDriftedEchoes(batched = true)

        assertTrue(logs.none { it.contains("IRC MSG $CHANNEL ${NICK}_:") }, "batched labeled echo reached sendToBridge: $logs")
        assertNotNull(bridge.messageIds.getIrcMsgId(60))
        assertNotNull(bridge.messageIds.getIrcMsgId(61))
    }

    @Test
    fun unlabeledMessagesFromOthersStillReachTheBridge() {
        val appender = captureLogs()
        val caps = listOf("message-tags", "echo-message", "labeled-response", "batch")
        val server = FakeIrcServer(caps).also { resources.add(it) }
        val bridge = testBridge(server.port)
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(CHANNEL), "bridge never joined $CHANNEL")

        server.sendFromUser("linkposter", CHANNEL, "hello from irc")
        Thread.sleep(1000)
        assertTrue(appender.list.any { it.formattedMessage == "IRC MSG $CHANNEL linkposter: hello from irc" }, "foreign message was swallowed")
    }

    private fun startLabeledBridge(): Triple<Bridge, FakeIrcServer, ListAppender<ILoggingEvent>> {
        val appender = captureLogs()
        val server = FakeIrcServer(listOf("message-tags", "echo-message", "labeled-response", "batch")).also { resources.add(it) }
        val bridge = testBridge(server.port)
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(CHANNEL), "bridge never joined $CHANNEL")
        return Triple(bridge, server, appender)
    }

    @Test
    fun labeledActionEchoFromAnotherNickIsStillOurs() {
        val (_, server, appender) = startLabeledBridge()

        server.sendRaw("@label=d4i99;msgid=m1 :${NICK}_!bridge@bridge.host PRIVMSG $CHANNEL :\u0001ACTION waves\u0001")
        Thread.sleep(1000)
        assertTrue(appender.list.none { it.formattedMessage.startsWith("IRC CTCP") }, "labeled ACTION echo was bridged")
    }

    @Test
    fun reusedBatchReferenceIsNotTakenForOurs() {
        val (_, server, appender) = startLabeledBridge()

        // our echo in a labeled-response batch, without a msgid
        server.sendRaw("@label=d4i1 :irc.example.net BATCH +r1 labeled-response")
        server.sendRaw("@batch=r1 :$NICK!bridge@bridge.host PRIVMSG $CHANNEL :our line")
        server.sendRaw(":irc.example.net BATCH -r1")
        // later, another batch with the same reference carries someone else's message
        server.sendRaw(":irc.example.net BATCH +r1 chathistory $CHANNEL")
        server.sendRaw("@batch=r1;msgid=m2 :linkposter!user@user.host PRIVMSG $CHANNEL :from history")
        server.sendRaw(":irc.example.net BATCH -r1")
        Thread.sleep(1000)
        assertTrue(appender.list.any { it.formattedMessage == "IRC MSG $CHANNEL linkposter: from history" }, "message in a reused batch was swallowed")
    }
}
