/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import club.minnced.discord.webhook.WebhookClient
import club.minnced.discord.webhook.WebhookClientBuilder
import club.minnced.discord.webhook.exception.HttpException
import club.minnced.discord.webhook.receive.ReadonlyMessage
import club.minnced.discord.webhook.send.AllowedMentions
import club.minnced.discord.webhook.send.WebhookMessageBuilder
import club.minnced.discord.webhook.util.WebhookErrorHandler
import io.zachbr.dis4irc.bridge.Bridge
import io.zachbr.dis4irc.bridge.message.BridgeMessage
import io.zachbr.dis4irc.bridge.message.BridgeSender
import io.zachbr.dis4irc.bridge.message.DiscordMessage
import io.zachbr.dis4irc.bridge.message.DiscordSource
import io.zachbr.dis4irc.bridge.message.IrcMessage
import io.zachbr.dis4irc.bridge.message.PlatformMessage
import io.zachbr.dis4irc.bridge.message.PlatformSource
import io.zachbr.dis4irc.bridge.pier.Pier
import io.zachbr.dis4irc.util.replaceTarget
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Activity.ActivityType
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageReference
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.RestAction
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.restaction.pagination.PinnedMessagePaginationAction
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.slf4j.Logger
import java.time.Instant
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.function.Consumer

private const val ZERO_WIDTH_SPACE = 0x200B.toChar()
private const val MAX_STARTED_THREADS = 64

/**
 * A Discord channel messages are bridged from, and the thread within it if the message came from one
 */
internal data class BridgeChannel(val source: DiscordSource, val thread: ThreadChannel?)

