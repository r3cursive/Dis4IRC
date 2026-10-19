/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordRouteTest {
    @Test
    fun plainMessagesUseTheWebhookIfThereIsOne() {
        assertEquals(DiscordRoute.Webhook, chooseRoute(hasWebhook = true, replyTo = null, canReply = true))
        assertEquals(DiscordRoute.Bot(null), chooseRoute(hasWebhook = false, replyTo = null, canReply = true))
    }

    @Test
    fun repliesUseTheBotWhenItCanReply() {
        assertEquals(DiscordRoute.Bot(42L), chooseRoute(hasWebhook = true, replyTo = 42L, canReply = true))
        assertEquals(DiscordRoute.Bot(42L), chooseRoute(hasWebhook = false, replyTo = 42L, canReply = true))
    }

    @Test
    fun onlyClientErrorsAreRefusals() {
        // e.g. missing permissions, an unknown message or a locked thread
        assertTrue(isRefusal(400))
        assertTrue(isRefusal(403))
        assertTrue(isRefusal(404))
        // the message may have been posted anyway, so it mustn't be sent again
        assertFalse(isRefusal(500))
        assertFalse(isRefusal(504))
        assertFalse(isRefusal(-1)) // JDA's code for a failed connection
    }

    @Test
    fun repliesGoOutUnthreadedWhenTheBotCantReply() {
        // e.g. the bot lacks Read Message History, which Discord requires for replies
        assertEquals(DiscordRoute.Webhook, chooseRoute(hasWebhook = true, replyTo = 42L, canReply = false))
        assertEquals(DiscordRoute.Bot(null), chooseRoute(hasWebhook = false, replyTo = 42L, canReply = false))
    }
}
