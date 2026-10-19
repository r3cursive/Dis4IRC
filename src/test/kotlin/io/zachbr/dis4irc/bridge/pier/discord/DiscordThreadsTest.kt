/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.entities.channel.ChannelType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private const val CHANNEL = 100L
private const val OTHER_CHANNEL = 200L

class DiscordThreadsTest {
    private val thread = ThreadRef(threadId = 500L, channelId = CHANNEL)

    /**
     * Fake starter lookup that holds its callbacks until answered, as a request to Discord would
     */
    private class StarterLookup : (((Boolean) -> Unit) -> Unit) {
        val pending = ArrayList<(Boolean) -> Unit>()
        var calls = 0
        override fun invoke(callback: (Boolean) -> Unit) {
            calls++
            pending.add(callback)
        }

        fun answer(startedFromMessage: Boolean) = pending.removeAt(0)(startedFromMessage)
    }

    /**
     * Bridges a thread message the way the Discord pier does, recording the IRC parent each message gets
     */
    private fun DiscordThreadTracker.bridge(
        messageId: Long,
        lookup: (((Boolean) -> Unit) -> Unit),
        sent: MutableList<Pair<Long, Long?>>,
        inThreadReplyTo: Long? = null
    ) = whenRootKnown(thread.threadId, lookup) { startedFromMessage ->
        sent.add(messageId to parentFor(thread, messageId, inThreadReplyTo, startedFromMessage))
    }

    @Test
    fun threadNoticesAreNeverBridged() {
        assertTrue(isHiddenThreadNotice(MessageType.THREAD_CREATED, bridgeThreads = true))
        assertTrue(isHiddenThreadNotice(MessageType.THREAD_STARTER_MESSAGE, bridgeThreads = true))
        assertTrue(isHiddenThreadNotice(MessageType.THREAD_STARTER_MESSAGE, bridgeThreads = false))
        for (bridgeThreads in listOf(true, false)) {
            assertFalse(isHiddenThreadNotice(MessageType.DEFAULT, bridgeThreads))
            assertFalse(isHiddenThreadNotice(MessageType.INLINE_REPLY, bridgeThreads))
            assertFalse(isHiddenThreadNotice(MessageType.SLASH_COMMAND, bridgeThreads))
        }
    }

    @Test
    fun threadCreatedNoticeIsBridgedAsUpstreamWithoutThreads() {
        // without thread bridging it is the only sign on IRC that a thread was started
        assertFalse(isHiddenThreadNotice(MessageType.THREAD_CREATED, bridgeThreads = false))
    }

    @Test
    fun onlyPublicThreadsAreBridgedAndOnlyWhenEnabled() {
        assertTrue(isBridgeableChannel(ChannelType.TEXT, bridgeThreads = false))
        assertTrue(isBridgeableChannel(ChannelType.TEXT, bridgeThreads = true))
        assertFalse(isBridgeableChannel(ChannelType.GUILD_PUBLIC_THREAD, bridgeThreads = false))
        assertTrue(isBridgeableChannel(ChannelType.GUILD_PUBLIC_THREAD, bridgeThreads = true))
        assertFalse(isBridgeableChannel(ChannelType.GUILD_PRIVATE_THREAD, bridgeThreads = true))
        assertFalse(isBridgeableChannel(ChannelType.GUILD_NEWS_THREAD, bridgeThreads = true))
        assertFalse(isBridgeableChannel(ChannelType.PRIVATE, bridgeThreads = true))
        assertFalse(isBridgeableChannel(ChannelType.NEWS, bridgeThreads = true))
    }

    @Test
    fun anyOfTheBridgesWebhooksIsTheBridge() {
        val webhooks = listOf(7L, 8L)
        assertTrue(isBridgeAuthor(1L, selfUserId = 1L, webhookIds = webhooks))
        // a webhook posting into a thread of its channel, where the source channel is the thread
        assertTrue(isBridgeAuthor(8L, selfUserId = 1L, webhookIds = webhooks))
        assertFalse(isBridgeAuthor(9L, selfUserId = 1L, webhookIds = webhooks))
        assertFalse(isBridgeAuthor(9L, selfUserId = 1L, webhookIds = emptyList()))
    }