class DiscordPier(private val bridge: Bridge) : Pier {
    internal val logger: Logger = bridge.logger
    internal val threadTracker = DiscordThreadTracker()
    internal val bridgesThreads: Boolean get() = bridge.config.discord.bridgeThreads
    private val threadStarts = ThreadStarts { logger.warn("Unable to start a Discord thread: $it") }
    // runs sends that had to wait for the message they reply to
    private val deferredSends = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "${bridge.config.bridgeName}-deferred-discord-sends").apply { isDaemon = true }
    }
    internal val replyOrdering = ReplyOrdering(deferredSends, { logger.error("Unable to send a message to Discord", it) })
    // messages a thread couldn't be started on, which are not tried again for a while
    private val failedThreadStarts = RecentFailures()
    // threads the bridge started, until the Discord cache has them
    private val startedThreads = object : LinkedHashMap<Long, ThreadChannel>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ThreadChannel>?): Boolean = size > MAX_STARTED_THREADS
    }
    private val webhookMap = HashMap<String, WebhookClient>()
    private var botAvatarUrl: String? = null
    private lateinit var discordApi: JDA

    override fun start() {
        logger.info("Connecting to Discord API...")

        val intents = listOf(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_EXPRESSIONS, GatewayIntent.MESSAGE_CONTENT)
        val discordApiBuilder = JDABuilder.createLight(bridge.config.discord.apiKey, intents)
            .setMemberCachePolicy(MemberCachePolicy.ALL) // so we can cache invisible members and ping people not online
            .enableCache(CacheFlag.EMOJI, CacheFlag.STICKER)
            .addEventListeners(DiscordMsgListener(this))
            .setStatus(getEnumFromString(bridge.config.discord.onlineStatus, "Unknown online-status specified"))

        if (bridge.config.discord.activityDesc.isNotBlank()) {
            val activityType: ActivityType = getEnumFromString(bridge.config.discord.activityType, "Unknown activity-type specified")
            discordApiBuilder.setActivity(Activity.of(activityType, bridge.config.discord.activityDesc, bridge.config.discord.activityUrl))
        }

        if (bridge.config.announceJoinsQuits) {
            discordApiBuilder.addEventListeners(DiscordJoinQuitListener(this))
        }

        discordApi = discordApiBuilder
            .build()
            .awaitReady()

        // init webhooks
        if (bridge.config.discord.webHooks.isNotEmpty()) {
            logger.info("Initializing Discord webhooks")
            val webhookErrorHandler = WebhookErrorHandler { client, message, throwable ->
                logger.error("Webhook ${client.id}: $message")
                throwable?.printStackTrace()
            }

            for (hook in bridge.config.discord.webHooks) {
                val webhook: WebhookClient
                try {
                    webhook = WebhookClientBuilder(hook.webhookUrl).build()
                } catch (ex: IllegalArgumentException) {
                    logger.error("Webhook for ${hook.discordChannel} with url ${hook.webhookUrl} is not valid!")
                    ex.printStackTrace()
                    continue
                }

                webhook.setErrorHandler(webhookErrorHandler)
                webhookMap[hook.discordChannel] = webhook
                logger.info("Webhook for ${hook.discordChannel} registered")
            }
        }

        botAvatarUrl = discordApi.selfUser.avatarUrl

        if (bridge.config.discord.ircRepliesStartThreads && !bridge.config.discord.bridgeThreads) {
            logger.warn("discord-options.irc-replies-start-threads needs bridge-threads, so IRC replies won't start threads")
        }

        logger.info("Discord Bot Invite URL: ${discordApi.getInviteUrl()}")
        logger.info("Connected to Discord!")
    }

    override fun onShutdown() {
        deferredSends.shutdownNow()

        // shutdown can be called when discord fails to init
        if (this::discordApi.isInitialized) {
            discordApi.shutdownNow()
        }

        for (client in webhookMap.values) {
            client.close()
        }
    }

    override fun sendMessage(targetChan: String, msg: BridgeMessage) {
        // without threading IRC replies the parent is never looked up, so nothing needs to wait, as upstream
        if (!bridge.config.discord.threadIrcReplies) {
            sendMessageNow(targetChan, msg)
            return
        }

        // an IRC reply can arrive before Discord has an id for the message it replies to, so it waits for that
        val ircMessage = msg.message as? IrcMessage
        replyOrdering.submit(targetChan, ircMessage?.msgId, ircMessage?.replyToMsgId) { parentWait ->
            val parent = ircMessage?.replyToMsgId
            when (parentWait) {
                ParentWait.FAILED -> logger.debug("The Discord copy of IRC msgid {} failed to send, sending its reply unthreaded", parent)
                ParentWait.TIMED_OUT -> logger.debug("The Discord copy of IRC msgid {} wasn't sent in time, sending its reply unthreaded", parent)
                ParentWait.ARRIVED, ParentWait.NONE -> {}
            }
            sendMessageNow(targetChan, msg)
        }
    }

    private fun sendMessageNow(targetChan: String, msg: BridgeMessage) {
        if (!this::discordApi.isInitialized) {
            logger.error("Discord Connection has not been initialized yet!")
            failInFlight(msg.message)
            return
        }

        val channel = getTextChannelBy(targetChan)
        if (channel == null) {
            logger.error("Unable to get a discord channel for: $targetChan | Is bot present?")
            failInFlight(msg.message)
            return
        }

        val webhook = webhookMap[targetChan]
        val guild = channel.guild
        val platMessage = msg.message

        // make sure to replace clearly separated mentions first to not replace partial mentions
        replaceMentions(guild, platMessage, true)

        // replace mentions but don't require separation to find some previously missed, non-separated ones
        replaceMentions(guild, platMessage, false)

        // convert emotes to show properly
        for (emoji in guild.emojiCache) {
            val mentionTrigger = ":${emoji.name}:"
            platMessage.contents = replaceTarget(platMessage.contents, mentionTrigger, emoji.asMention)
        }

        // Discord won't broadcast messages that are just whitespace
        if (platMessage.contents.trim() == "") {
            platMessage.contents = "$ZERO_WIDTH_SPACE"
        }

        if (bridge.config.discord.suppressUrlPreview) {
            platMessage.contents = wrapUrlsInBrackets(platMessage.contents)
        }

        // Discord refuses longer messages, so drop them here rather than after starting a thread for them.
        // Upstream loses them too, just with a stack trace.
        if (!fitsDiscord(platMessage.contents, botPrefix(msg), webhook != null, Message.MAX_CONTENT_LENGTH)) {
            logger.warn("Not sending a message from ${platMessage.sender.displayName} to Discord, it is longer than Discord allows")
            failInFlight(platMessage)
            return
        }

        // webhooks can't send replies, so threaded replies from IRC are sent by the bot, if it can reply
        val replyTo = getReplyTarget(platMessage)
        val parentMessageId = getReplyParent(platMessage)
        val joinedThreadStart = parentMessageId != null && threadStarts.joinIfStarting(parentMessageId) { threadId ->
            sendMessageAfterThreadStart(channel, webhook, msg, threadId, replyTo)
        }

        if (!joinedThreadStart) {
            when (val plan = getReplyPlan(channel, parentMessageId)) {
                is IrcReplyPlan.IntoThread -> sendMessageToThread(channel, webhook, msg, plan.target, replyTo)
                is IrcReplyPlan.StartThread -> {
                    val start = { callback: (ThreadStartResult) -> Unit -> startThreadOn(channel, plan.parentMessageId, callback) }
                    threadStarts.whenStarted(plan.parentMessageId, start) { threadId ->
                        sendMessageAfterThreadStart(channel, webhook, msg, threadId, replyTo)
                    }
                }
                IrcReplyPlan.Channel -> sendMessageToChannel(channel, webhook, msg, replyTo)
            }
        }

        bridge.updateStatistics(msg, Instant.now())
    }

    /**
     * Sends an IRC reply into the thread started on its parent, or to the channel if no thread could be started
     */
    private fun sendMessageAfterThreadStart(channel: TextChannel, webhook: WebhookClient?, bMessage: BridgeMessage, threadId: Long?, replyTo: Long?) {
        guardInFlight(bMessage) {
            if (threadId == null) {
                sendMessageToChannel(channel, webhook, bMessage, replyTo)
            } else {
                val target = ThreadTarget(ThreadRef(threadId, channel.idLong), parentInThread = false)
                sendMessageToThread(channel, webhook, bMessage, target, replyTo)
            }
        }
    }

    /**
     * Runs part of a send that happens later, on a callback. If it throws, replies waiting on the message are released.
     */
    private fun guardInFlight(bMessage: BridgeMessage, send: () -> Unit) {
        replyOrdering.guard((bMessage.message as? IrcMessage)?.msgId, send)
    }

    /**
     * Starts a thread on a message, named after the message's text if the bot can read it
     */
    private fun startThreadOn(channel: TextChannel, parentMessageId: Long, callback: (ThreadStartResult) -> Unit) {
        val onFailure = Consumer<Throwable> { ex ->
            if (ex is ErrorResponseException && ex.errorResponse == ErrorResponse.THREAD_WITH_THIS_MESSAGE_ALREADY_EXISTS) {
                // a thread started from a message has the message's id
                threadTracker.recordStartedThread(ThreadRef(parentMessageId, channel.idLong))
                callback(ThreadStartResult.AlreadyExists)
            } else {
                val message = "Unable to start a Discord thread on message $parentMessageId in ${channel.name}, not trying again for a while: $ex"
                if (failedThreadStarts.record(parentMessageId)) logger.debug(message) else logger.warn(message)
                callback(ThreadStartResult.Failed)
            }
        }

        val create = { name: String ->
            try {
                channel.createThreadChannel(name, parentMessageId).queue({ thread ->
                    threadTracker.recordStartedThread(ThreadRef(thread.idLong, channel.idLong))
                    synchronized(startedThreads) {
                        startedThreads[thread.idLong] = thread
                    }
                    logger.debug("Started Discord thread {} on message {} for an IRC reply", thread.id, parentMessageId)
                    callback(ThreadStartResult.Started(thread.idLong))
                }, onFailure)
            } catch (ex: Exception) {
                onFailure.accept(ex)
            }
        }

        if (!channel.guild.selfMember.hasPermission(channel, Permission.MESSAGE_HISTORY)) {
            create(DEFAULT_THREAD_NAME)
            return
        }

        channel.retrieveMessageById(parentMessageId).queue({ create(threadNameFrom(it.contentRaw)) }, { create(DEFAULT_THREAD_NAME) })
    }

    /**
     * Gets the Discord message an IRC message replies to, whether or not it should be sent as a Discord reply
     */
    private fun getReplyParent(platMessage: PlatformMessage): Long? {
        val replyToMsgId = (platMessage as? IrcMessage)?.replyToMsgId ?: return null
        return bridge.messageIds.getDiscordMessageId(replyToMsgId)
    }

    private fun getReplyPlan(channel: TextChannel, parentMessageId: Long?): IrcReplyPlan {
        val config = bridge.config.discord
        val canStartThreads = config.ircRepliesStartThreads && parentMessageId != null
            && !failedThreadStarts.isRecent(parentMessageId) && canStartThreadsIn(channel)
        return planIrcReply(parentMessageId, channel.idLong, config.bridgeThreads, config.threadIrcReplies, config.ircRepliesStartThreads,
            canStartThreads, threadTracker) { threadId ->
            getThreadChannel(threadId)?.let { ActiveThread(it.parentChannel.idLong, it.isLocked) }
        }
    }

    /**
     * Checks if the bot can start threads in a channel and post in them
     */
    private fun canStartThreadsIn(channel: TextChannel): Boolean {
        return channel.guild.selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.CREATE_PUBLIC_THREADS, Permission.MESSAGE_SEND_IN_THREADS)
    }

    /**
     * Gets a thread from the cache, or one the bridge just started that the cache doesn't have yet
     */
    private fun getThreadChannel(threadId: Long): ThreadChannel? {
        return discordApi.getThreadChannelById(threadId) ?: synchronized(startedThreads) { startedThreads[threadId] }
    }

    private fun sendMessageToChannel(channel: TextChannel, webhook: WebhookClient?, bMessage: BridgeMessage, replyTo: Long?) {
        val canReply = replyTo != null && canReplyIn(channel)
        sendMessageByRoute(channel, webhook, bMessage, chooseRoute(webhook != null, replyTo, canReply))
    }

    private fun sendMessageByRoute(channel: TextChannel, webhook: WebhookClient?, bMessage: BridgeMessage, route: DiscordRoute) {
        when (route) {
            DiscordRoute.Webhook -> webhook?.let {
                sendMessageWebhook(channel.guild, it, bMessage).whenComplete { _, ex -> if (ex != null) failInFlight(bMessage.message) }
            }
            is DiscordRoute.Bot -> {
                if (route.replyTo == null) {
                    sendMessageOldStyle(channel, bMessage)
                    return
                }

                sendMessageOldStyle(channel, bMessage, route.replyTo) { ex ->
                    // only retry when Discord refused the reply, other failures may come after it was sent
                    if (ex is ErrorResponseException && isDiscordRefusal(ex.errorCode)) {
                        logger.warn("Discord refused a reply in ${channel.name} (${ex.errorCode}), sending it unthreaded instead")
                        guardInFlight(bMessage) { sendMessageByRoute(channel, webhook, bMessage, chooseRoute(webhook != null, null, false)) }
                    } else {
                        logger.warn("Unable to send a reply in ${channel.name}, not sending it again as it may have been posted: $ex")
                        failInFlight(bMessage.message)
                    }
                }
            }
        }
    }

    /**
     * Checks if the bot can send replies in a channel or thread. Discord also requires message history access for them.
     */
    private fun canReplyIn(channel: GuildMessageChannel): Boolean {
        return channel.canTalk() && channel.guild.selfMember.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_HISTORY)
    }

    /**
     * Posts an IRC reply into the Discord thread its parent is in or started, falling back to the channel itself
     * if the thread can't be posted to
     */
    private fun sendMessageToThread(channel: TextChannel, webhook: WebhookClient?, bMessage: BridgeMessage, target: ThreadTarget, replyTo: Long?) {
        val threadId = target.thread.threadId
        if (webhook != null) {
            // webhooks can't reply, but can post into threads of their channel. The thread client shares the
            // parent's queue and must never be closed, as that closes the parent too.
            sendMessageWebhook(channel.guild, webhook.onThread(threadId), bMessage)
                .thenAccept { sent -> threadTracker.recordMessage(sent.id, target.thread) }
                .exceptionally { ex ->
                    val cause = (ex as? CompletionException)?.cause ?: ex
                    // only when Discord refused the post, other failures may come after it was posted
                    if (cause is HttpException && isRefusal(cause.code)) {
                        logger.warn("Discord refused a post into thread $threadId (HTTP ${cause.code}), sending to ${channel.name} instead")
                        guardInFlight(bMessage) { sendMessageToChannel(channel, webhook, bMessage, replyTo) }
                    } else {
                        logger.warn("Unable to post into Discord thread $threadId, not sending it again as it may have been posted: $cause")
                        failInFlight(bMessage.message)
                    }
                    null
                }
            return
        }

        val thread = getThreadChannel(threadId)
        if (thread == null || !thread.canTalk()) {
            logger.debug("Unable to post into Discord thread {}, sending to {} instead", threadId, channel.name)
            sendMessageToChannel(channel, null, bMessage, replyTo)
            return
        }

        // replies only work within the same channel, so a thread's starter message can't be replied to from in it.
        // When IRC replies start threads, the thread itself shows what is replied to.
        val inlineReplies = !bridge.config.discord.ircRepliesStartThreads
        val threadReplyTo = replyTo?.takeIf { inlineReplies && target.parentInThread && canReplyIn(thread) }
        sendMessageOldStyle(thread, bMessage, threadReplyTo, target.thread) { ex ->
            // only when Discord refused the post, other failures may come after it was posted
            if (ex is ErrorResponseException && isDiscordRefusal(ex.errorCode)) {
                logger.warn("Discord refused a post into thread $threadId (${ex.errorCode}), sending to ${channel.name} instead")
                guardInFlight(bMessage) { sendMessageToChannel(channel, null, bMessage, replyTo) }
            } else {
                logger.warn("Unable to post into Discord thread $threadId, not sending it again as it may have been posted: $ex")
                failInFlight(bMessage.message)
            }
        }
    }

    /**
     * @param thread the thread the message is posted into, if any
     * @param onFailure called if Discord refuses the message, defaults to logging it
     */
    private fun sendMessageOldStyle(
        discordChannel: GuildMessageChannel,
        bMessage: BridgeMessage,
        replyTo: Long? = null,
        thread: ThreadRef? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        if (!discordChannel.canTalk()) {
            logger.warn("Bridge cannot speak in ${discordChannel.name} to send message: $bMessage")
            failInFlight(bMessage.message)
            return
        }

        val platMessage = bMessage.message
        val action = discordChannel.sendMessage(buildBotMessage("${botPrefix(bMessage)}${platMessage.contents}"))
        if (replyTo != null) {
            // if the original has been deleted, send it anyway rather than failing
            action.setMessageReference(replyTo).failOnInvalidReply(false)
        }
        // a caller's onFailure may send the message another way, so it decides whether the send failed for good
        val failure = Consumer<Throwable> { ex ->
            if (onFailure != null) {
                onFailure(ex)
            } else {
                failInFlight(platMessage)
                RestAction.getDefaultFailure().accept(ex)
            }
        }
        action.queue({ sent ->
            recordBridgedMessage(platMessage, sent.idLong)
            thread?.let { threadTracker.recordMessage(sent.idLong, it) }
        }, failure)
    }

    private fun sendMessageWebhook(guild: Guild, webhook: WebhookClient, bMessage: BridgeMessage): CompletableFuture<ReadonlyMessage> {
        val platMessage = bMessage.message
        val guildUser = getMemberByUserNameOrDisplayName(platMessage.sender.displayName, guild)
        var avatarUrl = guildUser?.effectiveAvatarUrl

        var senderName = enforceSenderName(platMessage.sender.displayName)
        // if sender is command, use bot's actual name and avatar if possible
        if (platMessage.sender == BridgeSender) {
            senderName = guild.getMember(discordApi.selfUser)?.effectiveName ?: senderName
            avatarUrl = botAvatarUrl ?: avatarUrl
        }

        val message = WebhookMessageBuilder()
            .setContent(platMessage.contents)
            .setUsername(senderName)
            .setAvatarUrl(avatarUrl)
            .setAllowedMentions(
                AllowedMentions()
                    .withParseUsers(true)
                    .withParseRoles(true)
            )
            .build()

        return webhook.send(message)
            .thenApply { sent ->
                recordBridgedMessage(platMessage, sent.id)
                sent
            }
    }

    /**
     * Gets the Discord message an IRC message replies to (IRCv3 reply tag), if it should be sent as a Discord reply
     */
    internal fun getReplyTarget(platMessage: PlatformMessage): Long? {
        if (!bridge.config.discord.threadIrcReplies) {
            return null
        }
        val replyToMsgId = (platMessage as? IrcMessage)?.replyToMsgId ?: return null
        return bridge.messageIds.getDiscordMessageId(replyToMsgId)
    }

    /**
     * Gets the channel to bridge a Discord channel's messages as, or null if they aren't bridged.
     * Threads are bridged as their parent text channel, and only if that channel is mapped.
     */
    internal fun getBridgeChannel(channel: MessageChannelUnion): BridgeChannel? {
        if (!isBridgeableChannel(channel.type, bridge.config.discord.bridgeThreads)) {
            return null
        }

        if (!channel.type.isThread) {
            return BridgeChannel(channel.asTextChannel().asPlatformSource(), null)
        }

        val thread = channel.asThreadChannel()
        // threads can also belong to forum and announcement channels, which aren't bridged
        val parent = thread.parentChannel as? TextChannel ?: return null
        val source = parent.asPlatformSource()
        if (bridge.channelMappings.getMappingFor(source) == null) {
            return null
        }

        return BridgeChannel(source, thread)
    }

    /**
     * Sends a message from a thread to the bridge as a reply to the message it continues, once that is known.
     * The first message of a thread not seen before waits on one request to Discord, and later ones wait behind it.
     */
    internal fun sendThreadMessageToBridge(thread: ThreadChannel, message: Message, platMessage: DiscordMessage) {
        val threadRef = ThreadRef(thread.idLong, platMessage.source.channelId)
        val reference = message.messageReference?.takeIf { it.type == MessageReference.MessageReferenceType.DEFAULT }
        val inThreadReplyTo = inThreadReplyTarget(thread.idLong, reference?.messageIdLong, reference?.channelIdLong)
        threadTracker.whenRootKnown(thread.idLong, { callback -> lookupThreadStarter(thread, callback) }) { startedFromMessage ->
            val threadParentId = threadTracker.parentFor(threadRef, message.idLong, inThreadReplyTo, startedFromMessage)
            sendToBridge(platMessage.copy(threadParentId = threadParentId))
        }
    }

    /**
     * Looks up whether a thread was started from a message in its parent channel, rather than standalone
     */
    private fun lookupThreadStarter(thread: ThreadChannel, callback: (Boolean) -> Unit) {
        val onFailure = { ex: Throwable ->
            // the starter was deleted, or we can't read the parent channel's history
            logger.debug("Unable to get the parent message of Discord thread {}: {}", thread.id, ex.toString())
            callback(false)
        }

        try {
            // a standalone thread's parent message is its "thread created" notice, which shares the thread's id
            thread.retrieveParentMessage().queue({ callback(it.type != MessageType.THREAD_CREATED) }, onFailure)
        } catch (ex: Exception) {
            onFailure(ex)
        }
    }

    /**
     * Remembers which Discord message an IRC message became, so Discord replies to it can be threaded on IRC
     */
    internal fun recordBridgedMessage(platMessage: PlatformMessage, discordMessageId: Long) {
        val ircMsgId = (platMessage as? IrcMessage)?.msgId ?: return
        bridge.ircConn.onBridgedToDiscord(ircMsgId, discordMessageId)
        // only now, with the id recorded, can replies waiting on this message go out
        replyOrdering.inFlight.complete(ircMsgId, discordMessageId)
    }

    /**
     * Gets the `<nick> ` prefix the bot puts in front of a message it sends for someone, empty for the bridge itself
     */
    private fun botPrefix(bMessage: BridgeMessage): String {
        return if (bMessage.originatesFromBridgeItself()) "" else "<${enforceSenderName(bMessage.message.sender.displayName)}> "
    }

    /**
     * Lets replies waiting on an IRC message go out unthreaded, as its Discord copy won't get an id
     */
    private fun failInFlight(platMessage: PlatformMessage) {
        val ircMsgId = (platMessage as? IrcMessage)?.msgId ?: return
        replyOrdering.inFlight.fail(ircMsgId)
    }

    /**
     * Checks if the message came from this bot
     */
    fun isThisBot(source: PlatformSource, userSnowflake: Long): Boolean {
        if (source !is DiscordSource) {
            return false
        }

        // check against the bot user and all webclients
        return isBridgeAuthor(userSnowflake, discordApi.selfUser.idLong, webhookMap.values.map { it.id })
    }

    /**
     * Sends a message to the bridge for processing
     */
    fun sendToBridge(message: DiscordMessage) {
        // try and resolve local snapshot mentions before they go across the bridge
        if (message.snapshots.isNotEmpty()) {
            for (snapshot in message.snapshots) {
                val textChannel = getTextChannelBy(message.source.channelId.toString()) ?: continue
                snapshot.contents = parseMentionableToNames(textChannel.guild, snapshot.contents, requireSeparation = true)
                snapshot.contents = parseMentionableToNames(textChannel.guild, snapshot.contents, requireSeparation = false)
            }
        }

        bridge.submitMessage(BridgeMessage(message))
    }

    /**
     * Gets the pinned messages from the specified discord channel or null if the channel cannot be found
     */
    fun getPinnedMessages(channelId: String, callback: (List<DiscordMessage>?) -> Unit) {
        getTextChannelBy(channelId)?.let { channel ->
            channel.retrievePinnedMessages().queue { pinnedMessages ->
                val platformMessages = pinnedMessages.map { it.message.toPlatformMessage(logger) }
                callback(platformMessages)
            }
        } ?: callback(null)
    }

    /**
     * Gets a text channel by snowflake ID or string
     */
    private fun getTextChannelBy(string: String): TextChannel? {
        val byId = discordApi.getTextChannelById(string)
        if (byId != null) {
            return byId
        }

        val byName = discordApi.getTextChannelsByName(string, false)
        return if (byName.isNotEmpty()) byName.first() else null
    }

    private fun replaceMentions(guild: Guild, msg: PlatformMessage, requireSeparation: Boolean) {
        // convert name use to proper mentions
        for (member in guild.memberCache) {
            val mentionTrigger = "@${member.effectiveName}" // require @ prefix
            msg.contents = replaceTarget(msg.contents, mentionTrigger, member.asMention, requireSeparation)
        }

        // convert role use to proper mentions
        for (role in guild.roleCache) {
            if (!role.isMentionable) {
                continue
            }

            val mentionTrigger = "@${role.name}" // require @ prefix
            msg.contents = replaceTarget(msg.contents, mentionTrigger, role.asMention, requireSeparation)
        }

        // convert text channels to mentions
        for (guildChannel in guild.textChannelCache) {
            val mentionTrigger = "#${guildChannel.name}"
            msg.contents = replaceTarget(msg.contents, mentionTrigger, guildChannel.asMention, requireSeparation)
        }
    }

    private fun parseMentionableToNames(guild: Guild, contents: String, requireSeparation: Boolean): String {
        var newContents = contents

        // convert name use to proper mentions
        for (member in guild.memberCache) {
            newContents = replaceTarget(newContents, member.asMention, member.effectiveName, requireSeparation)
        }

        // convert role use to proper mentions
        for (role in guild.roleCache) {
            if (!role.isMentionable) {
                continue
            }

            newContents = replaceTarget(newContents, role.asMention, role.name, requireSeparation)
        }

        // convert text channels to mentions
        for (guildChannel in guild.textChannelCache) {
            newContents = replaceTarget(newContents, guildChannel.asMention, guildChannel.name, requireSeparation)
        }

        return newContents;
    }

    private fun getMemberByUserNameOrDisplayName(name: String, guild: Guild, ignoreCase: Boolean = true): Member? {
        // check by username first
        var matchingUsers = guild.getMembersByName(name, ignoreCase)
        // if no results, check by their nickname instead
        if (matchingUsers.isEmpty()) {
            matchingUsers = guild.getMembersByNickname(name, ignoreCase)
        }
        // if we still don't have any results, fire off a findMembers call to look it up (and cache it for later)
        // this won't help us with this specific call (we don't really want to wait around for this task to come back),
        // but it will help us with future calls to this and other functions, so the next time they talk, we'll have it.
        if (matchingUsers.isEmpty()) {
            guild.findMembers { it.user.name.equals(name, ignoreCase) || it.nickname.equals(name, ignoreCase) }
                .onSuccess { logger.debug("Cached ${it.size} results for user lookup: $name") }
        }

        return matchingUsers.firstOrNull()
    }

    /**
     * Gets an enum from the given string or throw an IllegalArgumentException with the given error message
     */
    private inline fun <reified T: Enum<T>> getEnumFromString(userInput: String, errorMessage: String): T {
        val inputUpper = userInput.uppercase()
        for (poss in T::class.java.enumConstants) {
            if (poss.name.uppercase() == inputUpper) {
                return poss
            }
        }

        throw IllegalArgumentException("$errorMessage: $userInput")
    }
}

