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
    fun messagesMustFitOneWayOfSendingThem() {
        val prefix = "<someone> "
        assertTrue(fitsDiscord("hi", prefix, hasWebhook = false, maxLength = 2000))
        assertTrue(fitsDiscord("a".repeat(2000 - prefix.length), prefix, hasWebhook = false, maxLength = 2000))
        // the bot's prefix counts, a webhook's username doesn't
        assertFalse(fitsDiscord("a".repeat(2000), prefix, hasWebhook = false, maxLength = 2000))
        assertTrue(fitsDiscord("a".repeat(2000), prefix, hasWebhook = true, maxLength = 2000))
        assertFalse(fitsDiscord("a".repeat(2001), prefix, hasWebhook = true, maxLength = 2000))
        // the webhook library counts UTF-16 chars, JDA counts code points, so the bot can still send this as a reply
        val emoji = "\uD83D\uDE00".repeat(1100)
        assertTrue(fitsDiscord(emoji, prefix, hasWebhook = true, maxLength = 2000))
        assertFalse(fitsDiscord(emoji + emoji, prefix, hasWebhook = true, maxLength = 2000))
    }

    @Test
    fun onlyDiscordErrorCodesAreRefusals() {
        assertTrue(isDiscordRefusal(10008)) // unknown message
        assertTrue(isDiscordRefusal(50013)) // missing permissions
        assertTrue(isDiscordRefusal(160005)) // thread locked
        assertFalse(isDiscordRefusal(0)) // JDA's code for a server error
        assertFalse(isDiscordRefusal(-1)) // JDA's code for a failed connection
    }

    @Test
    fun repliesGoOutUnthreadedWhenTheBotCantReply() {
        // e.g. the bot lacks Read Message History, which Discord requires for replies
        assertEquals(DiscordRoute.Webhook, chooseRoute(hasWebhook = true, replyTo = 42L, canReply = false))
        assertEquals(DiscordRoute.Bot(null), chooseRoute(hasWebhook = false, replyTo = 42L, canReply = false))
    }
}
