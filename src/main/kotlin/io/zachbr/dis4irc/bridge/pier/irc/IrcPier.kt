/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.zachbr.dis4irc.bridge.Bridge
import io.zachbr.dis4irc.bridge.message.BridgeMessage
import io.zachbr.dis4irc.bridge.message.DiscordMessage
import io.zachbr.dis4irc.bridge.message.PlatformMessage
import io.zachbr.dis4irc.bridge.pier.Pier
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.Client.Builder.Server.SecurityType
import org.kitteh.irc.client.library.element.Channel
import org.kitteh.irc.client.library.feature.CapabilityManager
import org.slf4j.Logger
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

private const val REPLY_TAG = "draft/reply"
private const val LABEL_TAG = "label"
private const val MAX_LABELED_BATCHES = 64

class IrcPier(private val bridge: Bridge) : Pier {
    internal val logger: Logger = bridge.logger
    private lateinit var ircConn: Client
    private var antiPing: Boolean = false
    private var noPrefix: Pattern? = null
    private var referenceLengthLimit: Int = 90
    private val echoTracker = IrcEchoTracker()
    private val labelCounter = AtomicLong()
    // labeled-response batches the server wrapped an echo in, by batch reference, with the label they answer
    private val labeledBatches = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_LABELED_BATCHES
    }

    override fun start() {
        logger.info("Connecting to IRC Server")

        // configure IRC
        val builder = Client.builder()
            .nick(bridge.config.irc.nickName)
            .user(bridge.config.irc.userName)
            .realName(bridge.config.irc.realName)
            .server()
                .host(bridge.config.irc.hostname)
                .port(bridge.config.irc.port, if (bridge.config.irc.sslEnabled) SecurityType.SECURE else SecurityType.INSECURE)
                .password(bridge.config.irc.password)

        if (bridge.config.irc.allowInvalidCerts) {
            logger.warn("Allowing invalid TLS certificates for IRC. This is not recommended.")
            builder.secureTrustManagerFactory(InsecureTrustManagerFactory.INSTANCE)
        }

        noPrefix = bridge.config.irc.noPrefixRegex
        antiPing = bridge.config.irc.antiPing
        referenceLengthLimit = bridge.config.irc.discordReplyContextLimit

        // listeners go on before connecting, so none of the early events, such as capability negotiation, are missed
        ircConn = builder.then().build()
        ircConn.client.exceptionListener.setConsumer { bridge.logger.warn("Exception from IRC API:", it)}

        // listeners
        ircConn.eventManager.registerEventListener(IrcConnectionListener(this))
        ircConn.eventManager.registerEventListener(IrcMessageListener(this))

        if (bridge.config.irc.discordReplyTags) {
            ircConn.eventManager.registerEventListener(IrcCapabilityListener())
        }

        if (bridge.config.announceJoinsQuits) {
            ircConn.eventManager.registerEventListener(IrcJoinQuitListener(this))
        }

        if (bridge.config.announceExtras) {
            ircConn.eventManager.registerEventListener(IrcExtrasListener(this))
        }

        // connect
        ircConn.connect()
    }

    override fun onShutdown() {
        if (this::ircConn.isInitialized) {
            ircConn.shutdown("Exiting...")
        }
    }

    override fun sendMessage(targetChan: String, msg: BridgeMessage) {
        if (!this::ircConn.isInitialized) {
            logger.error("IRC Connection has not been initialized yet!")
            return
        }

        val channel = getChannelByName(targetChan)
        if (channel == null) {
            logger.error("Unable to get or join $targetChan to send message from ${msg.message.sender.displayName}")
            logger.debug(msg.toString())
            return
        }

        val platMessage = msg.message
        val discordMessageId = (platMessage as? DiscordMessage)?.messageId
        val replyToMsgId = (platMessage as? DiscordMessage)?.referencedMessage?.messageId
            ?.let { bridge.messageIds.getIrcMsgId(it) }
            ?.takeIf { canSendReplyTags() }
        val includeReplyContext = replyToMsgId == null || bridge.config.irc.discordReplyContextWithTags

        val ircLines = IrcMessageFormatter.format(platMessage, bridge.config, includeReplyContext)
        val replyTags = replyToMsgId?.let { listOf("+$REPLY_TAG" to it) } ?: emptyList()
        val echoTrackingId = discordMessageId?.takeIf { isCapabilityEnabled(CapabilityManager.Defaults.ECHO_MESSAGE) }
        val useLabels = isCapabilityEnabled(CapabilityManager.Defaults.LABELED_RESPONSE)
        ircLines.forEachIndexed { lineIndex, line ->
            // split long lines ourselves, as sendMultiLineMessage would, so that tags can be attached to each part
            IrcLineSplitter.split(line, getRemainingBytes(channel.messagingName)).forEachIndexed { partIndex, part ->
                var tags = replyTags
                if (echoTrackingId != null && lineIndex == 0 && partIndex == 0) {
                    // a label lets us match the echo even if the server altered the text
                    val label = if (useLabels) "d4i${labelCounter.incrementAndGet()}" else null
                    echoTracker.expect(channel.messagingName, part, echoTrackingId, label)
                    label?.let { tags = tags + (LABEL_TAG to it) }
                }
                ircConn.sendRawLine("${formatTags(tags)}PRIVMSG ${channel.messagingName} :$part")
            }
        }

        if (ircLines.isNotEmpty()) {
            bridge.updateStatistics(msg, Instant.now())
        }
    }

    fun sendNotice(targetUser: String, message: String) {
        if (!this::ircConn.isInitialized) {
            logger.error("IRC Connection has not been initialized yet!")
            return
        }

        for (line in message.split("\n")) {
            ircConn.sendMultiLineNotice(targetUser, line)
        }
    }

    /**
     * Called when the server echoes back a message this bot sent (IRCv3 echo-message)
     *
     * @param label the IRCv3 label the echo carries
     * @param batch the batch the echo arrived in, which carries the label if the server wrapped the echo in one
     */
    fun onOwnMessageEcho(channelName: String, text: String, msgId: String?, label: String?, batch: String?) {
        if (msgId == null) {
            return
        }

        val echoLabel = label ?: batch?.let { synchronized(labeledBatches) { labeledBatches.remove(it) } }
        val discordMessageId = echoTracker.match(channelName, text, echoLabel) ?: return
        logger.debug("Discord message {} was bridged to IRC as msgid {}", discordMessageId, msgId)
        bridge.messageIds.put(discordMessageId, msgId)
    }

    /**
     * Called when the server starts a labeled-response batch, which it does when a labeled line has several responses
     */
    fun onLabeledBatch(reference: String, label: String) {
        synchronized(labeledBatches) {
            labeledBatches[reference] = label
        }
    }

    /**
     * Called when the IRC connection closes. Nothing we sent before it will be echoed.
     */
    fun onConnectionClosed() {
        echoTracker.clear()
        synchronized(labeledBatches) {
            labeledBatches.clear()
        }
    }

    /**
     * Records the Discord message an IRC message was bridged as, so Discord replies to it can be threaded on IRC
     */
    fun onBridgedToDiscord(ircMsgId: String, discordMessageId: Long) {
        logger.debug("IRC msgid {} was bridged to Discord as message {}", ircMsgId, discordMessageId)
        bridge.messageIds.put(discordMessageId, ircMsgId)
    }

    private fun isCapabilityEnabled(name: String): Boolean {
        return ircConn.capabilityManager.getCapability(name).isPresent
    }

    private fun canSendReplyTags(): Boolean {
        if (!bridge.config.irc.discordReplyTags || !isCapabilityEnabled(CapabilityManager.Defaults.MESSAGE_TAGS)) {
            return false
        }

        val clientTagDeny = ircConn.serverInfo.getISupportParameter("CLIENTTAGDENY").flatMap { it.value }.orElse(null)
        return !isClientTagDenied(clientTagDeny, REPLY_TAG)
    }

    /**
     * Bytes left for message content in a PRIVMSG to the target, as the IRC library calculates it but in UTF-8 bytes.
     * Message tags don't count, servers limit them separately.
     */
    private fun getRemainingBytes(target: String): Int {
        // :nick!name@host PRIVMSG TARGET :MESSAGE\r\n
        val maskBytes = ircConn.user.map { IrcLineSplitter.utf8Length(it.name) }.orElse(100)
        return 505 - maskBytes - IrcLineSplitter.utf8Length(target) - "PRIVMSG".length
    }

    /**
     * Gets a channel by name, joining it if necessary
     */
    private fun getChannelByName(name: String): Channel? {
        val chan = ircConn.getChannel(name).toNullable()
        if (chan == null) {
            logger.warn("Bridge not in expected channel $name, was it kicked?")
            logger.debug("Attempting to rejoin $name")
            ircConn.addChannel(name)
        }

        return chan ?: ircConn.getChannel(name).toNullable()
    }

    /**
     * Gets the IRC bot user's nickname
     */
    fun getBotNick(): String {
        return ircConn.nick
    }

    /**
     * Sends a message to the bridge for processing
     */
    fun sendToBridge(message: PlatformMessage) {
        bridge.submitMessage(BridgeMessage(message))
    }

    /**
     * Signals the bridge that the pier needs to shut down
     */
    fun signalShutdown(inErr: Boolean) {
        this.bridge.shutdown(inErr)
    }

    /**
     * Handle tasks that need to be done after connection but before the bot can start being used
     */
    fun runPostConnectTasks() {
        // execute any startup commands, never logging their text as they often carry passwords
        val commands = bridge.config.irc.startupRawCommands
        for ((index, command) in commands.withIndex()) {
            logger.debug("Sending raw init command {} of {} ({})", index + 1, commands.size, command.substringBefore(' '))
            ircConn.sendRawLine(command)
        }

        // join all mapped channels
        for (mapping in bridge.config.channelMappings) {
            logger.debug("Joining ${mapping.ircChannel}")
            ircConn.addChannel(mapping.ircChannel)
        }
    }
}
