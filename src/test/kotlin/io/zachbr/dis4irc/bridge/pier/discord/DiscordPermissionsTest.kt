/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import net.dv8tion.jda.api.Permission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.EnumSet

class DiscordPermissionsTest {
    private val everything = EnumSet.allOf(Permission::class.java)

    private fun without(vararg permissions: Permission): Set<Permission> = EnumSet.copyOf(everything).apply { removeAll(permissions.toSet()) }

    private fun warnings(
        granted: Set<Permission>,
        hasWebhook: Boolean = true,
        bridgeThreads: Boolean = true,
        threadIrcReplies: Boolean = true,
        startThreads: Boolean = true
    ) = missingPermissionWarnings("general", granted, hasWebhook, bridgeThreads, threadIrcReplies, startThreads)

    @Test
    fun nothingToSayWithEveryPermission() {
        assertEquals(emptyList<String>(), warnings(everything))
    }

    @Test
    fun missingThreadPermissionsAreNamed() {
        assertEquals(
            listOf("IRC replies in #general can't start threads: missing Create Public Threads. They'll go out as Discord replies in the channel instead."),
            warnings(without(Permission.CREATE_PUBLIC_THREADS))
        )
    }

    @Test
    fun missingReplyPermissionsAreNamed() {
        assertEquals(
            listOf("IRC replies in #general can't be sent as Discord replies: missing Read Message History. They'll go out as plain messages."),
            warnings(without(Permission.MESSAGE_HISTORY), startThreads = false)
        )
    }

    @Test
    fun whatProductionHitIsBothWarnings() {
        // no thread and no inline reply, as seen in production: the reply went out as a plain webhook post
        val granted = without(Permission.CREATE_PUBLIC_THREADS, Permission.MESSAGE_SEND_IN_THREADS, Permission.MESSAGE_HISTORY)
        assertEquals(
            listOf(
                "IRC replies in #general can't start threads: missing Create Public Threads, Send Messages in Threads. They'll go out as plain messages.",
                "IRC replies in #general can't be sent as Discord replies: missing Read Message History. They'll go out as plain messages."
            ),
            warnings(granted)
        )
    }

    @Test
    fun threadPostsOnlyNeedTheBotWithoutAWebhook() {
        val granted = without(Permission.MESSAGE_SEND_IN_THREADS)
        // the webhook posts into threads itself
        assertEquals(emptyList<String>(), warnings(granted, hasWebhook = true, startThreads = false))
        assertEquals(
            listOf("IRC replies in #general can't be posted into threads: missing Send Messages in Threads. They'll go out as Discord replies in the channel instead."),
            warnings(granted, hasWebhook = false, startThreads = false)
        )
    }

    @Test
    fun onlyEnabledFeaturesAreChecked() {
        val none = EnumSet.noneOf(Permission::class.java)
        // without thread-irc-replies, IRC replies are plain messages anyway
        assertEquals(emptyList<String>(), warnings(none, threadIrcReplies = false))
        // without bridge-threads, threads are never used
        assertEquals(1, warnings(none, bridgeThreads = false).size)
        assertTrue(warnings(none, bridgeThreads = false).single().contains("can't be sent as Discord replies"))
    }

    @Test
    fun permissionsUseDiscordsOwnNames() {
        assertEquals("View Channel", permissionName(Permission.VIEW_CHANNEL))
        assertEquals("Read Message History", permissionName(Permission.MESSAGE_HISTORY))
        assertEquals("Create Public Threads", permissionName(Permission.CREATE_PUBLIC_THREADS))
    }

    @Test
    fun firstTimesAreLoudThenQuiet() {
        val firstTimes = FirstTimes(capacity = 2)
        assertTrue(firstTimes.first("reply:1"))
        assertFalse(firstTimes.first("reply:1"))
        assertTrue(firstTimes.first("reply:2"))

        firstTimes.forget("reply:1")
        assertTrue(firstTimes.first("reply:1"))

        // bounded, the oldest is forgotten
        firstTimes.first("reply:3")
        assertTrue(firstTimes.first("reply:2"))
    }
}
