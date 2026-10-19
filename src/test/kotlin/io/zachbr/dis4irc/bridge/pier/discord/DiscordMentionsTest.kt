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
        assertEquals("@\$1 x", renderMentions("<@9> x", listOf("9" to "\$1"), emptyList(), emptyList(), emptyList()) { it })
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
}
