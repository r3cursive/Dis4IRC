/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private const val DEFAULT_IN_FLIGHT_CAPACITY = 256
private val DEFAULT_WAIT: Duration = Duration.ofSeconds(10)

/**
 * Keeps track of IRC messages whose Discord copy is being sent, by IRC msgid, until Discord has given it an id.
 * Each send ends with the Discord message id, or null if it failed. A failed send is kept, so a reply that arrives
 * later knows its parent failed rather than never being sent.
 *
 * Entries are forgotten [ttl] after their send was submitted or dispatched, and at most [capacity] are kept.
 */
class InFlightSends(
    private val capacity: Int = DEFAULT_IN_FLIGHT_CAPACITY,
    private val ttl: Duration = DEFAULT_WAIT,
    private val clock: Clock = Clock.systemUTC()
) {
    private class Send(val result: CompletableFuture<Long?>, var updatedAt: Instant)

    private val sends = object : LinkedHashMap<String, Send>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Send>?): Boolean = size > capacity
    }

    /**
     * Marks a send in flight. A msgid already in flight keeps its entry, so replies waiting on it aren't orphaned.
     */
    @Synchronized
    fun start(ircMsgId: String) {
        val existing = sends[ircMsgId]
        if (existing == null || existing.result.isDone) {
            sends[ircMsgId] = Send(CompletableFuture(), clock.instant())
        }
    }

    /**
     * Restarts the time a send may take, from when it is actually dispatched rather than when it was queued
     */
    @Synchronized
    fun dispatched(ircMsgId: String) {
        sends[ircMsgId]?.takeIf { !it.result.isDone }?.updatedAt = clock.instant()
    }

    fun complete(ircMsgId: String, discordMessageId: Long) {
        val send = synchronized(this) { sends.remove(ircMsgId) } ?: return
        // outside the lock, as waiting sends may continue right away
        send.result.complete(discordMessageId)
    }

    fun fail(ircMsgId: String) {
        val send = synchronized(this) {
            sends[ircMsgId]?.also { it.updatedAt = clock.instant() }
        } ?: return
        send.result.complete(null)
    }

    /**
     * Gets the send of a message, finished or not, or null if it isn't tracked or was forgotten
     */
    @Synchronized
    fun pending(ircMsgId: String): CompletableFuture<Long?>? {
        val send = sends[ircMsgId] ?: return null
        if (send.updatedAt.plus(ttl).isBefore(clock.instant())) {
            sends.remove(ircMsgId)
            return null
        }
        return send.result
    }
}

/**
 * Runs sends in the order they were submitted, per key. A send can wait for something first, and every later send
 * with the same key waits behind it. Sends with nothing to wait for and nothing ahead of them run right away, on the
 * caller's thread.
 *
 * @param executor runs sends that had to wait, so they don't run on the thread that completed what they waited for
 * @param onError told about sends that throw, which don't hold up the ones behind them
 */
class OrderedSends(private val executor: Executor, private val onError: (Throwable) -> Unit) {
    private class Item(val ready: CompletableFuture<*>?, val send: () -> Unit)

    private val queues = HashMap<String, ArrayDeque<Item>>()
    private val draining = HashSet<String>()

    fun submit(key: String, ready: CompletableFuture<*>?, send: () -> Unit) {
        val mustWait = synchronized(this) {
            val queue = queues[key]
            if (queue != null) {
                queue.addLast(Item(ready, send))
                return
            }

            val waiting = ready != null && !ready.isDone
            if (waiting) {
                queues[key] = ArrayDeque(listOf(Item(ready, send)))
            }
            waiting
        }

        if (mustWait) {
            ready?.whenCompleteAsync({ _, _ -> drain(key) }, executor)
        } else {
            runSafely(send)
        }
    }

    private fun drain(key: String) {
        synchronized(this) {
            if (!draining.add(key)) {
                return
            }
        }

        var finished = false
        try {
            while (true) {
                var waitFor: CompletableFuture<*>? = null
                val next = synchronized(this) {
                    val queue = queues[key]
                    val head = queue?.firstOrNull()
                    if (queue == null || head == null) {
                        queues.remove(key)
                        draining.remove(key)
                        finished = true
                        return
                    }

                    if (head.ready != null && !head.ready.isDone) {
                        waitFor = head.ready
                        draining.remove(key)
                        finished = true
                        null
                    } else {
                        queue.removeFirst()
                    }
                }

                if (next == null) {
                    waitFor?.whenCompleteAsync({ _, _ -> drain(key) }, executor)
                    return
                }

                runSafely(next.send)
            }
        } finally {
            // only a fatal error gets here unfinished, so let another drain carry on with the rest of the queue
            if (!finished) {
                synchronized(this) {
                    draining.remove(key)
                }
                try {
                    executor.execute { drain(key) }
                } catch (ex: RejectedExecutionException) {
                    // shutting down, and the original error matters more
                }
            }
        }
    }

    private fun runSafely(send: () -> Unit) {
        try {
            send()
        } catch (ex: Throwable) {
            onError(ex)
            if (ex is VirtualMachineError) {
                throw ex
            }
        }
    }
}

/**
 * What became of the message an IRC reply was waiting for
 */
enum class ParentWait {
    /** The reply didn't wait, its parent wasn't in flight */
    NONE,

    /** The parent reached Discord, so its id is known */
    ARRIVED,

    /** The parent's send failed */
    FAILED,

    /** The parent took longer than the reply would wait */
    TIMED_OUT
}

/**
 * Makes sure an IRC reply never reaches Discord before the message it replies to, so it can be threaded under it.
 *
 * When an IRC message arrives, its send is marked in flight until Discord gives it an id. A reply to a message still in
 * flight waits for that, for at most [maxWait], before it is sent. Later messages to the same Discord channel wait
 * behind it, so the channel keeps the order they were sent on IRC.
 */
class ReplyOrdering(
    executor: Executor,
    onError: (Throwable) -> Unit,
    private val maxWait: Duration = DEFAULT_WAIT,
    val inFlight: InFlightSends = InFlightSends(ttl = maxWait)
) {
    private val sends = OrderedSends(executor, onError)

    /**
     * Sends a message to a Discord channel in order, after its parent's send if that is still in flight
     *
     * @param msgId the IRC msgid of the message, marked in flight until its send completes or fails
     * @param replyToMsgId the IRC msgid of the message it replies to, if any
     * @param send sends the message, told what became of the parent it waited for
     */
    fun submit(channelKey: String, msgId: String?, replyToMsgId: String?, send: (parentWait: ParentWait) -> Unit) {
        msgId?.let { inFlight.start(it) }
        val waiter = replyToMsgId?.let { inFlight.pending(it) }
            ?.thenApply { if (it == null) ParentWait.FAILED else ParentWait.ARRIVED }
            ?.completeOnTimeout(ParentWait.TIMED_OUT, maxWait.toMillis(), TimeUnit.MILLISECONDS)

        sends.submit(channelKey, waiter) {
            msgId?.let { inFlight.dispatched(it) }
            guard(msgId) { send(waiter?.getNow(ParentWait.TIMED_OUT) ?: ParentWait.NONE) }
        }
    }

    /**
     * Runs part of a message's send, which may also be a later step on a callback. If it throws, the message never
     * gets an id, so replies waiting on it are released rather than held until they time out.
     */
    fun guard(msgId: String?, send: () -> Unit) {
        try {
            send()
        } catch (ex: Throwable) {
            msgId?.let { inFlight.fail(it) }
            throw ex
        }
    }
}
