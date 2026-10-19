/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge

import org.spongepowered.configurate.CommentedConfigurationNode
import java.util.regex.Pattern

val MATCH_ALL_INDIV = Regex(".")
/**
 * Main bridge configuration data class
 */
data class BridgeConfiguration(
    val bridgeName: String,
    val announceJoinsQuits: Boolean,
    val announceExtras: Boolean,
    val channelMappings: List<ChannelMapping>,
    val irc: IrcConfiguration,
    val discord: DiscordConfiguration,
    val rawNode: CommentedConfigurationNode
) {
    /** toString with no sensitive info */
    fun toLoggable(): String {
        return "BridgeConfiguration(" +
                "bridgeName='$bridgeName', " +
                "announceJoinsQuits=$announceJoinsQuits, " +
                "announceExtras=$announceExtras, " +
                "channelMappings=$channelMappings, " +
                irc.toLoggable() + " " +
                discord.toLoggable() + " " +
                "rawNode=(hash) ${rawNode.hashCode()})"
    }
}

data class IrcConfiguration (
    val hostname: String,
    val password: String?,
    val port: Int,
    val sslEnabled: Boolean,
    val allowInvalidCerts: Boolean,
    val nickName: String,
    val userName: String,
    val realName: String,
    val antiPing: Boolean,
    val useNickNameColor: Boolean,
    val noPrefixRegex: Pattern?,
    val announceForwardedCommands: Boolean,
    val discordReplyContextLimit: Int,
    val startupRawCommands: List<String>,
    val sendDiscordEmbeds: Boolean,
    val discordReplyTags: Boolean,
    val discordReplyContextWithTags: Boolean,
    val antiPingStyle: AntiPingStyle,
    val antiPingMentions: Boolean
) {
    fun toLoggable(): String {
        return "IrcConfiguration(" +
                "hostname='$hostname', " +
                "password=${password?.replace(MATCH_ALL_INDIV, "*")}, " +
                "port=$port, " +
                "sslEnabled=$sslEnabled, " +
                "allowInvalidCerts=$allowInvalidCerts, " +
                "nickName='$nickName', " +
                "userName='$userName', " +
                "realName='$realName', " +
                "antiPing=$antiPing, " +
                "useNickNameColor=$useNickNameColor, " +
                "noPrefixRegex=$noPrefixRegex, " +
                "announceForwardedCommands=$announceForwardedCommands, " +
                "discordReplyContextLimit=$discordReplyContextLimit, " +
                "startupRawCommands=$startupRawCommands" +
                "sendDiscordEmbeds=$sendDiscordEmbeds, " +
                "discordReplyTags=$discordReplyTags, " +
                "discordReplyContextWithTags=$discordReplyContextWithTags, " +
                "antiPingStyle=${antiPingStyle.configName}, " +
                "antiPingMentions=$antiPingMentions" +
                ")"
    }
}

/**
 * Where zero width spaces go in bridged nicknames, so they don't highlight anyone on IRC
 */
enum class AntiPingStyle(val configName: String) {
    /** After every other character, as upstream does */
    EVERY_OTHER("every-other"),

    /** Once, after the first letter or digit. Many zero width spaces trip spam filters such as UnrealIRCd's antimixedutf8. */
    SINGLE("single");

    companion object {
        fun fromConfigName(name: String): AntiPingStyle? = entries.firstOrNull { it.configName.equals(name.trim(), ignoreCase = true) }
    }
}

data class DiscordConfiguration(
    val apiKey: String,
    val webHooks: List<WebhookMapping>,
    val activityType: String,
    val activityDesc: String,
    val activityUrl: String,
    val onlineStatus: String,
    val suppressUrlPreview: Boolean,
    val threadIrcReplies: Boolean,
    val bridgeThreads: Boolean,
    val ircRepliesStartThreads: Boolean
) {
    fun toLoggable(): String {
        return "DiscordConfiguration(" +
                "apiKey='${apiKey.replace(MATCH_ALL_INDIV, "*")}', " +
                "webHooks=${webHooks.map { it.toLoggable() }}, " +
                "activityType=${activityType}, " +
                "activityDesc=${activityDesc}, " +
                "activityUrl=${activityUrl}, " +
                "onlineStatus=${onlineStatus}" +
                "suppressUrlPreview=${suppressUrlPreview}, " +
                "threadIrcReplies=${threadIrcReplies}, " +
                "bridgeThreads=${bridgeThreads}, " +
                "ircRepliesStartThreads=${ircRepliesStartThreads}" +
                ")"
    }
}

/**
 * Simple channel-to-channel configuration data class
 */
data class ChannelMapping(
    val discordChannel: String,
    val ircChannel: String
)

/**
 * Simple discord-to-webhook configuration data class
 */
data class WebhookMapping(
    val discordChannel: String,
    val webhookUrl: String
) {
    /** toString with no sensitive info */
    fun toLoggable(): String {
        return "WebhookMapping(discordChannel='$discordChannel', webhookUrl='${webhookUrl.substring(0, 60)}')"
    }
}
