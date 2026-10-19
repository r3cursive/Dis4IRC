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

private const val DEFAULT_CAPACITY = 2048

/**
 * A Discord thread and the text channel it belongs to
 */
data class ThreadRef(val threadId: Long, val channelId: Long)

/**
 * What the Discord cache knows about an active thread
 */
data class ActiveThread(val channelId: Long, val locked: Boolean)

/**
 * Where an IRC reply goes when its parent is in, or starts, a Discord thread
 *
 * @param parentInThread whether the parent message itself is in the thread, so it can be replied to there
 */
data class ThreadTarget(val thread: ThreadRef, val parentInThread: Boolean)

/**
 * Checks if a message is one of Discord's thread notices that shouldn't be bridged. A thread starter message only
 * repeats the message the thread was started from, so it is never bridged. A thread created notice only carries the
 * thread's name. It is dropped when threads are bridged, since the thread's messages then show the thread on IRC.
 * Otherwise it is bridged as upstream does, as the only sign on IRC that a thread exists.
 */
fun isHiddenThreadNotice(type: MessageType, bridgeThreads: Boolean): Boolean {
    return when (type) {
        MessageType.THREAD_STARTER_MESSAGE -> true
        MessageType.THREAD_CREATED -> bridgeThreads
        else -> false
    }
}

/**
 * Checks if messages from a channel of this type are bridged. Text channels always are, public threads only when
 * thread bridging is enabled. Private threads never are, as their members expect them to stay private.
 */
fun isBridgeableChannel(type: ChannelType, bridgeThreads: Boolean): Boolean {
    return when (type) {
        ChannelType.TEXT -> true
        ChannelType.GUILD_PUBLIC_THREAD -> bridgeThreads
        else -> false
    }
}

/**
 * Checks if a Discord message author is this bridge, either the bot itself or any of its webhooks.
 * Webhooks also post into the threads of their channel, so every webhook is checked, not just the source channel's.
 */
fun isBridgeAuthor(authorId: Long, selfUserId: Long, webhookIds: Collection<Long>): Boolean {
    return authorId == selfUserId || authorId in webhookIds
}

/**
 * Gets the message a thread message replies to inline, if that message is part of the thread.
 * That includes the message the thread was started from, which shares the thread's id but lives in the parent channel.
 */
fun inThreadReplyTarget(threadId: Long, referencedMessageId: Long?, referencedChannelId: Long?): Long? {
    if (referencedMessageId == null) {
        return null
    }

    return referencedMessageId.takeIf { referencedChannelId == threadId || it == threadId }
}

/**
 * Picks the thread an IRC reply should be posted into, given the Discord copy of the message it replies to.
 * Returns null if the parent isn't part of a thread we can post to in the given channel.
 *
 * @param activeThread looks up a thread in the Discord cache, which holds every active thread the bot can see
 */
fun selectReplyThread(parentMessageId: Long, channelId: Long, tracker: DiscordThreadTracker, activeThread: (Long) -> ActiveThread?): ThreadTarget? {
    val inThread = tracker.threadOf(parentMessageId)
    val thread = inThread
        ?: tracker.threadStartedBy(parentMessageId)
        // a thread started from a message has the same id as the message
        ?: activeThread(parentMessageId)?.let { ThreadRef(parentMessageId, it.channelId) }
        ?: return null

    if (thread.channelId != channelId || activeThread(thread.threadId)?.locked == true) {
        return null
    }

    return ThreadTarget(thread, parentInThread = inThread != null)
}

/**
 * Keeps track of bridged Discord threads, so they can be bridged to IRC as reply chains and back.
 *
 * Each thread has a root, the message its IRC reply chain hangs off. For a thread started from a message that is the
 * message itself, which was bridged from the parent channel. For a standalone thread it is the first message bridged
 * from the thread. Entries are evicted least-recently-used first, nothing is persisted.
 */
class DiscordThreadTracker(private val capacity: Int = DEFAULT_CAPACITY) {
    private data class ThreadInfo(val channelId: Long, val rootId: Long)

