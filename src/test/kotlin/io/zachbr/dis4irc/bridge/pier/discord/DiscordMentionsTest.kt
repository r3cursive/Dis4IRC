/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private const val ZWSP = "​"

class DiscordMentionsTest {
    private val users = listOf("111" to ".kirk", "222" to "virtual potato")
    private val roles = listOf("<@&333>" to "mods")
    private val channels = listOf("<#444>" to "general")
    private val emojis = listOf("<:kek:555>" to "kek")

    private fun render(raw: String, mentionName: (String) -> String) = renderMentions(raw, users, emojis, channels, roles, mentionName)

    private val marked = { name: String -> "[$name]" }

    @Test
    fun userMentionsPassThroughTheNameChange() {
        assertEquals("hey @[.kirk] and @[virtual potato]", render("hey <@111> and <@!222>", marked))
    }

    @Test
    fun roleMentionsPassThroughTheNameChange() {
        assertEquals("ping @[mods]", render("ping <@&333>", marked))
    }

    @Test
    fun everythingElseRendersAsJdaDoes() {
        // channel and emoji mentions, everyone and here, and a name typed as plain text
        assertEquals("@everyone @here see #general :kek: kirk", render("@everyone @here see <#444> <:kek:555> kirk", marked))
    }

    @Test
    fun withoutChangingNamesItMatchesJdasDisplayContent() {
        assertEquals("@.kirk @mods #general :kek:", render("<@111> <@&333> <#444> <:kek:555>") { it })
    }

    @Test
    fun namesWithRegexCharactersAreSafe() {
        assertEquals("@\$1 x", renderMentions("<@9> x", listOf("9" to "\$1"), emptyList(), emptyList(), emptyList(), mentionName = { it }))
    }

    @Test
    fun mentionsShowTheServerNameBeforeTheUserName() {
        assertEquals("Kirk (nick)", mentionDisplayName("Kirk (nick)", "kirk_user"))
        assertEquals("kirk_user", mentionDisplayName(null, "kirk_user"))
    }

    @Test
    fun mentionAntiPingNeedsTheOptionAndAntiPing() {
        assertNull(testBridge { it.node("irc", "anti-ping").set(true) }.discordConn.mentionAntiPing, "the option is off by default")
        assertNull(testBridge {
            it.node("irc", "anti-ping").set(false)
            it.node("irc", "anti-ping-mentions").set(true)
        }.discordConn.mentionAntiPing, "anti-ping is off")

        assertNull(testBridge {
            it.node("irc", "anti-ping").set(true)
            it.node("irc", "anti-ping-style").set("every-other")
            it.node("irc", "anti-ping-mentions").set(true)
        }.discordConn.mentionAntiPing, "every other character is too many zero width spaces for a mentioned name")

        val antiPing = testBridge {
            it.node("irc", "anti-ping").set(true)
            it.node("irc", "anti-ping-style").set("single")
            it.node("irc", "anti-ping-mentions").set(true)
        }.discordConn.mentionAntiPing
        assertNotNull(antiPing)
        assertEquals("hey @.k${ZWSP}irk, @m${ZWSP}ods see #general", render("hey <@111>, <@&333> see <#444>", antiPing!!))
    }

    @Test
    fun theOptionIsWrittenOffByDefault() {
        val config = testBridge().config
        assertFalse(config.irc.antiPingMentions)
        assertFalse(config.rawNode.node("irc", "anti-ping-mentions").virtual())
    }

    private val many = (1..9).map { "$it" to "user$it" }

    private fun renderMany(raw: String) = renderMentions(raw, many, emptyList(), emptyList(), roles, marked)

    @Test
    fun upToThreeMentionsAreAllChanged() {
        assertEquals("@[user1] @[user2] @[user3]", renderMany("<@1> <@2> <@3>"))
    }

    @Test
    fun mentionsPastThreeRenderPlain() {
        // each zero width space scores on 129irc's spam filter, which refuses a line at 10
        assertEquals("@[user1] @[user2] @[mods] @user4 @user5", renderMany("<@1> <@2> <@&333> <@4> <@5>"))
    }

    @Test
    fun repeatedMentionsCountEachTime() {
        assertEquals("@[user1] @[user1] @[user1] @user1 @user1", renderMany("<@1> <@!1> <@1> <@1> <@1>"))
    }

    @Test
    fun theLimitIsPerContentString() {
        // the reply context line and the message are separate IRC lines, rendered separately
        val header = renderMany("<@1> <@2> <@3> <@4>")
        val body = renderMany("<@5> <@6> <@7> <@8>")
        assertEquals("@[user1] @[user2] @[user3] @user4", header)
        assertEquals("@[user5] @[user6] @[user7] @user8", body)
    }

    @Test
    fun unknownMentionsDontCount() {
        assertEquals("<@99> @[user1] @[user2] @[user3] @user4", renderMany("<@99> <@1> <@2> <@3> <@4>"))
    }

    @Test
    fun forwardedMentionsChangeOnlyTheFirstThree() {
        val names = mapOf(1L to "one", 2L to "two", 3L to "three", 4L to "four")
        val nameOf = { isRole: Boolean, id: Long -> if (isRole) "role$id" else names[id] }

        // the rest stay as mentions for the usual conversion to plain names
        assertEquals("[one] [role7] [two] <@3> <@!4>", changeFirstMentionNames("<@1> <@&7> <@!2> <@3> <@!4>", marked, nameOf))
        assertEquals("<@99> [one] [two] [three] <@4>", changeFirstMentionNames("<@99> <@1> <@2> <@3> <@4>", marked, nameOf))
    }
}
