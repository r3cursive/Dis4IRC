/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.config

import io.zachbr.dis4irc.bridge.testBridge
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.spongepowered.configurate.CommentedConfigurationNode

class ConfigurationUtilsTest {
    // toLoggable truncates webhook URLs to 60 characters, which the default placeholder URL is shorter than
    private fun withoutWebhooks(node: CommentedConfigurationNode) {
        node.node("discord-webhooks").set(null)
    }

    @Test
    fun bridgeThreadsIsOffByDefault() {
        val config = testBridge(configure = ::withoutWebhooks).config
        assertFalse(config.discord.bridgeThreads)
        assertFalse(config.rawNode.node("discord-options", "bridge-threads").boolean)
        assertTrue(config.toLoggable().contains("bridgeThreads=false"))
    }

    @Test
    fun bridgeThreadsIsAddedToExistingConfigs() {
        // a config written before the option existed
        val config = testBridge { it.node("discord-options").removeChild("bridge-threads") }.config
        assertFalse(config.discord.bridgeThreads)
        assertFalse(config.rawNode.node("discord-options", "bridge-threads").virtual())
    }

    @Test
    fun bridgeThreadsCanBeEnabled() {
        val config = testBridge {
            withoutWebhooks(it)
            it.node("discord-options", "bridge-threads").set(true)
        }.config
        assertTrue(config.discord.bridgeThreads)
        assertTrue(config.toLoggable().contains("bridgeThreads=true"))
    }

    @Test
    fun ircRepliesStartThreadsIsOffByDefault() {
        val config = testBridge(configure = ::withoutWebhooks).config
        assertFalse(config.discord.ircRepliesStartThreads)
        assertTrue(config.toLoggable().contains("ircRepliesStartThreads=false"))

        val enabled = testBridge { it.node("discord-options", "irc-replies-start-threads").set(true) }.config
        assertTrue(enabled.discord.ircRepliesStartThreads)
    }
}
