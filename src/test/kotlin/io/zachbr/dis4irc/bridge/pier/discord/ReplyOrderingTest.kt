/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import io.zachbr.dis4irc.bridge.MessageIdCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val CHANNEL = "123"

class ReplyOrderingTest {
    private val direct = Executor { it.run() }
    private val errors = Collections.synchronizedList(ArrayList<Throwable>())
    private val messageIds = MessageIdCache()
    private val sent = Collections.synchronizedList(ArrayList<String>())

    private fun ordering(maxWait: Duration = Duration.ofSeconds(10)) = ReplyOrdering(direct, { errors.add(it) }, maxWait)

    /**
     * What the Discord pier does once Discord gave an IRC message's copy an id
     */
    private fun ReplyOrdering.discordSent(ircMsgId: String, discordMessageId: Long) {
        messageIds.put(discordMessageId, ircMsgId)
        inFlight.complete(ircMsgId, discordMessageId)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Test
    fun replyToInFlightParentGetsItsIdOnceTheSendCompletes() {
        val ordering = ordering()
        var parentSeen: Long? = null
        var parentWait: ParentWait? = null

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        // the IRC reply arrives before Discord has answered for the parent
        ordering.submit(CHANNEL, "reply", "parent") { wait ->
            parentWait = wait
            parentSeen = messageIds.getDiscordMessageId("parent")
            sent.add("reply")
        }
        assertEquals(listOf("parent"), sent, "the reply has to wait for its parent's id")

        ordering.discordSent("parent", 50L)

        assertEquals(listOf("parent", "reply"), sent)
        assertEquals(50L, parentSeen)
        assertEquals(ParentWait.ARRIVED, parentWait)
    }

    @Test
    fun laterMessagesStayBehindAWaitingReply() {
        val ordering = ordering()

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") { sent.add("reply") }
        ordering.submit(CHANNEL, "later", null) { sent.add("later") }
        // another channel isn't held up
        ordering.submit("456", "elsewhere", null) { sent.add("elsewhere") }
        assertEquals(listOf("parent", "elsewhere"), sent)

        ordering.discordSent("parent", 50L)
        ordering.submit(CHANNEL, "after", null) { sent.add("after") }

        assertEquals(listOf("parent", "elsewhere", "reply", "later", "after"), sent)
    }

    @Test
    fun replyFallsBackWhenTheParentTakesTooLong() {
        val ordering = ordering(maxWait = Duration.ofMillis(100))
        val done = CountDownLatch(1)
        var parentWait: ParentWait? = null

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") { wait ->
            parentWait = wait
            sent.add("reply")
            done.countDown()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS), "the reply should go out after the wait")
        assertEquals(ParentWait.TIMED_OUT, parentWait)
        assertEquals(listOf("parent", "reply"), sent)
    }

    @Test
    fun replyFallsBackWhenTheParentSendFails() {
        val ordering = ordering()
        var parentWait: ParentWait? = null

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") { wait ->
            parentWait = wait
            sent.add("reply")
        }
        ordering.inFlight.fail("parent")

        assertEquals(listOf("parent", "reply"), sent)
        assertEquals(ParentWait.FAILED, parentWait)
    }

    @Test
    fun repliesToMessagesNotInFlightGoOutRightAway() {
        val ordering = ordering()
        var parentWait: ParentWait? = null

        // the parent was sent long ago, or never came from IRC
        messageIds.put(50L, "old")
        ordering.submit(CHANNEL, "reply", "old") { wait ->
            parentWait = wait
            sent.add("reply")
        }

        assertEquals(listOf("reply"), sent)
        assertEquals(ParentWait.NONE, parentWait)
    }

    @Test
    fun aFailingSendDoesNotHoldUpTheChannel() {
        val ordering = ordering()

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") { throw IllegalStateException("boom") }
        ordering.submit(CHANNEL, "later", null) { sent.add("later") }
        ordering.discordSent("parent", 50L)

        assertEquals(listOf("parent", "later"), sent)
        assertEquals(1, errors.size)
    }

    @Test
    fun aSendThatThrowsReleasesItsReplies() {
        val ordering = ordering()
        var parentWait: ParentWait? = null

        // e.g. a message too long for Discord once mentions are expanded
        ordering.submit(CHANNEL, "parent", null) { throw IllegalStateException("too long") }
        ordering.submit(CHANNEL, "reply", "parent") { wait ->
            parentWait = wait
            sent.add("reply")
        }

        assertEquals(listOf("reply"), sent, "the reply mustn't wait for a parent that will never get an id")
        assertEquals(ParentWait.FAILED, parentWait)
        assertEquals(1, errors.size)
    }

    @Test
    fun aMessageTooLongToSendReleasesItsReplies() {
        val ordering = ordering()
        var parentWait: ParentWait? = null

        // what the Discord pier does with a message too long for Discord, before starting any thread for it
        ordering.submit(CHANNEL, "too long", "parent") { ordering.inFlight.fail("too long") }
        ordering.submit(CHANNEL, "reply", "too long") { wait ->
            parentWait = wait
            sent.add("reply")
        }

        assertEquals(listOf("reply"), sent, "the reply mustn't wait")
        assertEquals(ParentWait.FAILED, parentWait)
    }

    @Test
    fun aLaterStepOfASendThatThrowsReleasesItsReplies() {
        val ordering = ordering()
        val waits = ArrayList<ParentWait>()

        // the parent's send carries on later, such as once a thread for it was started
        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "waiting", "parent") { waits.add(it) }
        assertThrows(IllegalArgumentException::class.java) {
            ordering.guard("parent") { throw IllegalArgumentException("Content may not exceed 2000 characters!") }
        }
        ordering.submit(CHANNEL, "later", "parent") { waits.add(it) }

        assertEquals(listOf(ParentWait.FAILED, ParentWait.FAILED), waits, "neither reply should wait for the timeout")
    }

    @Test
    fun anErrorDoesNotStallTheChannel() {
        val ordering = ordering()

        ordering.submit(CHANNEL, "parent", null) { sent.add("parent") }
        ordering.submit(CHANNEL, "reply", "parent") { throw AssertionError("not an exception") }
        ordering.submit(CHANNEL, "second", "parent") { throw StackOverflowError() }
        ordering.submit(CHANNEL, "later", null) { sent.add("later") }
        ordering.discordSent("parent", 50L)
        ordering.submit(CHANNEL, "after", null) { sent.add("after") }

        assertEquals(listOf("parent", "later", "after"), sent)
        assertEquals(listOf("AssertionError", "StackOverflowError"), errors.map { it.javaClass.simpleName })
    }

    @Test
    fun aQueuedSendGetsItsFullWaitOnceDispatched() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val inFlight = InFlightSends(ttl = Duration.ofSeconds(10), clock = clock)

        inFlight.start("queued")
        // it waited 9 seconds behind a reply before it was sent
        clock.now = clock.now.plusSeconds(9)
        inFlight.dispatched("queued")
        clock.now = clock.now.plusSeconds(6)

        assertNotNull(inFlight.pending("queued"), "replies to it should still wait for its id")
    }

    @Test
    fun startingAgainKeepsTheSendInFlight() {
        val inFlight = InFlightSends()
        inFlight.start("a")
        val first = inFlight.pending("a")

        inFlight.start("a")
        assertSame(first, inFlight.pending("a"), "replies already waiting must not be orphaned")

        inFlight.fail("a")
        inFlight.start("a")
        assertNotSame(first, inFlight.pending("a"), "a failed send can be started again")
    }

    @Test
    fun inFlightSendsAreForgottenAfterAWhileAndBounded() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val inFlight = InFlightSends(capacity = 2, ttl = Duration.ofSeconds(10), clock = clock)

        inFlight.start("a")
        assertNotNull(inFlight.pending("a"))
        clock.now = clock.now.plusSeconds(11)
        assertNull(inFlight.pending("a"), "a send that never finished is forgotten")

        inFlight.start("b")
        inFlight.start("c")
        inFlight.start("d")
        assertNull(inFlight.pending("b"), "only the newest sends are kept")
        assertNotNull(inFlight.pending("d"))

        val pending = inFlight.pending("d")!!
        inFlight.complete("d", 7L)
        assertEquals(7L, pending.getNow(null))
        assertNull(inFlight.pending("d"), "finished sends are cleared")
    }
}
