# Modrinth listing

Body text for the Modrinth project page. Fields outside the body: project type *mod*,
loader *Fabric*, game version *1.21.11*, environment *client only*, license *LGPL-3.0-only*,
categories *social* and *utility*, source and issues links to the GitHub repository, Discord
link from the README contact section.

**Icon: still to be produced** (Modrinth wants a square PNG, 512×512 or larger). Nothing in the
repository is the icon yet.

---

## Proximity voice chat for Wynncraft

Wynncraft runs no voice server, so Simple Voice Chat alone does nothing there. WynnVoiceChat is a
small client mod that points your existing Simple Voice Chat installation at a community relay,
and the relay routes audio between players on the same Wynncraft world. Install it, join
Wynncraft, accept the notice, talk.

### Requirements

- Minecraft 1.21.11 with Fabric Loader 0.18.4 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) 2.6 or newer

WynnVoiceChat does not replace Simple Voice Chat; you install both. Simple Voice Chat's own
volume, push-to-talk and mute settings all apply. WynnVoiceChat is a separate project: please
report problems here or on our Discord, not to Simple Voice Chat support.

### How it works

- **Opt-in.** Voice is off until you read and accept the consent notice on your first world
  join. You only ever hear players who opted in too.
- **Proximity.** You hear players on your world within 32 blocks (whispering halves it),
  matching the housing plot you are on.
- **Audience.** You choose who may hear you with `/wynnvoicechat tier` (`/wvc` works everywhere as a short alias): your **party** (default),
  your **friends and guild**, or **everyone** nearby. Party members hear each other anywhere.
- **Moderation.** `/wynnvoicechat block <player>` silences someone permanently in both directions.
  `/wynnvoicechat report <player> [reason]` reports someone you heard in the last two minutes.
  `/wynnvoicechat enable` and `/wynnvoicechat disable` turn the whole thing on and off.
- **No accounts.** The relay verifies you through Mojang's session server, the same way a
  Minecraft server does when you join it.

The mod only activates on `wynncraft.com`; on any other server it does nothing.

### Privacy

- **Identity.** On connect the relay confirms your Minecraft account with Mojang's session
  server. There is no registration, password or e-mail.
- **Position.** While voice is on, your in-game position and world are sent to the relay so
  audio can be routed to nearby players. They are not stored.
- **Friends and guild.** The mod reads your friend list from `/friend list` and sends it to the
  relay; the relay looks your guild up on the public Wynncraft API and player UUIDs up on Mojang.
- **Audio.** Your last two minutes of speech are held in the relay's memory only. They are
  written to disk solely when another player reports you, and then the report — including that
  audio and the reporter's own last two minutes — is sent to the WynnVoiceChat maintainers' private
  Discord channel for review. Reports go to the WynnVoiceChat maintainers, not to Wynncraft staff.
- **Bans** are for the WynnVoiceChat relay as a whole and are enforced by the relay, not by the mod.

### Source

WynnVoiceChat is free software under the LGPL-3.0-only license. The relay is open source too and
anyone can run their own; see the repository README.