    @Test
    fun inlineRepliesCountOnlyWithinTheThread() {
        assertEquals(42L, inThreadReplyTarget(threadId = 500L, referencedMessageId = 42L, referencedChannelId = 500L))
        // the starter message lives in the parent channel but shares the thread's id
        assertEquals(500L, inThreadReplyTarget(threadId = 500L, referencedMessageId = 500L, referencedChannelId = CHANNEL))
        assertNull(inThreadReplyTarget(threadId = 500L, referencedMessageId = 42L, referencedChannelId = CHANNEL))
        assertNull(inThreadReplyTarget(threadId = 500L, referencedMessageId = null, referencedChannelId = null))
    }

    @Test
    fun threadStartedFromMessageHangsOffThatMessage() {
        val tracker = DiscordThreadTracker()

        assertEquals(500L, tracker.parentFor(thread, 501L, null, startedFromMessage = true))
        assertEquals(500L, tracker.parentFor(thread, 502L, null, startedFromMessage = true))
        assertEquals(501L, tracker.parentFor(thread, 503L, inThreadReplyTo = 501L, startedFromMessage = true))
        assertEquals(thread, tracker.threadStartedBy(500L))
    }

    @Test
    fun standaloneThreadHangsOffItsFirstBridgedMessage() {
        val tracker = DiscordThreadTracker()

        assertNull(tracker.parentFor(thread, 501L, null, startedFromMessage = false), "the first message is the root and goes out unthreaded")
        assertEquals(501L, tracker.parentFor(thread, 502L, null, startedFromMessage = false))
        assertEquals(502L, tracker.parentFor(thread, 503L, inThreadReplyTo = 502L, startedFromMessage = false))
        assertNull(tracker.threadStartedBy(500L), "the thread created notice is never bridged, so isn't a starter")
        assertNull(tracker.threadStartedBy(501L))
    }

    @Test
    fun rootIsStableWhenTheSameMessageIsSeenAgain() {
        val tracker = DiscordThreadTracker()

        assertNull(tracker.parentFor(thread, 501L, null, startedFromMessage = false))
        // slash command responses are bridged again when they are edited
        assertNull(tracker.parentFor(thread, 501L, null, startedFromMessage = false))
        assertEquals(501L, tracker.parentFor(thread, 502L, null, startedFromMessage = false))
    }

    @Test
    fun threadMessagesWaitInOrderForTheStarterLookup() {
        val tracker = DiscordThreadTracker()
        val lookup = StarterLookup()
        val sent = ArrayList<Pair<Long, Long?>>()

        tracker.bridge(501L, lookup, sent)
        tracker.bridge(502L, lookup, sent)
        assertTrue(sent.isEmpty(), "nothing goes out before the thread's root is known")

        lookup.answer(startedFromMessage = false)
        tracker.bridge(503L, lookup, sent)

        assertEquals(listOf(501L to null, 502L to 501L, 503L to 501L), sent)
        assertEquals(1, lookup.calls, "the starter should only be looked up once per thread")
    }

    @Test
    fun messagesArrivingWhileWaitingAreRunInOrder() {
        val tracker = DiscordThreadTracker()
        val lookup = StarterLookup()
        val sent = ArrayList<Pair<Long, Long?>>()

        // a message that arrives while the first waiting one is being bridged queues behind it
        tracker.whenRootKnown(thread.threadId, lookup) { startedFromMessage ->
            sent.add(501L to tracker.parentFor(thread, 501L, null, startedFromMessage))
            tracker.bridge(503L, lookup, sent)
        }
        tracker.bridge(502L, lookup, sent)
        lookup.answer(startedFromMessage = true)

        assertEquals(listOf(501L to 500L, 502L to 500L, 503L to 500L), sent)
        assertEquals(1, lookup.calls)
    }

    @Test
    fun lookupThatAnswersAtOnceIsFine() {
        val tracker = DiscordThreadTracker()
        val sent = ArrayList<Pair<Long, Long?>>()

        tracker.bridge(501L, { callback -> callback(true) }, sent)
        assertEquals(listOf(501L to 500L), sent)
    }

