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
const val DEFAULT_THREAD_NAME = "Thread from IRC"
private const val THREAD_NAME_LENGTH = 60
// Discord's limit for channel names
private const val MAX_THREAD_NAME_LENGTH = 100

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
 * Finds the thread a message is in or started, whether or not it can be posted to
 */
private fun findThreadOf(messageId: Long, tracker: DiscordThreadTracker, activeThread: (Long) -> ActiveThread?): ThreadTarget? {
    val inThread = tracker.threadOf(messageId)
    val thread = inThread
        ?: tracker.threadStartedBy(messageId)
        // a thread started from a message has the same id as the message
        ?: activeThread(messageId)?.let { ThreadRef(messageId, it.channelId) }
        ?: return null

    return ThreadTarget(thread, parentInThread = inThread != null)
}

private fun isUsable(target: ThreadTarget, channelId: Long, activeThread: (Long) -> ActiveThread?): Boolean {
    return target.thread.channelId == channelId && activeThread(target.thread.threadId)?.locked != true
}

/**
 * Where an IRC reply goes on Discord
 */
sealed interface IrcReplyPlan {
    /** Into the thread its parent is in or started */
    data class IntoThread(val target: ThreadTarget) : IrcReplyPlan

    /** Into a new thread, started on the parent message */
    data class StartThread(val parentMessageId: Long) : IrcReplyPlan

    /** Into the channel itself, as a Discord reply if the bot can reply */
    data object Channel : IrcReplyPlan
}

/**
 * Plans where an IRC message goes on Discord, given the Discord copy of the message it replies to, if any.
 *
 * @param startThreads whether IRC replies to messages without a thread start one
 * @param canStartThreads whether the bot can start threads in the channel and post in them
 */
fun planIrcReply(
    parentMessageId: Long?,
    channelId: Long,
    bridgeThreads: Boolean,
    startThreads: Boolean,
    canStartThreads: Boolean,
    tracker: DiscordThreadTracker,
    activeThread: (Long) -> ActiveThread?
): IrcReplyPlan {
    if (parentMessageId == null || !bridgeThreads) {
        return IrcReplyPlan.Channel
    }

    val found = findThreadOf(parentMessageId, tracker, activeThread)
    if (found != null) {
        // a locked thread, or one of another channel, can't take the reply, and the parent can't have another thread
        return if (isUsable(found, channelId, activeThread)) IrcReplyPlan.IntoThread(found) else IrcReplyPlan.Channel
    }

    return if (startThreads && canStartThreads) IrcReplyPlan.StartThread(parentMessageId) else IrcReplyPlan.Channel
}

/**
 * How an attempt to start a thread on a message went
 */
sealed interface ThreadStartResult {
    data class Started(val threadId: Long) : ThreadStartResult

    /** The message already has a thread, which has the message's id */
    data object AlreadyExists : ThreadStartResult

    data object Failed : ThreadStartResult
}

/**
 * Makes sure only one thread is started per message when several IRC replies to it arrive before the thread exists.
 * The first reply starts the thread, later ones wait for it, and all of them run in the order they arrived.
 */
class ThreadStarts {
    private val waiting = HashMap<Long, ArrayDeque<(Long?) -> Unit>>()

    /**
     * Runs the action once a thread exists on the message, with the thread's id, or null if one couldn't be started
     *
     * @param start starts the thread, calling back once with how it went
     */
    fun whenStarted(parentMessageId: Long, start: (callback: (ThreadStartResult) -> Unit) -> Unit, action: (threadId: Long?) -> Unit) {
        synchronized(this) {
            val queue = waiting[parentMessageId]
            if (queue != null) {
                queue.addLast(action)
                return
            }
            waiting[parentMessageId] = ArrayDeque(listOf(action))
        }

        val callback = { result: ThreadStartResult ->
            val threadId = when (result) {
                is ThreadStartResult.Started -> result.threadId
                ThreadStartResult.AlreadyExists -> parentMessageId
                ThreadStartResult.Failed -> null
            }
            runWaiting(parentMessageId, threadId)
        }

        try {
            start(callback)
        } catch (ex: Exception) {
            runWaiting(parentMessageId, null)
            throw ex
        }
    }

    /**
     * Queues the action behind a thread start already under way for the message, returning false if there is none
     */
    fun joinIfStarting(parentMessageId: Long, action: (threadId: Long?) -> Unit): Boolean {
        synchronized(this) {
            val queue = waiting[parentMessageId] ?: return false
            queue.addLast(action)
            return true
        }
    }

    private fun runWaiting(parentMessageId: Long, threadId: Long?) {
        var failure: Exception? = null
        while (true) {
            val next = synchronized(this) {
                val queue = waiting[parentMessageId] ?: return
                queue.removeFirstOrNull() ?: run {
                    waiting.remove(parentMessageId)
                    null
                }
            } ?: break

            // one failing reply mustn't hold up the rest
            try {
                next(threadId)
            } catch (ex: Exception) {
                failure?.addSuppressed(ex) ?: run { failure = ex }
            }
        }

        failure?.let { throw it }
    }
}

private val MENTION = Regex("""<(@[!&]?|#)\d+>""")
private val CUSTOM_EMOJI = Regex("""<a?:(\w+):\d+>""")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
// headings, quotes and subtext at the start of a line, possibly nested
private val LINE_MARKUP = Regex("""(?m)^[ \t]*(?:(?:#{1,3}|>{1,3}|-#)[ \t]+)+""")
private val INLINE_MARKUP = Regex("""\*\*|__|\*|~~|\|\||`+""")
// _italics_, but not the underscores inside snake_case words
private val UNDERSCORE_ITALICS = Regex("""(?<!\w)_([^_]+)_(?!\w)""")
private val WHITESPACE = Regex("""\s+""")

/**
 * Names a thread started from IRC after the message it was started on: the start of its text, without mentions and
 * markdown. Falls back to [DEFAULT_THREAD_NAME] when nothing is left.
 */
fun threadNameFrom(content: String): String {
    val text = content
        .replace(MARKDOWN_LINK, "$1")
        .replace(MENTION, "")
        .replace(CUSTOM_EMOJI, ":$1:")
        .replace(LINE_MARKUP, "")
        .replace(INLINE_MARKUP, "")
        .replace(UNDERSCORE_ITALICS, "$1")
        .replace(WHITESPACE, " ")
        .trim()

    if (text.isEmpty()) {
        return DEFAULT_THREAD_NAME
    }

    if (text.codePointCount(0, text.length) <= THREAD_NAME_LENGTH) {
        return text
    }

    // cut at a word boundary near the length if there is one, never splitting a character
    val cut = text.substring(0, text.offsetByCodePoints(0, THREAD_NAME_LENGTH))
    val space = cut.lastIndexOf(' ')
    val name = if (space >= THREAD_NAME_LENGTH / 2) cut.substring(0, space) else cut
    return fitLength(name, MAX_THREAD_NAME_LENGTH).trim()
}

/**
 * Cuts text to at most the given number of UTF-16 chars, as Discord counts them, without splitting a surrogate pair
 */
private fun fitLength(text: String, maxLength: Int): String {
    if (text.length <= maxLength) {
        return text
    }

    var end = maxLength
    if (Character.isLowSurrogate(text[end]) && Character.isHighSurrogate(text[end - 1])) {
        end--
    }
    return text.substring(0, end)
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
     * Records a thread the bridge started on a message. Like any thread started from a message, the message is its root.
     */
    @Synchronized
    fun recordStartedThread(thread: ThreadRef) {
        threads[thread.threadId] = ThreadInfo(thread.channelId, thread.threadId)
    }

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
