/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.mutator.mutators

import io.zachbr.dis4irc.bridge.message.DiscordMessage
import io.zachbr.dis4irc.bridge.message.DiscordSender
import io.zachbr.dis4irc.bridge.message.DiscordSource
import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.CommentedConfigurationNode
import java.time.Instant
import java.time.OffsetDateTime

private val RESET = IrcFormattingCodes.RESET.toString()

class ShortenLongMessagesTest {
    private fun shorten(contents: String, configure: (CommentedConfigurationNode) -> Unit = {}): String {
        val config = CommentedConfigurationNode.root()
        configure(config)
        val message = DiscordMessage(contents, DiscordSender("a", 1L), DiscordSource("c", 2L), Instant.now(), sentTimestamp = OffsetDateTime.now())
        ShortenLongMessages(testBridge(), config).mutate(message)
        return message.contents
    }

    // the settings production uses, matching the shortener before newlines were kept
    private fun prodSettings(config: CommentedConfigurationNode) {
        config.node("max-message-length").set(400)
        config.node("max-new-lines").set(3)
        config.node("shortened-suffix").set("...")
        config.node("collapse-new-lines").set(true)
    }

    @Test
    fun newlinesAreKeptByDefault() {
        val contents = (1..8).joinToString("\n") { "line $it" }
        assertEquals("$contents[...]$RESET", shorten(contents))
    }

    @Test
    fun newlinesCollapseToSpacesWhenEnabled() {
        val contents = "one\ntwo\nthree\nfour\nfive"
        assertEquals("one two three four five...$RESET", shorten(contents, ::prodSettings))
    }

    @Test
    fun collapsedMessagesAreCutAtTheMaximumLength() {
        val contents = ("word ".repeat(50) + "\n").repeat(4)
        val shortened = shorten(contents, ::prodSettings)

        assertEquals(contents.replace("\n", " ").substring(0, 400) + "..." + RESET, shortened)
        assertFalse("\n" in shortened)
    }

    @Test
    fun shortMessagesAreUntouched() {
        assertEquals("one\ntwo", shorten("one\ntwo", ::prodSettings))
    }

    @Test
    fun collapseOptionIsWrittenOffByDefault() {
        val config = CommentedConfigurationNode.root()
        ShortenLongMessages(testBridge(), config)
        assertFalse(config.node("collapse-new-lines").virtual())
        assertFalse(config.node("collapse-new-lines").boolean)
    }
}
