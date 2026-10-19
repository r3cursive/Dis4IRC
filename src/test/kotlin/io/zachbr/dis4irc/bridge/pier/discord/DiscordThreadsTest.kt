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

    private fun plan(
        parentMessageId: Long?,
        channelId: Long,
        tracker: DiscordThreadTracker,
        startThreads: Boolean = false,
        canStartThreads: Boolean = true,
        bridgeThreads: Boolean = true,
        threadIrcReplies: Boolean = true,
        activeThread: (Long) -> ActiveThread? = { null }
    ) = planIrcReply(parentMessageId, channelId, bridgeThreads, threadIrcReplies, startThreads, canStartThreads, tracker, activeThread)

    private fun intoThread(thread: ThreadRef, parentInThread: Boolean) = IrcReplyPlan.IntoThread(ThreadTarget(thread, parentInThread))

    @Test
    fun ircReplyToThreadMessageGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = false)

        assertEquals(intoThread(thread, parentInThread = true), plan(501L, CHANNEL, tracker))
    }

    @Test
    fun ircReplyToMessagePostedIntoThreadGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.recordMessage(600L, thread)

        assertEquals(intoThread(thread, parentInThread = true), plan(600L, CHANNEL, tracker))
    }

    @Test
    fun ircReplyToThreadStarterGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        assertEquals(intoThread(thread, parentInThread = false), plan(500L, CHANNEL, tracker))
    }

    @Test
    fun ircReplyToStarterOfActiveThreadGoesIntoTheThread() {
        val tracker = DiscordThreadTracker()
        val active = mapOf(500L to ActiveThread(CHANNEL, locked = false))

        assertEquals(intoThread(thread, parentInThread = false), plan(500L, CHANNEL, tracker, activeThread = { active[it] }))
    }

    @Test
    fun ircReplyToPlainMessageStaysInTheChannel() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        assertEquals(IrcReplyPlan.Channel, plan(42L, CHANNEL, tracker))
    }

    @Test
    fun lockedThreadsFallBackToTheChannel() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = false)
        val active = mapOf(500L to ActiveThread(CHANNEL, locked = true))

        assertEquals(IrcReplyPlan.Channel, plan(501L, CHANNEL, tracker, activeThread = { active[it] }))
        assertEquals(IrcReplyPlan.Channel, plan(500L, CHANNEL, DiscordThreadTracker(), activeThread = { active[it] }))
    }

    @Test
    fun threadsOfOtherChannelsAreNeverTargeted() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)
        val active = mapOf(700L to ActiveThread(OTHER_CHANNEL, locked = false))

        // e.g. an IRC reply in one channel to a msgid from another
        assertEquals(IrcReplyPlan.Channel, plan(501L, OTHER_CHANNEL, tracker))
        assertEquals(IrcReplyPlan.Channel, plan(500L, OTHER_CHANNEL, tracker))
        assertEquals(IrcReplyPlan.Channel, plan(700L, CHANNEL, tracker, activeThread = { active[it] }))
    }

    @Test
    fun ircReplyToMessageWithoutThreadStartsOneWhenEnabled() {
        val tracker = DiscordThreadTracker()

        assertEquals(IrcReplyPlan.StartThread(42L), plan(42L, CHANNEL, tracker, startThreads = true))
        // without permission to start threads, or with the option off, the reply goes to the channel as before
        assertEquals(IrcReplyPlan.Channel, plan(42L, CHANNEL, tracker, startThreads = true, canStartThreads = false))
        assertEquals(IrcReplyPlan.Channel, plan(42L, CHANNEL, tracker, startThreads = false))
        assertEquals(IrcReplyPlan.Channel, plan(42L, CHANNEL, tracker, startThreads = true, bridgeThreads = false))
        assertEquals(IrcReplyPlan.Channel, plan(null, CHANNEL, tracker, startThreads = true))
    }

    @Test
    fun ircRepliesArePlainMessagesWithoutThreadIrcReplies() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        // as upstream: no threads entered, none started
        assertEquals(IrcReplyPlan.Channel, plan(501L, CHANNEL, tracker, threadIrcReplies = false))
        assertEquals(IrcReplyPlan.Channel, plan(500L, CHANNEL, tracker, threadIrcReplies = false))
        assertEquals(IrcReplyPlan.Channel, plan(42L, CHANNEL, tracker, startThreads = true, threadIrcReplies = false))
    }

    @Test
    fun recentFailuresExpire() {
        val clock = object : java.time.Clock() {
            var now: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?): java.time.Clock = this
            override fun instant(): java.time.Instant = now
        }
        val failures = RecentFailures(java.time.Duration.ofMinutes(10), capacity = 2, clock = clock)

        assertFalse(failures.isRecent(42L))
        assertFalse(failures.record(42L), "the first failure is logged loudly")
        assertTrue(failures.isRecent(42L))
        assertTrue(failures.record(42L), "repeats are logged quietly")

        clock.now = clock.now.plus(java.time.Duration.ofMinutes(11))
        assertFalse(failures.isRecent(42L), "after a while it is tried again")

        failures.record(1L)
        failures.record(2L)
        assertFalse(failures.isRecent(42L))
        assertFalse(failures.record(42L), "evicted failures count as new")
    }

    @Test
    fun existingThreadsAreUsedInsteadOfStartingOne() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)

        assertEquals(intoThread(thread, parentInThread = true), plan(501L, CHANNEL, tracker, startThreads = true))
        assertEquals(intoThread(thread, parentInThread = false), plan(500L, CHANNEL, tracker, startThreads = true))
    }

    @Test
    fun lockedThreadIsNotReplacedByANewOne() {
        val tracker = DiscordThreadTracker()
        tracker.parentFor(thread, 501L, null, startedFromMessage = true)
        val active = mapOf(500L to ActiveThread(CHANNEL, locked = true))

        // a message can only have one thread, so the reply goes to the channel
        assertEquals(IrcReplyPlan.Channel, plan(500L, CHANNEL, tracker, startThreads = true, activeThread = { active[it] }))
    }

    @Test
    fun startedThreadsTakeLaterReplies() {
        val tracker = DiscordThreadTracker()
        tracker.recordStartedThread(ThreadRef(42L, CHANNEL))

        assertEquals(intoThread(ThreadRef(42L, CHANNEL), parentInThread = false), plan(42L, CHANNEL, tracker, startThreads = true))
        // Discord messages typed in the new thread go to IRC as replies to the message it was started on
        assertEquals(42L, tracker.parentFor(ThreadRef(42L, CHANNEL), 43L, null, startedFromMessage = false))
    }

    /**
     * Fake thread start that holds its callbacks until answered, as a request to Discord would
     */
    private class FakeThreadStart : ((ThreadStartResult) -> Unit) -> Unit {
        val pending = ArrayList<(ThreadStartResult) -> Unit>()
        override fun invoke(callback: (ThreadStartResult) -> Unit) {
            pending.add(callback)
        }

        fun answer(result: ThreadStartResult) = pending.removeAt(0)(result)
    }

    @Test
    fun repliesArrivingBeforeTheThreadExistsStartOnlyOne() {
        val starts = ThreadStarts()
        val start = FakeThreadStart()
        val sent = ArrayList<Pair<String, Long?>>()

        starts.whenStarted(42L, start) { sent.add("first" to it) }
        starts.whenStarted(42L, start) { sent.add("second" to it) }
        assertTrue(starts.joinIfStarting(42L) { sent.add("third" to it) })
        assertEquals(1, start.pending.size, "only one thread should be started")
        assertTrue(sent.isEmpty())

        start.answer(ThreadStartResult.Started(42L))

        assertEquals(listOf("first" to 42L, "second" to 42L, "third" to 42L), sent)
        assertFalse(starts.joinIfStarting(42L) { fail("nothing is starting any more") })
    }

    @Test
    fun existingThreadIsResolvedByTheMessageId() {
        val starts = ThreadStarts()
        val sent = ArrayList<Long?>()

        // Discord says the message already has a thread, which has the message's id
        starts.whenStarted(42L, { callback -> callback(ThreadStartResult.AlreadyExists) }) { sent.add(it) }
        assertEquals(listOf<Long?>(42L), sent)
    }

    @Test
    fun failedThreadStartFallsBackForEveryWaitingReply() {
        val starts = ThreadStarts()
        val start = FakeThreadStart()
        val sent = ArrayList<Long?>()

        starts.whenStarted(42L, start) { sent.add(it) }
        starts.whenStarted(42L, start) { sent.add(it) }
        start.answer(ThreadStartResult.Failed)
        assertEquals(listOf<Long?>(null, null), sent)

        assertThrows(IllegalStateException::class.java) {
            starts.whenStarted(43L, { throw IllegalStateException("missing permission") }) { sent.add(it) }
        }
        assertEquals(listOf<Long?>(null, null, null), sent)
    }

    @Test
    fun threadNamesComeFromTheMessageText() {
        assertEquals("anyone watching the late game", threadNameFrom("anyone   watching\nthe late game"))
        assertEquals("look at this", threadNameFrom("<@123456> **look** at _this_ <#987>"))
        assertEquals("the docs say :thinking:", threadNameFrom("[the docs](https://example.com) say <:thinking:111>"))
        assertEquals("quoted heading", threadNameFrom("> ## quoted heading"))
        assertEquals("snake_case stays", threadNameFrom("snake_case stays"))
        assertEquals(DEFAULT_THREAD_NAME, threadNameFrom("<@123> <@&456>"))
        assertEquals(DEFAULT_THREAD_NAME, threadNameFrom("   "))
    }

    @Test
    fun longThreadNamesAreCutAtAWord() {
        val name = threadNameFrom("word ".repeat(40))
        assertTrue(name.length <= 60, "too long: ${name.length}")
        assertTrue(name.endsWith("word"))

        // one long word is cut to Discord's 100 char limit without splitting a character
        for (start in listOf("", "a")) {
            val emoji = threadNameFrom(start + "\uD83D\uDE00".repeat(80))
            assertTrue(emoji.length <= 100, "too long: ${emoji.length}")
            assertFalse(Character.isHighSurrogate(emoji.last()))
            assertTrue(emoji.length >= 99)
        }
    }
}
