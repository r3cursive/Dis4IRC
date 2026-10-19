# Dis4IRC fork

This is [r3cursive/Dis4IRC](https://github.com/r3cursive/Dis4IRC), a fork of
[zachbr/Dis4IRC](https://github.com/zachbr/Dis4IRC). Its patch series bridges
Discord threads to IRC reply chains and hardens upstream PR #95's reply
bridging. It also keeps up with Discord API changes.

- Branch: `threads`, with commits 1 to 20 as shipped at `633103e`
- Next: `threads-next` adds commits 21 to 24, until they ship
- Upstream: `zachbr/Dis4IRC` branch `master`
- Upstream base: `6275040` ("Update Github Actions versions", version 1.7.4-SNAPSHOT)

The `threads` branch is upstream `master` plus the commits in the ledger below, in
order. Every commit passes `./gradlew build` on its own.

## Credit

The first four commits are Rusty Bower's upstream
[PR #95](https://github.com/zachbr/Dis4IRC/pull/95), "Bridge replies both ways
using IRCv3 reply tags". They are cherry-picked unchanged and keep his
authorship. Everything after them builds on his work: the msgid cache, the echo
tracking and the fake IRCv3 server tests.

Commits 5 to 13 fix findings from an independent audit of PR #95. The audit
found PR #95 safe as a base once those fixes are in. Each finding ID below is
the audit's. Two further reviews of the thread work led to fixes folded into
commits 5 and 18.

## Patch ledger

Commits are named by subject, since SHAs change on every rebase.

### 1 to 4. PR #95: bridge replies both ways using IRCv3 reply tags

- Commits:
  - Send Discord replies to IRC as IRCv3 threaded replies
  - Log reply-threading message id mappings at debug level
  - Send IRC replies to Discord as replies, only add reply context when unthreaded
  - Test reply threading against a fake IRCv3 server
- Why: Discord replies go to IRC with the `+draft/reply` tag, and IRC replies
  arrive on Discord as real replies. The rest of the series needs this plumbing.
- Upstreamability: this is upstream's own open PR.
- Related: zachbr/Dis4IRC#95.
- Conflict risk: none while #95 is open.

### 5 to 13. Fixes from the PR #95 audit

These touch only PR #95's code and upstream code around it. Each could be
offered upstream on its own, or folded into #95. Their conflict risk is medium,
since any change to #95 before it merges touches the same lines.

| # | Commit | ID | Problem | Fix | Test |
|---|---|---|---|---|---|
| 5 | discord: only send IRC replies as bot replies when the bot can reply | H-A | Replies the bot couldn't post were dropped without a word. Discord also refuses replies without Read Message History. | A pure route choice sends replies as bot replies only when the bot can reply. Refused replies are resent unthreaded. | `DiscordRouteTest` |
| 6 | discord: never let IRC replies ping everyone or here | H-B | Bot replies allowed every mention type, so IRC users could ping `@everyone`. | Users and roles only, like webhooks, and no ping for the replied-to author. | `DiscordPierTest` |
| 7 | irc: keep own echoes and init commands out of the debug log | M-A | echo-message sent the NickServ password back, and it was logged at debug level. | Own private echoes are ignored. Init commands are logged by verb only. | `IrcLoggingTest` |
| 8 | irc: match echoes by label instead of exact text | M-B, M-C, L-D | Servers alter echoes, so exact text matching missed them. Entries also expired during long send backlogs. | Labels via labeled-response, with a formatting-blind text fallback. 10 minute expiry, cleared on disconnect. | `IrcEchoLabelTest`, `IrcReplyTagsTest` |
| 9 | irc: split long lines by UTF-8 bytes on code point boundaries | L-B | The library cutter threw on long emoji runs and made parts of 880 bytes. | A byte-measuring splitter that never splits a code point. | `IrcLineSplitterTest`, `IrcReplyThreadingTest` |
| 10 | irc: register listeners before connecting | L-C | The echo-message request could miss the server's capability list. | Build, register listeners, then connect. | `IrcReplyThreadingTest` |
| 11 | irc: map every part of a bridged message to its Discord copy | L-A | Only the first line was mapped, and that could be the "Reply to" context line. | Every part maps to the Discord message. Discord replies point at the first content line. | `IrcReplyThreadingTest`, `IrcReplyTagsTest` |
| 12 | irc: serialize tracked sends | L-E | Concurrent sends could queue and send in different orders, losing mappings. | Queue and send under one lock. | `IrcReplyThreadingTest` |
| 13 | irc: request echo-message whenever reply threading needs it | L-F | echo-message was only requested with `discord-reply-tags` on. | Requested when any reply or thread option needs it. | `IrcReplyThreadingTest` |

Two notes on the tests. The race in commit 10 can't be reproduced on demand, so
its test guards that echo-message is negotiated on every connect. The
concurrency test in commit 12 failed three runs out of three before the fix.

### 14. deps: update JDA to 6.7.0

- Why: Discord makes
  [Channel Obfuscation](https://docs.discord.com/developers/change-log#channel-obfuscation-for-users-and-bots)
  mandatory on 2026-11-16. JDA 6.7.0 is the release that handles obfuscated
  channels. It is a breaking release, but no Dis4IRC call sites change.
- Files: `build.gradle.kts`.
- Upstreamability: high. Upstream will make the same bump.
- Related: discord-jda/JDA#3042.
- Conflict risk: low. Drop it once upstream is on 6.7.0 or newer.

### 15. mutators: optionally collapse newlines when shortening

- Why: production runs the shortener as it was at `9bf1c0f`, which joined lines
  with spaces. Upstream's `7002fa4` keeps newlines. The new
  `collapse-new-lines` option brings the joining back, off by default.
- Files: `ShortenLongMessages.kt`, `ShortenLongMessagesTest.kt`.
- Upstreamability: medium, as a small option.
- Conflict risk: low.

### 16. irc: optional single zero-width space anti-ping

- Why: the live e2e gate found 129irc refusing every Discord line with a
  `<nick>` prefix: `404 ... Mixed character spam (#bridgetest)`. That is
  UnrealIRCd's antimixedutf8 module. Upstream's anti-ping puts a zero width
  space after every other character, about ten in a 21 character name.
- Fix: `irc.anti-ping-style` takes `every-other`, the default, or `single`. The
  single style puts one zero width space after the first character. That is
  what matterbridge does, and its lines were delivered in the same live run.
- It also explains an old production bug. Messages from the Discord user
  "virtual potato" vanished on IRC.
- Files: `ConfigurationData.kt`, `ConfigurationUtils.kt`,
  `IrcMessageFormatter.kt`, `README.md`, `IrcAntiPingTest.kt`.
- Upstreamability: high. Any UnrealIRCd network with antimixedutf8 hits this.
- Conflict risk: low.

### 17. irc: send the ratified +reply tag alongside +draft/reply

- Why: the reply tag was ratified as `+reply`, while many clients and servers
  still only know `+draft/reply`. The bridge sends both. Each tag is checked
  against `CLIENTTAGDENY` on its own.
- Upstreamability: high, as a follow-up to #95 or folded into it.
- Related: zachbr/Dis4IRC#95, ircv3/ircv3-specifications#535.
- Conflict risk: medium. It edits lines that PR #95 adds.

### 18. discord: bridge thread messages as IRC reply chains

- Why: Dis4IRC drops every message outside plain text channels, so thread
  conversations never reach IRC. This maps a Discord thread to an IRC reply
  chain and back, behind `discord-options.bridge-threads`.
- Review fixes folded in: posts into threads fall back to the channel on any
  refusal. The thread starter lookup is asynchronous, off the gateway thread.
  Resends happen only when Discord refused a post, never after a timeout or a
  server error.
- Files: `DiscordThreads.kt` (new), `DiscordPier.kt`, `DiscordMsgListener.kt`,
  `discord/Extensions.kt`, `PlatformMessage.kt`, `IrcPier.kt`,
  `ConfigurationData.kt`, `ConfigurationUtils.kt`, `README.md`, plus tests.
- Upstreamability: medium. It depends on #95.
- Related: zachbr/Dis4IRC#58 (Support threads), zachbr/Dis4IRC#95.
- Conflict risk: high. It touches the Discord message path and the send paths.

### 19. discord: start a thread when an IRC user replies

- Why: IRC users have no other way to start a Discord thread. With
  `discord-options.irc-replies-start-threads`, an IRC reply to a message
  without a thread starts one on it and is posted inside.
- Files: `DiscordThreads.kt`, `DiscordPier.kt`, `ConfigurationData.kt`,
  `ConfigurationUtils.kt`, `README.md`, plus tests.
- Upstreamability: medium, together with commit 18.
- Conflict risk: high, for the same reasons as commit 18.

### 20. docs: add FORK.md ledger of carried patches

- Why: this file.
- Upstreamability: none. Never send it upstream.
- Conflict risk: none.

Commits 1 to 20 are what production first shipped, at `633103e`. Commits 21 to
24 are follow-ups from a third review. They ship in a later bump, after their
own live e2e run.

### 21. discord: only resend after Discord refused a post

- Why: a resend decision now rests on Discord's own error code, 10000 or
  above, rather than the HTTP status. JDA never reports such a code for a failed
  connection or a server error. Locked threads (160005) still fall back.
  Failures that aren't refusals are warnings, never resent.
- Files: `DiscordRoute.kt`, `DiscordPier.kt`, `DiscordRouteTest.kt`.
- Upstreamability: with commits 5 and 18.
- Conflict risk: low.

### 22. discord: remember failed thread starts for 10 minutes

- Why: a failed thread start was retried on every later IRC reply to the same
  message. It is now remembered for 10 minutes, for up to 256 messages. The
  first failure is a warning, repeats are debug lines.
- Files: `DiscordThreads.kt`, `DiscordPier.kt`, `DiscordThreadsTest.kt`.
- Upstreamability: with commit 19.
- Conflict risk: low.

### 23. discord: keep IRC replies out of threads without thread-irc-replies

- Why: `thread-irc-replies` only turned off inline replies in the channel. IRC
  replies still went into threads, and could start them. Now IRC replies are
  plain messages whenever it is off, as upstream.
- Files: `DiscordThreads.kt`, `DiscordPier.kt`, `ConfigurationUtils.kt`,
  `README.md`, `DiscordThreadsTest.kt`.
- Upstreamability: with commits 18 and 19.
- Conflict risk: low.

### 24. docs: update FORK.md for the follow-ups

- Why: this file, for commits 21 to 23.
- Conflict risk: none.

## Configuration added by the fork

All new options default to upstream behaviour.

```hocon
irc {
    # "every-other" (default) or "single"
    anti-ping-style=every-other
}
discord-options {
    # Whether to bridge messages in public threads of mapped channels.
    bridge-threads=false
    # Whether an IRC reply to a message without a thread starts one.
    # Needs bridge-threads and thread-irc-replies.
    irc-replies-start-threads=false
}
mutators {
    shorten-long-messages {
        # Whether to join the lines of a shortened message with spaces
        collapse-new-lines=false
    }
}
```

PR #95 adds `irc.discord-reply-tags` (default `true`),
`irc.discord-reply-context-with-tags` (default `false`) and
`discord-options.thread-irc-replies` (default `true`). The shortener's
upstream keys are `max-message-length` (1800), `max-new-lines` (6) and
`shortened-suffix` (`[...]`).

### Recommended production settings

Production sets every option explicitly:

```hocon
irc {
    anti-ping-style=single
    discord-reply-tags=true
    discord-reply-context-with-tags=true
}
discord-options {
    thread-irc-replies=true
    bridge-threads=true
    irc-replies-start-threads=true
}
mutators {
    shorten-long-messages {
        max-message-length=400
        max-new-lines=3
        shortened-suffix="..."
        collapse-new-lines=true
    }
}
```

These settings keep the `Reply to "nick: context"` line on Discord replies, next
to the reply tags. Plain IRC clients then still see what a reply answers. The
name in that line uses the single anti-ping style too, so it passes the spam
filter. Discord replies to the message point at its content line, never the
context line.

## Design decisions

### A Discord thread is an IRC reply chain

IRCv3 has no thread primitive. The closest it came was proposal
ircv3/ircv3-specifications#226 (Conversation Threading), which was closed in
2017. What IRCv3 does have is the `reply` client tag, ratified in
ircv3/ircv3-specifications#535. Its client notes suggest showing a reply next to
its parent "to make a thread apparent". PR #95 checked live that IRCCloud shows
tagged replies threaded under their parent.

So the fork maps a thread to a reply chain:

- On IRC, each thread message is a reply to the thread's root, or to the message
  it replies to inline inside the thread.
- Plain IRC clients see ordinary channel messages. The fork adds no thread
  marker or prefix for them.
- On Discord, an IRC reply to anything in a thread is posted into that thread. If
  the thread is locked, deleted or refuses the post, the reply goes to the
  channel as in PR #95.
- IRC replies only go into threads with `thread-irc-replies` on as well.
  Without it they bridge as plain messages, as upstream does.
- All of it is off unless `bridge-threads` is set.

### An IRC reply starts a Discord thread

IRC clients draw `+reply` chains as threads. So from IRC, replying is the
natural way to start a thread. With `irc-replies-start-threads`, an IRC reply to
a Discord message without a thread starts one on it:

- The thread is named after the message, its first 60 or so characters without
  mentions and markdown. It falls back to "Thread from IRC".
- The reply is posted inside it. Later IRC replies to the message, or to
  anything in the thread, go there too.
- Discord messages typed in the thread reach IRC as replies to the message.
- Only one thread is started per message, even if several replies arrive first.
- If Discord says the message already has a thread, the fork uses it, since it
  has the message's ID.
- Without Create Public Threads and Send Messages in Threads, replies go to the
  channel as before.
- A failed start is remembered for 10 minutes, so later replies to that message
  go straight to the channel. The first failure is a warning, repeats are
  debug lines.
- With the option on, the bot no longer sends inline replies, except on that
  fallback. The thread shows what is being replied to.

This replaces an earlier rule that the bridge never creates threads. The option
needs `bridge-threads`, since replies typed in the new thread must reach IRC. It
also needs `thread-irc-replies`, which turns IRC reply threading on at all.

### The thread root

A thread started from a message hangs off that message. The message was already
bridged from the parent channel, so IRC clients thread the replies under it. A
standalone thread has no such message. Its root is the first message bridged
from it, which goes out as a normal message.

Telling the two apart takes one `retrieveParentMessage()` call per thread. The
call is asynchronous, and the thread's messages wait for it in order. The
result is cached in a bounded LRU.

### The type 18 ID quirk

When someone creates a standalone thread, Discord posts a `THREAD_CREATED`
notice (message type 18) in the parent channel. Verified live, that notice has
the same ID as the thread. So `retrieveParentMessage()` returns the notice for a
standalone thread, and its type is how the fork tells the thread kinds apart. A
404 also means there is no starter message, for example after it was deleted.

The notice's content is the thread name, which upstream bridges as if the
thread's creator had said it. With `bridge-threads` on, the fork drops it, as the
thread's messages now show the thread. With it off, the notice is bridged as
upstream does, since it is then IRC's only sign of the thread.
`THREAD_STARTER_MESSAGE` only repeats another message and is always dropped.

### Both reply tags are sent

Replies carry `+draft/reply` and `+reply` with the same msgid. Older clients and
servers only know the draft name. Each tag is checked against `CLIENTTAGDENY`
by its name without the `+`. The live network the fork targets allows both.

### Webhooks can't reply, but can post into threads

Discord webhooks can't send inline replies, which is why PR #95 has the bot send
IRC replies. They can post into a thread of their own channel. So IRC replies
that go into a thread use the channel's webhook, through
`WebhookClient.onThread(threadId)`, and keep the IRC nick as the username.

The thread client shares the parent client's thread pool and queue. Closing it
closes the parent, so the fork never closes it. Without a webhook the bot posts
into the thread instead.

### Recognising the bridge's own messages

Upstream's `isThisBot` looked up the webhook for the source channel. Inside a
thread the source is the thread's ID, which has no webhook entry. The bridge's
own webhook posts into threads would have echoed back to IRC. Thread messages
are now bridged as their parent channel, and `isThisBot` matches every
configured webhook ID. A webhook can only post in its own channel and that
channel's threads, so matching all of them is safe.

### Resending only after a refusal

When Discord refuses a post, the fork sends it another way, for example to the
channel instead of a thread. A refusal is one of Discord's own error codes on
the JDA side, from 10000 up, which includes 160005 for a locked thread. On the
webhook side it is a 4xx status. Server errors and failed connections are only
logged as warnings. Discord may have posted the message anyway, and a resend
would post it twice.

## Known limitations

- Only public threads of text channels are bridged. Private threads stay
  private. Threads in forum and announcement channels are not bridged.
- The fork keeps everything in memory. After a restart, a thread's next message
  becomes a new root, and replies to older messages go out untagged.
- A reply is only tagged once the server has echoed its parent. A thread
  message sent right after its root can go out untagged.
- Without `irc-replies-start-threads`, IRC replies to the starter of an archived
  thread stay in the channel, unless the bridge saw the thread since it started.
  Archived threads leave the cache, and the fork doesn't look them up.
- The first message of each new thread waits for one request to Discord. Later
  messages from that thread wait behind it, so they can reach IRC after messages
  from other channels that arrived later.
- A bridge command typed in a thread is answered in the parent channel, since
  the thread is bridged as its parent.
- After a failed thread start, replies to that message go to the channel for
  10 minutes before the bridge tries again.
- On IRC, a standalone thread's root looks like any other message. Nothing says
  a thread started there.
- Without IRCv3 `message-tags` and `echo-message`, thread messages reach IRC as
  plain messages with no threading.
- Message edits and deletions are not bridged, as upstream.

## Rebasing on upstream

The remotes are `origin` for `r3cursive/Dis4IRC` and `upstream` for
`zachbr/Dis4IRC`. Add the upstream remote once:

```sh
git remote add upstream https://github.com/zachbr/Dis4IRC.git
```

Then rebase and check:

```sh
git fetch upstream
git rebase upstream/master threads
./gradlew clean build
```

New source files need the MIT header, which `./gradlew updateLicenses` adds.
Build the jar from clean, since an incremental build can keep an old git hash in
the version string.

Run the live end-to-end suite from `r3cursive/dis4irc-deploy` against the built jar:

```sh
python3 -I scripts/e2e/bridge_e2e.py --bridge dis4irc --suite all --jar build/libs/Dis4IRC-<version>.jar
```

Update the upstream base SHA at the top of this file after each rebase.

### When upstream lands something, drop the matching commits

- PR #95 merged unchanged: drop commits 1 to 4, then offer 5 to 13 upstream.
- PR #95 merged with changes: drop commits 1 to 4, then resolve 5 to 13, 17, 18
  and 19 against the merged code. Drop any audit fix the merge already covers.
- Upstream sends `+reply` itself: drop commit 17.
- Upstream on JDA 6.7.0 or newer: drop commit 14.
- Upstream adds a newline option to the shortener: drop commit 15.
- Upstream adds a lighter anti-ping: drop commit 16.
- Upstream drops thread notices or fixes `isThisBot` for threads: remove those
  parts from commit 18.
- Upstream adds its own thread support (#58): compare it with commits 18 and 19.
  Drop them if upstream covers reply chains both ways.
