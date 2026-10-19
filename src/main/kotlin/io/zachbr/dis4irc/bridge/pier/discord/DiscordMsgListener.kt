/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.time.Instant

/**
 * Responsible for listening to incoming discord messages and filtering garbage
 */
class DiscordMsgListener(private val pier: DiscordPier) : ListenerAdapter() {
    private val logger = pier.logger

    override fun onMessageReceived(event: MessageReceivedEvent) {
        // thread notices only carry the thread name or repeat another message
        if (isHiddenThreadNotice(event.message.type, pier.bridgesThreads)) {
            return
        }

        // don't bridge DMs on this handler (later?)
        val bridgeChannel = pier.getBridgeChannel(event.channel) ?: return

        // don't bridge itself
        val source = bridgeChannel.source // if we bridge DMs in the future, this needs to change
        if (pier.isThisBot(source, event.author.idLong)) {
            return
        }

        // don't bridge empty messages (discord does this on join)
        val message = event.message
        if (message.contentDisplay.isEmpty()
            && message.attachments.isEmpty()
            && message.stickers.isEmpty()
            && message.embeds.isEmpty()
            && message.messageSnapshots.isEmpty()
            && message.type != MessageType.SLASH_COMMAND) {
            return
        }

        val receiveInstant = Instant.now()
        logger.debug("DISCORD MSG ${event.channel.name} ${event.author.name}: ${event.message.contentStripped}")
        sendToBridge(bridgeChannel, message, receiveInstant)
    }

    override fun onMessageUpdate(event: MessageUpdateEvent) {
        // this is only here to support slash command embeds currently
        if (event.message.type != MessageType.SLASH_COMMAND) {
            return
        }

        val bridgeChannel = pier.getBridgeChannel(event.channel) ?: return

        // don't bridge itself
        val source = bridgeChannel.source
        if (pier.isThisBot(source, event.author.idLong)) {
            return
        }

        // don't bridge empty messages
        val message = event.message
        if (message.contentDisplay.isEmpty()
            && message.attachments.isEmpty()
            && message.stickers.isEmpty()
            && message.embeds.isEmpty()
            && message.messageSnapshots.isEmpty()
            && message.components.isEmpty()) {
            return
        }

        val receiveInstant = Instant.now()
        logger.debug("DISCORD MSGUPD ${event.channel.name} ${event.author.name} ${event.message.idLong}")
        sendToBridge(bridgeChannel, message, receiveInstant)
    }

    private fun sendToBridge(bridgeChannel: BridgeChannel, message: Message, receiveInstant: Instant) {
        val platformMessage = message.toPlatformMessage(logger, receiveInstant, source = bridgeChannel.source)
        val thread = bridgeChannel.thread
        if (thread != null) {
            pier.sendThreadMessageToBridge(thread, message, platformMessage)
        } else {
            pier.sendToBridge(platformMessage)
        }
    }
}
