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
 * Remembers which IRC message (by IRCv3 msgid) corresponds to a given Discord message (by snowflake), both ways.
 *
 * Used so that replies on either side can be threaded on the other. Entries are evicted least-recently-used
 * first, nothing is persisted, so replies to messages from before a restart are bridged unthreaded.
 */
class MessageIdCache(private val capacity: Int = DEFAULT_CAPACITY) {
    private val discordToIrc = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > capacity
    }
    private val ircToDiscord = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > capacity
    }

    /**
     * Records a one-to-one mapping both ways, as for an IRC message bridged to a single Discord message
     */
    @Synchronized
    fun put(discordMessageId: Long, ircMsgId: String) {
        discordToIrc[discordMessageId] = ircMsgId
        ircToDiscord[ircMsgId] = discordMessageId
    }

    /**
     * Records the IRC message that Discord replies to the given Discord message should point at
     */
    @Synchronized
    fun putDiscordToIrc(discordMessageId: Long, ircMsgId: String) {
        discordToIrc[discordMessageId] = ircMsgId
    }

    /**
     * Records the Discord message an IRC message belongs to. Several IRC messages can belong to one Discord message,
     * one for each line or part it was split into.
     */
    @Synchronized
    fun putIrcToDiscord(ircMsgId: String, discordMessageId: Long) {
        ircToDiscord[ircMsgId] = discordMessageId
    }

    @Synchronized
    fun getIrcMsgId(discordMessageId: Long): String? = discordToIrc[discordMessageId]

    @Synchronized
    fun getDiscordMessageId(ircMsgId: String): Long? = ircToDiscord[ircMsgId]
}
