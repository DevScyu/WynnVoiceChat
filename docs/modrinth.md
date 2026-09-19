# Modrinth listing

Body text for the Modrinth project page. Fields outside the body: project type *mod*, loader *Fabric*, game version *1.21.11*, environment *client only*, license *LGPL-3.0-only*, categories *social* and *utility*, source and issues links to the GitHub repository, Discord link from the README contact section, website https://wynnvoicechat.com.

Icon: `.github/brand/icon-512.png`. Gallery images from `../docs/assets/modrinth/` (banner featured); their CDN URLs are baked into the body below. Summary field: *Voice chat for Wynncraft parties, guilds and friends.* Slug: `wynnvoicechat`.

---

<p align="center"><img src="https://cdn.modrinth.com/data/EqqrBoe4/images/0d8542e9098d68acd76cf8fcfc257e17d6a188c8.png" alt="WynnVoiceChat: voice chat for Wynncraft parties, guilds and friends" width="680"></p>

Wynncraft runs no voice server, so Simple Voice Chat alone does nothing there. WynnVoiceChat is a small client mod that points your existing Simple Voice Chat installation at a community relay, and the relay routes audio between players on the same Wynncraft world. Install it, join Wynncraft, accept the notice, talk.

### Requirements

- Minecraft 1.21.11 with Fabric Loader 0.18.4 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)
- [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) 2.6 or newer

WynnVoiceChat does not replace Simple Voice Chat; you install both. Simple Voice Chat's own volume, push-to-talk and mute settings all apply. WynnVoiceChat is a separate project: please report problems here or on our Discord, not to Simple Voice Chat support.

### How it works

- **Opt-in.** Voice is off until you read and accept the notice on your first world join. You only ever hear players who opted in too.
- **Proximity.** You hear players on your world within 32 blocks (whispering halves it), matching the housing plot you are on.
- **Audience.** Choose who may hear you with `/wynnvoicechat tier` (`/wvc` is a short alias): your **party** (default), your **friends and guild**, or **everyone** nearby. Party members hear each other anywhere.
- **Who is on voice.** A speaker icon on the nameplate, tinted by relation: green party, blue friend, yellow guild, white stranger.
- **Guild channel.** `/wvc guild on` lets you hear and be heard by guild members anywhere, in a read-only group in Simple Voice Chat.
- **Moderation.** `/wvc block <player>` silences someone in both directions. `/wvc report <player> [reason]` reports someone you heard in the last two minutes. `/wvc disable` turns everything off.
- **No accounts.** The relay verifies you through Mojang's session server, the same way a Minecraft server does when you join it.

The mod only activates on `wynncraft.com`; on any other server it does nothing.

<p align="center"><img src="https://cdn.modrinth.com/data/EqqrBoe4/images/4e18a5c073b3e7b890fb9ebefe57c5b89825ebd3.png" alt="A player's nameplate with a blue speaker icon showing they are a friend on voice" width="480"></p>

### Calls

`/wvc call <player>` rings a mutual friend who is on voice, anywhere in the game. They see who is calling with accept and decline keys, you both get a small pill with the call timer, and `H` hangs up. `/wvc dnd on` refuses calls while you are busy.

<p align="center"><img src="https://cdn.modrinth.com/data/EqqrBoe4/images/087f1da3efa2303abad1407a4cc1b78eae0b3f6e.png" alt="The call flow: incoming panel, ringing, in a call, call ended, declined" width="280"></p>

### Privacy and safety

<p align="center"><img src="https://cdn.modrinth.com/data/EqqrBoe4/images/0775c1f08864df3fb26e7c69a61ca2f96264a73a.png" alt="The in-game notice you accept before voice turns on: it states the two-minute speech buffer and links the terms, rules and privacy notice" width="640"></p>

- **Identity.** On connect the relay confirms your Minecraft account with Mojang's session server. There is no registration, password or e-mail.
- **Position.** While voice is on, your position and world go to the relay so audio reaches nearby players. They are not stored.
- **Friends and guild.** The mod reads your friend list from `/friend list` and sends it to the relay; the relay looks your guild up on the public Wynncraft API.
- **Audio.** Your last two minutes of speech are held in the relay's memory only. They are written out solely when a report is made, and then the report, including that audio and the reporter's own last two minutes, goes to volunteer moderators in a private Discord channel. Clips are deleted after 30 days.
- **Reports go to us, not to Wynncraft.** Wynncraft treats voice on this service like a private call. Report in game, or e-mail safety@wynnvoicechat.com if you are not a player.
- **Bans** are for the WynnVoiceChat relay as a whole and are enforced by the relay.

Full details: [Privacy notice](https://wynnvoicechat.com/privacy), [Terms of use](https://wynnvoicechat.com/terms), [Community rules](https://wynnvoicechat.com/rules). You must be 13 or older to use voice.

### Source

WynnVoiceChat is free software under the LGPL-3.0-only licence; the source is in the repository linked above.

### Hosting

<p align="center"><a href="https://wynnvoicechat.com/lilypad"><img src="https://wynnvoicechat.com/assets/lilypad-logo.png" alt="Lilypad" width="240"></a></p>

The public relay runs on a server sponsored by [Lilypad](https://wynnvoicechat.com/lilypad) (affiliate link).

---

WynnVoiceChat is an independent, community-made mod. It is not affiliated with, endorsed by, or part of Wynncraft, Wynntils, or Simple Voice Chat.