    private val threads = lruMap<Long, ThreadInfo>()
    private val messageThreads = lruMap<Long, ThreadRef>()
    // messages waiting on a lookup of how their thread was started, by thread id
    private val waitingOnStarter = HashMap<Long, ArrayDeque<(Boolean) -> Unit>>()

    /**
     * Runs an action for a message from a thread once its root can be worked out, keeping each thread's messages in
     * order. The first message of a thread not seen before starts the lookup, and later messages from that thread wait
     * for it. A lookup that throws counts as a standalone thread.
     *
     * @param lookupStarter checks if the thread was started from a message in its parent channel, calling back once
     * @param action given the lookup's result, which only matters if the thread's root isn't known yet
     */
    fun whenRootKnown(threadId: Long, lookupStarter: (callback: (Boolean) -> Unit) -> Unit, action: (startedFromMessage: Boolean) -> Unit) {
        val isNewThread = synchronized(this) {
            val waiting = waitingOnStarter[threadId]
            when {
                waiting != null -> {
                    waiting.addLast(action)
                    return
                }
                threads.containsKey(threadId) -> false
                else -> {
                    waitingOnStarter[threadId] = ArrayDeque(listOf(action))
                    true
                }
            }
        }

        if (!isNewThread) {
            action(false)
            return
        }

        try {
            lookupStarter { startedFromMessage -> runWaiting(threadId, startedFromMessage) }
        } catch (ex: Exception) {
            runWaiting(threadId, false)
            throw ex
        }
    }

    private fun runWaiting(threadId: Long, startedFromMessage: Boolean) {
        var failure: Throwable? = null
        while (true) {
            val next = synchronized(this) {
                val waiting = waitingOnStarter[threadId] ?: return
                waiting.removeFirstOrNull() ?: run {
                    waitingOnStarter.remove(threadId)
                    null
                }
            } ?: break

            // one failing message mustn't hold up the rest of the thread
            try {
                next(startedFromMessage)
            } catch (ex: Exception) {
                failure?.addSuppressed(ex) ?: run { failure = ex }
            }
        }

        failure?.let { throw it }
    }

    /**
     * Records a message bridged from a thread and works out the message it continues on IRC.
     *
     * @param inThreadReplyTo the message it replies to inline within the thread, if any
     * @param startedFromMessage whether the thread was started from a message in its parent channel, only used the
     * first time a thread is seen
     * @return the message to send this one as a reply to, or null if it is the thread's root
     */
    fun parentFor(thread: ThreadRef, messageId: Long, inThreadReplyTo: Long?, startedFromMessage: Boolean): Long? {
        recordMessage(messageId, thread)
        val rootId = getRoot(thread.threadId) ?: setRootIfAbsent(thread, if (startedFromMessage) thread.threadId else messageId)
        return inThreadReplyTo ?: rootId.takeIf { it != messageId }
    }

    /**
     * Records that a message is in a thread, whether it was bridged from it or posted into it
     */
    @Synchronized
    fun recordMessage(messageId: Long, thread: ThreadRef) {
        messageThreads[messageId] = thread
    }

    /**
     * Gets the thread a message is in, if it is one we bridged from or posted into
     */
    @Synchronized
    fun threadOf(messageId: Long): ThreadRef? = messageThreads[messageId]

    /**
     * Gets the thread a message started, if it is a known thread started from that message
     */
    @Synchronized
    fun threadStartedBy(messageId: Long): ThreadRef? {
        val info = threads[messageId] ?: return null
        return if (info.rootId == messageId) ThreadRef(messageId, info.channelId) else null
    }

    @Synchronized
    private fun getRoot(threadId: Long): Long? = threads[threadId]?.rootId

    @Synchronized
    private fun setRootIfAbsent(thread: ThreadRef, rootId: Long): Long {
        return threads.getOrPut(thread.threadId) { ThreadInfo(thread.channelId, rootId) }.rootId
    }

    private fun <K, V> lruMap() = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }
}
