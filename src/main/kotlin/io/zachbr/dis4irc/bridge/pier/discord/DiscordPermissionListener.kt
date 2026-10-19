/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.discord

import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent
import net.dv8tion.jda.api.events.guild.override.GenericPermissionOverrideEvent
import net.dv8tion.jda.api.events.role.update.RoleUpdatePermissionsEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

/**
 * Checks the bot's permissions in mapped channels again whenever they may have changed
 */
class DiscordPermissionListener(private val pier: DiscordPier) : ListenerAdapter() {
    override fun onGenericPermissionOverride(event: GenericPermissionOverrideEvent) = pier.checkChannelPermissions()

    override fun onRoleUpdatePermissions(event: RoleUpdatePermissionsEvent) = pier.checkChannelPermissions()

    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
        if (event.member.idLong == event.jda.selfUser.idLong) {
            pier.checkChannelPermissions()
        }
    }

    override fun onGuildMemberRoleRemove(event: GuildMemberRoleRemoveEvent) {
        if (event.member.idLong == event.jda.selfUser.idLong) {
            pier.checkChannelPermissions()
        }
    }
}