private const val NICK_ENFORCEMENT_CHAR = "-"

/**
 * Builds a message for the bot to send. Like webhook messages, it can only mention users and roles, never everyone
 * or here, and a reply doesn't ping the author of the message it replies to.
 */
fun buildBotMessage(content: String): MessageCreateData {
    return MessageCreateBuilder()
        .setContent(content)
        .setAllowedMentions(EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE))
        .mentionRepliedUser(false)
        .build()
}

/**
 * Ensures name is within Discord's requirements
 */
fun enforceSenderName(name: String): String {
    if (name.length < 2) {
        return NICK_ENFORCEMENT_CHAR + name + NICK_ENFORCEMENT_CHAR
    }

    if (name.length > 32) {
        return name.substring(0, 32)
    }

    return name
}

/**
 * Wraps URLs within a string in angle brackets.
 *
 * @param text string to process
 * @return string with URLs wrapped in <>.
 */
fun wrapUrlsInBrackets(text: String): String {
    // discord only links for http[s]:// URLs
    val urlRegex = """(?<!<)https?://\S+""".toRegex(RegexOption.IGNORE_CASE) // don't match when already wrapped

    return urlRegex.replace(text) { match ->
        val originalUrl = match.value
        val lastUrlCharIndex = originalUrl.indexOfLast { it !in ".,;:'!?)" }

        if (lastUrlCharIndex == -1) {
            return@replace originalUrl
        }

        val url = originalUrl.substring(0, lastUrlCharIndex + 1)
        val suffix = originalUrl.substring(lastUrlCharIndex + 1)

        return@replace "<${url}>${suffix}"
    }
}

