/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import io.zachbr.dis4irc.bridge.message.IrcMessage
import net.engio.mbassy.listener.Handler
import org.kitteh.irc.client.library.event.batch.ClientBatchStartEvent
import org.kitteh.irc.client.library.event.channel.ChannelCtcpEvent
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.helper.ServerMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateNoticeEvent
import org.kitteh.irc.client.library.util.Format
import java.time.Instant

private const val CTCP_ACTION = "ACTION"

private const val MSGID_TAG = "msgid"
private const val LABEL_TAG = "label"
private const val BATCH_TAG = "batch"
private const val LABELED_RESPONSE_BATCH = "labeled-response"
// numerics a server answers a channel message with when it refuses it
private val SEND_REFUSALS = setOf(
    404, // ERR_CANNOTSENDTOCHAN, also used by spam filters such as UnrealIRCd's antimixedutf8
    477, // ERR_NEEDREGGEDNICK on many servers
    489  // ERR_SECUREONLYCHAN
)
// +reply is the ratified name, +draft/reply the draft name many clients still send, some send both
private val REPLY_TAGS = listOf("+draft/reply", "+reply")

/**
 * Gets the IRCv3 msgid tag of the message behind this event, if the server sent one
 */
private fun ServerMessageEvent.msgId(): String? = source.getTag(MSGID_TAG).flatMap { it.value }.orElse(null)

/**
 * Gets a tag's value from the message behind this event, if the server sent it
 */
private fun ServerMessageEvent.tagValue(name: String): String? = source.getTag(name).flatMap { it.value }.orElse(null)

/**
 * Gets the msgid this message replies to (IRCv3 reply client tag), if any
 */
private fun ServerMessageEvent.replyToMsgId(): String? = replyToMsgId { source.getTag(it).flatMap { tag -> tag.value }.orElse(null) }

internal fun replyToMsgId(getTag: (String) -> String?): String? = REPLY_TAGS.firstNotNullOfOrNull { getTag(it)?.takeIf(String::isNotEmpty) }

/**
 * Responsible for listening to incoming IRC messages and filtering garbage
 */
class IrcMessageListener(private val pier: IrcPier) {
    private val logger = pier.logger

    @Handler
    fun onMessage(event: ChannelMessageEvent) {
        // ignore messages sent by this bot, other than noting the msgid of echoes
        if (event.actor.nick == pier.getBotNick()) {
            pier.onOwnMessageEcho(event.channel.name, event.message, event.msgId(), event.tagValue(LABEL_TAG), event.tagValue(BATCH_TAG))
            return
        }

        val receiveInstant = Instant.now()
        logger.debug("IRC MSG ${event.channel.name} ${event.actor.nick}: ${event.message}")

        val sender = event.actor.asBridgeSender()
        val source = event.channel.asBridgeSource()
        val message = IrcMessage(event.message, sender, source, receiveInstant, event.msgId(), event.replyToMsgId())
        pier.sendToBridge(message)
    }

    @Handler
    fun onChannelCtcp(event: ChannelCtcpEvent) {
        // ignore messages sent by this bot
        if (event.actor.nick == pier.getBotNick()) {
            return
        }

        val receveInstant = Instant.now()
        logger.debug("IRC CTCP ${event.channel.name} ${event.actor.nick}: ${event.message}")

        // if it's not an action we probably don't care
        if (!event.message.startsWith(CTCP_ACTION)) {
            return
        }

        // add italic code and strip off the ctcp type info
        val messageText = "${Format.ITALIC}${event.message.substring(CTCP_ACTION.length + 1)}"

        val sender = event.actor.asBridgeSender()
        val source = event.channel.asBridgeSource()
        val message = IrcMessage(messageText, sender, source, receveInstant, event.msgId(), event.replyToMsgId())
        pier.sendToBridge(message)
    }

    @Handler
    fun onNumeric(event: ClientReceiveNumericEvent) {
        if (event.numeric !in SEND_REFUSALS || event.parameters.size < 2) {
            return
        }

        // :server 404 nick #channel :reason
        val target = event.parameters[1]
        val reason = event.parameters.getOrNull(2) ?: ""
        pier.onSendRefused(target, reason, event.tagValue(LABEL_TAG), event.tagValue(BATCH_TAG))
    }

    @Handler
    fun onBatchStart(event: ClientBatchStartEvent) {
        if (event.referenceTag.type != LABELED_RESPONSE_BATCH) {
            return
        }

        val label = event.tagValue(LABEL_TAG) ?: return
        pier.onLabeledBatch(event.referenceTag.referenceTag, label)
    }

    @Handler
    fun onPrivateMessage(event: PrivateMessageEvent) {
        // with echo-message our own private messages come back, such as NickServ IDENTIFY from the init commands
        if (event.actor.nick == pier.getBotNick()) {
            return
        }

        logger.debug("IRC PRIVMSG ${event.actor.nick}: ${event.message}")
    }

    @Handler
    fun onPrivateNotice(event: PrivateNoticeEvent) {
        if (event.actor.nick == pier.getBotNick()) {
            return
        }

        logger.debug("IRC NOTICE ${event.actor.nick}: ${event.message}")
    }
}
