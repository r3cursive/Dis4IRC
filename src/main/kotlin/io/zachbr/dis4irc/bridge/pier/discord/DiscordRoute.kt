/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

/**
 * How a message from IRC is sent to a Discord channel
 */
sealed interface DiscordRoute {
    /** Through the channel's webhook, under the sender's name. Webhooks can't send replies. */
    data object Webhook : DiscordRoute

    /** By the bot as `<nick> text`, as a reply to the given message if set */
    data class Bot(val replyTo: Long?) : DiscordRoute
}

/**
 * Checks if an HTTP status means Discord refused a request. Only those are worth sending another way. Server errors
 * and failed connections can come after Discord already posted the message, so sending again could post it twice.
 */
fun isRefusal(httpStatus: Int): Boolean = httpStatus in 400..499

/**
 * Checks if a JDA error code is one of Discord's own error codes, which it only sends when it refused a request.
 * JDA uses -1 for a failed connection and 0 for a server error, after either of which the message may have been posted.
 * Discord's codes start at 10000 and go past 59999, such as 160005 for a locked thread.
 */
fun isDiscordRefusal(errorCode: Int): Boolean = errorCode >= 10000

/**
 * Picks how to send a message from IRC to Discord. Replies need the bot, so they only go out as replies when the bot
 * can reply in the channel. Otherwise they go out unthreaded, through the webhook if there is one.
 *
 * @param canReply whether the bot can view, send and read history in the channel, which Discord requires for replies
 */
fun chooseRoute(hasWebhook: Boolean, replyTo: Long?, canReply: Boolean): DiscordRoute {
    if (replyTo != null && canReply) {
        return DiscordRoute.Bot(replyTo)
    }

    return if (hasWebhook) DiscordRoute.Webhook else DiscordRoute.Bot(null)
}
