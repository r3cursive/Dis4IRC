/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import org.kitteh.irc.client.library.util.Format
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val MAX_PENDING = 256
// the IRC library sends about one line per second, so a long backlog can take minutes to drain
private val PENDING_TIMEOUT: Duration = Duration.ofMinutes(10)

/**
 * Matches the server's echoes (IRCv3 echo-message) of lines the bridge sent back to the Discord message that
 * produced them, so the msgid the server assigned can be recorded.
 *
 * With IRCv3 labeled-response, each tracked line is sent with a label and its echo carries the same label, so even an
 * echo the server altered (stripped colours, filtered words) still matches. Without it, echoes are matched in queue
 * order on channel and text, ignoring formatting codes. Anything that doesn't match is ignored.
 */
class IrcEchoTracker(private val clock: Clock = Clock.systemUTC()) {
    /**
     * A line bridged from Discord
     *
     * @param primary whether it is the first part of the message's first content line, which Discord replies point at
     */
    data class TrackedLine(val discordMessageId: Long, val primary: Boolean)

    private data class Pending(val channel: String, val text: String, val line: TrackedLine, val label: String?, val queuedAt: Instant)

    private val pending = ArrayDeque<Pending>()

    /**
     * @param label the IRCv3 label the line is sent with, if any
     * @param primary whether it is the first part of the message's first content line
     */
    @Synchronized
    fun expect(channel: String, text: String, discordMessageId: Long, label: String? = null, primary: Boolean = true) {
        if (pending.size >= MAX_PENDING) {
            pending.removeFirst()
        }
        pending.addLast(Pending(channel, text, TrackedLine(discordMessageId, primary), label, clock.instant()))
    }

    /**
     * Returns the Discord message id the echoed line belongs to, or null if it isn't one we are waiting on.
     *
     * @param label the label the echo carries, if any
     */
    fun match(channel: String, text: String, label: String? = null): Long? = matchLine(channel, text, label)?.discordMessageId

    /**
     * Returns the tracked line the echo is of, or null if it isn't one we are waiting on.
     *
     * @param label the label the echo carries, if any
     */
    @Synchronized
    fun matchLine(channel: String, text: String, label: String? = null): TrackedLine? {
        val cutoff = clock.instant().minus(PENDING_TIMEOUT)
        pending.removeAll { it.queuedAt.isBefore(cutoff) }

        if (label != null) {
            val index = pending.indexOfFirst { it.label == label }
            return if (index == -1) null else pending.removeAt(index).line
        }

        val strippedText = Format.stripAll(text)
        val index = pending.indexOfFirst {
            it.label == null && it.channel.equals(channel, ignoreCase = true) && Format.stripAll(it.text) == strippedText
        }
        if (index == -1) {
            return null
        }

        val match = pending[index]
        // any unlabeled line queued before the match in the same channel was never echoed as sent, drop it
        val iterator = pending.iterator()
        var i = 0
        while (iterator.hasNext() && i <= index) {
            val next = iterator.next()
            if (next.label == null && next.channel.equals(channel, ignoreCase = true)) {
                iterator.remove()
            }
            i++
        }

        return match.line
    }

    /**
     * Forgets every line we are waiting on, as nothing sent before a reconnect will be echoed
     */
    @Synchronized
    fun clear() {
        pending.clear()
    }
}

/**
 * Builds the message tag prefix for a line, or an empty string if there are no tags
 */
fun formatTags(tags: List<Pair<String, String>>): String {
    if (tags.isEmpty()) {
        return ""
    }

    return tags.joinToString(";", prefix = "@", postfix = " ") { (key, value) -> "$key=${escapeTagValue(value)}" }
}

/**
 * Escapes a value for use in an IRCv3 message tag.
 */
fun escapeTagValue(value: String): String {
    val builder = StringBuilder(value.length)
    for (char in value) {
        when (char) {
            ';' -> builder.append("\\:")
            ' ' -> builder.append("\\s")
            '\\' -> builder.append("\\\\")
            '\r' -> builder.append("\\r")
            '\n' -> builder.append("\\n")
            else -> builder.append(char)
        }
    }
    return builder.toString()
}

/**
 * Checks a CLIENTTAGDENY ISUPPORT value to see whether the given client tag (without the + prefix) is blocked.
 * See https://ircv3.net/specs/extensions/message-tags#rpl_isupport-tokens
 */
fun isClientTagDenied(clientTagDeny: String?, tag: String): Boolean {
    if (clientTagDeny.isNullOrBlank()) {
        return false
    }

    val entries = clientTagDeny.split(',')
    if ("*" in entries) {
        return "-$tag" !in entries
    }
    return tag in entries
}