    @Test
    fun failingLookupCountsAsStandalone() {
        val tracker = DiscordThreadTracker()
        val sent = ArrayList<Pair<Long, Long?>>()

        assertThrows(IllegalStateException::class.java) {
            tracker.bridge(501L, { throw IllegalStateException("no access") }, sent)
        }
        tracker.bridge(502L, { fail("the root is known, no lookup needed") }, sent)

        assertEquals(listOf(501L to null, 502L to 501L), sent)
    }

    @Test
    fun failingMessageDoesNotHoldUpTheThread() {
        val tracker = DiscordThreadTracker()
        val lookup = StarterLookup()
        val sent = ArrayList<Pair<Long, Long?>>()

        tracker.bridge(501L, lookup, sent)
        tracker.whenRootKnown(thread.threadId, lookup) { throw IllegalStateException("bad message") }
        tracker.bridge(503L, lookup, sent)

        assertThrows(IllegalStateException::class.java) { lookup.answer(startedFromMessage = false) }
        tracker.bridge(504L, lookup, sent)

        assertEquals(listOf(501L to null, 503L to 501L, 504L to 501L), sent)
        assertEquals(1, lookup.calls)
    }

    @Test
    fun bridgedMessagesAreRecordedInTheirThread() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = false)
        tracker.recordMessage(600L, thread)

        assertEquals(thread, tracker.threadOf(501L))
        assertEquals(thread, tracker.threadOf(600L))
        assertNull(tracker.threadOf(999L))
    }

    @Test
    fun trackerIsBounded() {
        val tracker = DiscordThreadTracker(capacity = 2)
        tracker.recordMessage(1L, thread)
        tracker.recordMessage(2L, thread)
        tracker.recordMessage(3L, thread)
        assertNull(tracker.threadOf(1L))
        assertEquals(thread, tracker.threadOf(3L))

        tracker.parentFor(ThreadRef(10L, CHANNEL), 11L, null, startedFromMessage = false)
        tracker.parentFor(ThreadRef(20L, CHANNEL), 21L, null, startedFromMessage = false)
        tracker.parentFor(ThreadRef(30L, CHANNEL), 31L, null, startedFromMessage = false)
        // thread 10 was evicted, so its next message is treated as a new root
        assertNull(tracker.parentFor(ThreadRef(10L, CHANNEL), 12L, null, startedFromMessage = false))
    }

    @Test
    fun ircReplyToThreadMessageGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = false)

        assertEquals(ThreadTarget(thread, parentInThread = true), selectReplyThread(501L, CHANNEL, tracker) { null })
    }

    @Test
    fun ircReplyToMessagePostedIntoThreadGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.recordMessage(600L, thread)

        assertEquals(ThreadTarget(thread, parentInThread = true), selectReplyThread(600L, CHANNEL, tracker) { null })
    }

    @Test
    fun ircReplyToThreadStarterGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        assertEquals(ThreadTarget(thread, parentInThread = false), selectReplyThread(500L, CHANNEL, tracker) { null })
    }

    @Test
    fun ircReplyToStarterOfActiveThreadGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        val active = mapOf(500L to ActiveThread(CHANNEL, locked = false))

        assertEquals(ThreadTarget(thread, parentInThread = false), selectReplyThread(500L, CHANNEL, tracker) { active[it] })
    }

    @Test
    fun ircReplyToPlainMessageStaysInTheChannel() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        assertNull(selectReplyThread(42L, CHANNEL, tracker) { null })
    }

    @Test
    fun lockedThreadsFallBackToTheChannel() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = false)
        val active = mapOf(500L to ActiveThread(CHANNEL, locked = true))

        assertNull(selectReplyThread(501L, CHANNEL, tracker) { active[it] })
        assertNull(selectReplyThread(500L, CHANNEL, DiscordThreadTracker()) { active[it] })
    }

    @Test
    fun threadsOfOtherChannelsAreNeverTargeted() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)
        val active = mapOf(700L to ActiveThread(OTHER_CHANNEL, locked = false))

        // e.g. an IRC reply in one channel to a msgid from another
        assertNull(selectReplyThread(501L, OTHER_CHANNEL, tracker) { null })
        assertNull(selectReplyThread(500L, OTHER_CHANNEL, tracker) { null })
        assertNull(selectReplyThread(700L, CHANNEL, tracker) { active[it] })
    }
}
