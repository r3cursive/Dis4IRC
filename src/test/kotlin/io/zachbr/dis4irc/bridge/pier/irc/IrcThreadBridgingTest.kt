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
import org.spongepowered.configurate.CommentedConfigurationNode
import java.time.Instant
import java.time.OffsetDateTime

private const val REPLY_TAG = "+draft/reply"
private const val RATIFIED_REPLY_TAG = "+reply"

/**
 * Runs Discord thread messages through the bridge and the real IRC pier against [FakeIrcServer], to check they go out
 * as IRCv3 reply chains. The Discord pier resolves each thread message's parent, here it is set directly.
 */
class IrcThreadBridgingTest {
    private val resources = ArrayList<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        resources.reversed().forEach { runCatching { it.close() } }
    }

    private fun startBridge(configure: (CommentedConfigurationNode) -> Unit = {}): Pair<FakeIrcServer, Bridge> {
        val server = FakeIrcServer().also { resources.add(it) }
        val bridge = testBridge(server.port) {
            it.node("discord-options", "bridge-threads").set(true)
            configure(it)
        }
        bridge.ircConn.start()
        resources.add(AutoCloseable { bridge.ircConn.onShutdown() })
        assertTrue(server.awaitJoin(TEST_IRC_CHANNEL), "bridge never joined $TEST_IRC_CHANNEL")
        return server to bridge
    }

    // thread messages are bridged with their parent channel as the source
    private fun discordMessage(id: Long, text: String, threadParentId: Long? = null, replyTo: DiscordMessage? = null) = DiscordMessage(
        text,
        DiscordSender("WhaTheSht", 5L),
        DiscordSource("bridged", TEST_DISCORD_CHANNEL_ID),
        Instant.now(),
        sentTimestamp = OffsetDateTime.now(),
        referencedMessage = replyTo,
        messageId = id,
        threadParentId = threadParentId
    )

    private fun Bridge.awaitIrcMsgId(discordMessageId: Long, timeoutMillis: Long = 10_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            messageIds.getIrcMsgId(discordMessageId)?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    @Test
    fun threadMessagesAreRepliesToTheThreadRoot() {
        val (server, bridge) = startBridge()

        // the thread's root, either the message it was started from or a standalone thread's first message
        bridge.submitMessage(BridgeMessage(discordMessage(10, "anyone watching the late game")))
        val rootMsgId = bridge.awaitIrcMsgId(10)
        assertNotNull(rootMsgId, "echo of the root was not recorded")

        bridge.submitMessage(BridgeMessage(discordMessage(11, "yeah, overtime", threadParentId = 10)))
        bridge.submitMessage(BridgeMessage(discordMessage(12, "what a finish", threadParentId = 10)))
        val sent = server.awaitSent(3)
        Thread.sleep(1500) // make sure no context lines follow

        assertEquals(listOf("<WhaTheSht> anyone watching the late game", "<WhaTheSht> yeah, overtime", "<WhaTheSht> what a finish"), server.sent.map { it.text })
        assertTrue(sent[0].clientTags.isEmpty(), "the root goes out unthreaded")
        for (message in sent.drop(1)) {
            assertEquals(mapOf(REPLY_TAG to rootMsgId, RATIFIED_REPLY_TAG to rootMsgId), message.clientTags)
        }
    }

    @Test
    fun inThreadRepliesAreRepliesToTheirTarget() {
        val (server, bridge) = startBridge()

        bridge.submitMessage(BridgeMessage(discordMessage(10, "root")))
        bridge.awaitIrcMsgId(10)
        val inThread = discordMessage(11, "first in thread", threadParentId = 10)
        bridge.submitMessage(BridgeMessage(inThread))
        val inThreadMsgId = bridge.awaitIrcMsgId(11)
        assertNotNull(inThreadMsgId)

        bridge.submitMessage(BridgeMessage(discordMessage(12, "replying inline", threadParentId = 11, replyTo = inThread)))
        val reply = server.awaitSent(3)[2]
        Thread.sleep(1500)

        assertEquals(3, server.sent.size, "a tagged reply needs no context line")
        assertEquals("<WhaTheSht> replying inline", reply.text)
        assertEquals(inThreadMsgId, reply.clientTags[REPLY_TAG])
        assertEquals(inThreadMsgId, reply.clientTags[RATIFIED_REPLY_TAG])
    }

    @Test
    fun echoMessageIsRequestedForThreadsAlone() {
        // thread replies on IRC need the msgids of our own lines even with the other reply options off
        val (server, _) = startBridge {
            it.node("irc", "discord-reply-tags").set(false)
            it.node("discord-options", "thread-irc-replies").set(false)
        }
        assertTrue("echo-message" in server.enabledCapabilities)
    }

    @Test
    fun threadMessageWithUnknownRootIsSentUntagged() {
        val (server, bridge) = startBridge()

        // e.g. the root was bridged before a restart
        bridge.submitMessage(BridgeMessage(discordMessage(11, "late to the thread", threadParentId = 999)))
        server.awaitSent(1)
        Thread.sleep(1500)

        val sent = server.sent.single()
        assertEquals("<WhaTheSht> late to the thread", sent.text)
        assertTrue(sent.clientTags.isEmpty())
    }
}
