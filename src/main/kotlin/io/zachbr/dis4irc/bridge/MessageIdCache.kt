/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge

private const val DEFAULT_CAPACITY = 2048

/**
 * Remembers which IRC message (by IRCv3 msgid) corresponds to a given Discord message (by snowflake).
 *
 * Used so that Discord replies can be sent to IRC as threaded replies. Entries are evicted least-recently-used
 * first, nothing is persisted, so replies to messages from before a restart fall back to the plain text context.
 */
class MessageIdCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val discordToIrc = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > capacity
    }

    @Synchronized
    fun put(discordMessageId: Long, ircMsgId: String) {
        discordToIrc[discordMessageId] = ircMsgId
    }

    @Synchronized
    fun getIrcMsgId(discordMessageId: Long): String? = discordToIrc[discordMessageId]
}
