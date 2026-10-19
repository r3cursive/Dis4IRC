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
 * The most mentions anti-pinged in one IRC line. UnrealIRCd's antimixedutf8 scores a point for each zero width space
 * inside a Latin word and refuses the whole line at 10. The sender's name takes 1, so 3 more makes 4 of the 10. That
 * leaves room for emoji and other scripts in the message, which score too. Live, 9 mentions with the sender made 10 and
 * the line was refused.
 */
const val MAX_ANTI_PINGED_MENTIONS = 3

// a user mention, <@id> or <@!id>, or a role mention, <@&id>
private val USER_OR_ROLE_MENTION = Regex("""<@([!&]?)(\d+)>""")

/**
 * Renders a Discord message's raw content as JDA's display content does, but passes the names of the first
 * [maxChanged] mentioned users and roles through [mentionName]. Later mentions render as JDA renders them. Each
 * occurrence counts, so a user mentioned twice counts twice. Channel mentions, emoji, everyone and here are rendered
 * as JDA does, and text typed as a plain name is left alone.
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
    mentionName: (String) -> String,
    maxChanged: Int = MAX_ANTI_PINGED_MENTIONS
): String {
    val userNames = users.toMap()
    val roleNames = roles.toMap()
    var changed = 0
    var text = USER_OR_ROLE_MENTION.replace(raw) { match ->
        val name = if (match.groupValues[1] == "&") roleNames[match.value] else userNames[match.groupValues[2]]
        when {
            name == null -> match.value
            changed < maxChanged -> {
                changed++
                "@" + mentionName(name)
            }
            else -> "@$name"
        }
    }

    for ((mention, name) in emojis) {
        text = text.replace(mention, ":$name:")
    }
    for ((mention, name) in channels) {
        text = text.replace(mention, "#$name")
    }
    return text
}

/**
 * Replaces the first [maxChanged] user and role mentions in the text, in the order they appear, with their names
 * passed through [mentionName], without an @ as forwarded messages show them. Mentions [nameOf] doesn't know, and
 * later ones, are left for the usual conversion.
 *
 * @param nameOf gets the name a mention shows as, given whether it is a role and its id, or null if it isn't converted
 */
fun changeFirstMentionNames(
    text: String,
    mentionName: (String) -> String,
    nameOf: (isRole: Boolean, id: Long) -> String?,
    maxChanged: Int = MAX_ANTI_PINGED_MENTIONS
): String {
    var changed = 0
    return USER_OR_ROLE_MENTION.replace(text) { match ->
        val id = match.groupValues[2].toLongOrNull()
        val name = if (changed < maxChanged && id != null) nameOf(match.groupValues[1] == "&", id) else null
        if (name == null) {
            match.value
        } else {
            changed++
            mentionName(name)
        }
    }
}
