/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

/**
 * The name a mentioned user shows as: their name in the server if they are a member, else their user name.
 * JDA's own display content uses the same rule.
 */
fun mentionDisplayName(memberName: String?, userName: String): String = memberName ?: userName

/**
 * Renders a Discord message's raw content as JDA's display content does, but passes the names of mentioned users and
 * roles through [mentionName] first. Channel mentions, emoji, everyone and here are rendered as JDA does, and text
 * typed as a plain name is left alone.
 *
 * @param users mentioned users' ids and the names they show as
 * @param emojis mentioned custom emoji's mention text and names
 * @param channels mentioned channels' mention text and names
 * @param roles mentioned roles' mention text and names
 */
fun renderMentions(
    raw: String,
    users: List<Pair<String, String>>,
    emojis: List<Pair<String, String>>,
    channels: List<Pair<String, String>>,
    roles: List<Pair<String, String>>,
    mentionName: (String) -> String
): String {
    var text = raw
    for ((id, name) in users) {
        text = text.replace(Regex("<@!?" + Regex.escape(id) + ">"), Regex.escapeReplacement("@" + mentionName(name)))
    }
    for ((mention, name) in emojis) {
        text = text.replace(mention, ":$name:")
    }
    for ((mention, name) in channels) {
        text = text.replace(mention, "#$name")
    }
    for ((mention, name) in roles) {
        text = text.replace(mention, "@" + mentionName(name))
    }
    return text
}
