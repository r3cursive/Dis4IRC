/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import net.dv8tion.jda.api.Permission

/**
 * Permissions the bot needs to send IRC replies as Discord replies
 */
val REPLY_PERMISSIONS = listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)

/**
 * Permissions the bot needs to start a thread for an IRC reply and post in it
 */
val START_THREAD_PERMISSIONS = listOf(Permission.VIEW_CHANNEL, Permission.CREATE_PUBLIC_THREADS, Permission.MESSAGE_SEND_IN_THREADS)

/**
 * Permissions the bot needs to post IRC replies into threads itself, which it does without a webhook
 */
val THREAD_POST_PERMISSIONS = listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND_IN_THREADS)

/**
 * Names permissions as Discord's own settings do, so they can be found there
 */
fun permissionName(permission: Permission): String = when (permission) {
    Permission.VIEW_CHANNEL -> "View Channel"
    Permission.MESSAGE_HISTORY -> "Read Message History"
    else -> permission.getName()
}

/**
 * Describes what the bot can't do with IRC replies in a channel for lack of permissions there, one line per feature.
 * Only features that are turned on and need the bot are checked.
 *
 * @param granted the permissions the bot has in the channel
 * @param hasWebhook whether the channel has a webhook, which posts into threads without the bot's permissions
 */
fun missingPermissionWarnings(
    channelName: String,
    granted: Set<Permission>,
    hasWebhook: Boolean,
    bridgeThreads: Boolean,
    threadIrcReplies: Boolean,
    startThreads: Boolean
): List<String> {
    if (!threadIrcReplies) {
        // IRC replies are plain messages anyway, as upstream
        return emptyList()
    }

    val warnings = ArrayList<String>()
    val missingForReplies = REPLY_PERMISSIONS.filterNot { it in granted }
    val fallback = if (missingForReplies.isEmpty()) "They'll go out as Discord replies in the channel instead." else "They'll go out as plain messages."

    if (bridgeThreads && startThreads) {
        val missing = START_THREAD_PERMISSIONS.filterNot { it in granted }
        if (missing.isNotEmpty()) {
            warnings.add("IRC replies in #$channelName can't start threads: missing ${names(missing)}. $fallback")
        }
    } else if (bridgeThreads && !hasWebhook) {
        val missing = THREAD_POST_PERMISSIONS.filterNot { it in granted }
        if (missing.isNotEmpty()) {
            warnings.add("IRC replies in #$channelName can't be posted into threads: missing ${names(missing)}. $fallback")
        }
    }

    if (missingForReplies.isNotEmpty()) {
        warnings.add("IRC replies in #$channelName can't be sent as Discord replies: missing ${names(missingForReplies)}. They'll go out as plain messages.")
    }

    return warnings
}

private fun names(permissions: List<Permission>): String = permissions.joinToString(", ") { permissionName(it) }

/**
 * Remembers which keys have been seen, so something can be logged loudly the first time and quietly after that
 */
class FirstTimes(private val capacity: Int = 256) {
    private val seen = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > capacity
    }

    /**
     * Records the key, returning whether this is the first time it was seen
     */
    @Synchronized
    fun first(key: String): Boolean = seen.put(key, true) == null

    /**
     * Forgets the key, so the next time it is seen counts as the first again
     */
    @Synchronized
    fun forget(key: String) {
        seen.remove(key)
    }
}
