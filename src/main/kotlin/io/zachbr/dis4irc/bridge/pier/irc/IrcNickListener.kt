/*
 * This file is part of Dis4IRC.
 *
 * Copyright (c) Dis4IRC contributors
 *
 * MIT License
 */

package io.zachbr.dis4irc.bridge.pier.irc

import net.engio.mbassy.listener.Handler
import org.kitteh.irc.client.library.element.User
import org.kitteh.irc.client.library.event.client.ClientReceiveCommandEvent
import org.kitteh.irc.client.library.feature.filter.CommandFilter

/**
 * Follows nick changes the IRC library drops because the server sent them with a bare `:nick` prefix.
 *
 * UnrealIRCd tells a client about its own nick change as `:oldnick NICK :newnick`, without the `!user@host` part. It
 * does so when NickServ RECOVER moves the bridge onto its nick after it connected as `nick` plus a backtick. The IRC
 * library only reads `nick!user@host` as a user, so it drops the line and keeps the old nick. The bridge would then
 * no longer recognise its own echoes.
 */
class IrcNickListener(private val pier: IrcPier) {
    @CommandFilter("NICK")
    @Handler
    fun onNick(event: ClientReceiveCommandEvent) {
        // the IRC library handles changes it can read as a user's
        if (event.actor is User) {
            return
        }

        val newNick = event.parameters.firstOrNull() ?: return
        pier.onBareNickChange(event.actor.name, newNick)
    }
}
