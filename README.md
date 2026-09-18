<a id="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![LGPL-3.0 License][license-shield]][license-url]



<br />
<div align="center">
<h3 align="center">WynnVoice</h3>

  <p align="center">
    Proximity voice chat for Wynncraft, powered by Simple Voice Chat.
    <br />
    <br />
    <a href="https://github.com/DevScyu/WynnVoiceChat/issues/new?labels=bug">Report Bug</a>
    &middot;
    <a href="https://github.com/DevScyu/WynnVoiceChat/issues/new?labels=enhancement">Request Feature</a>
  </p>
</div>



<details>
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#built-with">Built With</a></li>
      </ul>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
        <li><a href="#running-a-relay">Running a relay</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>



## About The Project

Wynncraft runs no voice server, so installing Simple Voice Chat alone does nothing there.
WynnVoice is a small Fabric client mod plus a relay server: the mod points your existing
Simple Voice Chat installation at the relay, and the relay routes audio between players on
the same Wynncraft world.

* **Opt-in.** Voice is off until you accept the consent screen. You only hear players who
  opted in too.
* **Proximity and relationship based.** You hear players on your world within range, and only
  those who chose to be heard by you: party, friends & guild, or everyone.
* **Moderated.** Block anyone, report with audio evidence, and bans are enforced by the relay.
* **No accounts.** The relay verifies you through Mojang's session server, the same way a
  Minecraft server does.

WynnVoice does not replace Simple Voice Chat; you install both.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



### Built With

* [![Fabric][fabric-badge]][fabric-url]
* [![Simple Voice Chat][svc-badge]][svc-url]
* [![Java][java-badge]][java-url]
* [![Kotlin][kotlin-badge]][kotlin-url]
* [![Netty][netty-badge]][netty-url]

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Getting Started

The repository is one Gradle build with three modules:

| Module      | Language | What                                                                      |
|-------------|----------|---------------------------------------------------------------------------|
| `protocol/` | Java 21  | Packets and framing shared by mod and relay (Netty `ByteBuf` only)        |
| `server/`   | Kotlin   | The relay: TCP control channel, Mojang session auth, audio relay, moderation |
| `mod/`      | Java 21  | The Fabric 1.21.11 client mod                                             |

### Prerequisites

To play:

* Minecraft 1.21.11 with Fabric Loader 0.18.4 or newer
* [Fabric API][fabric-api-url]
* [Simple Voice Chat][svc-url] 2.6 or newer

To build:

* JDK 21 (a newer JDK works as long as Gradle can find a JDK 21 toolchain)

### Installation

Three mods go into your `mods` folder:

1. [Fabric API][fabric-api-url]
2. [Simple Voice Chat][svc-url]
3. The WynnVoice jar from [Modrinth][modrinth-url] or the
   [releases page](https://github.com/DevScyu/WynnVoiceChat/releases)

Start the game once, then point `relayHost` in `config/wynnvoice.json` at the relay you use
(see [Usage](#usage); the default is `localhost`). Join Wynncraft and accept the consent notice.

To build the mod yourself instead:

```sh
git clone https://github.com/DevScyu/WynnVoiceChat.git
cd WynnVoiceChat
./gradlew build
```

The mod is `mod/build/libs/mod-<version>.jar` (the protocol classes are nested inside it), the
relay is `server/build/libs/server-<version>-all.jar`.

### Running a relay

Players do not need to run one; this is for whoever operates the relay the mod points at.
The relay is a single self-contained jar (JRE 21 or newer):

```sh
VOICE_ENABLED=true VOICE_HOST=voice.example.com \
  java -jar server/build/libs/server-<version>-all.jar
```

or, during development, `./gradlew :server:run`.

The `Dockerfile` builds the same jar and runs it on a JRE 21 image; `/data` holds the SQLite
database and report audio:

```sh
docker build -t wynnvoice-relay .
docker run -d --name wynnvoice-relay \
  -p 9100:9100 -p 24454:24454/udp -p 127.0.0.1:9101:9101 \
  -v wynnvoice-data:/data \
  -e VOICE_ENABLED=true -e VOICE_HOST=voice.example.com \
  wynnvoice-relay
```

Open TCP `CONTROL_PORT` and UDP `VOICE_PORT` to the internet. `HTTP_PORT` only ever serves
`POST /discord` and must sit behind a reverse proxy that terminates HTTPS and forwards nothing
but that path; leave `METRICS_PORT` on loopback. Every setting is an environment variable:

| Variable                            | Default   | Meaning                                            |
|-------------------------------------|-----------|----------------------------------------------------|
| `CONTROL_HOST` / `CONTROL_PORT`     | `0.0.0.0` / `9100` | TCP control channel the mod connects to    |
| `RATE_LIMIT_MAX_CONCURRENT_PER_IP`  | `5`       | Open connections allowed per IP                     |
| `RATE_LIMIT_MAX_PER_MINUTE_PER_IP`  | `15`      | New connections per minute per IP                   |
| `RATE_LIMIT_MAX_HANDSHAKING`        | `500`     | Unauthenticated connections allowed at once         |
| `VOICE_ENABLED`                     | `false`   | Must be `true`; otherwise every client is refused with `DISABLED` |
| `VOICE_ALLOWED_UUIDS`               | —         | Comma-separated player UUIDs; when set, anyone else is refused with `NOT_ALLOWED` |
| `VOICE_HOST`                        | required when enabled | Public host or IP Simple Voice Chat clients send audio to |
| `VOICE_PORT` / `VOICE_BIND`         | `24454` / `0.0.0.0` | UDP port for audio and the address it binds to |
| `VOICE_RANGE`                       | `32`      | Proximity range in blocks; whispering halves it     |
| `VOICE_EVERYONE_ENABLED`            | `false`   | Allow the everyone audience; otherwise tiers are capped at friends & guild |
| `VOICE_RING_CAP_MB`                 | `512`     | Total memory kept for report audio evidence         |
| `VOICE_REPORT_DIR`                  | `voice-reports` | Directory report audio is written to, one folder per report id |
| `DB_PATH`                           | `voice.db` | SQLite file holding blocks, bans and reports; created on startup |
| `HTTP_PORT`                         | `9101`    | HTTP port serving only `POST /discord`, the Discord interactions endpoint |
| `METRICS_BIND` / `METRICS_PORT`     | `127.0.0.1` / `9102` | Prometheus `GET /metrics`; `0` disables. No token, so keep it on loopback (or a private interface) and never behind the public proxy |
| `DISCORD_APPLICATION_ID` / `DISCORD_GUILD_ID` | — | Application id and the server the `/voice` commands are registered in |
| `DISCORD_BOT_TOKEN`                 | —         | Bot token used to post reports and register commands |
| `DISCORD_PUBLIC_KEY`                | —         | Application public key every interaction is verified against |
| `DISCORD_MOD_ROLE_ID`               | —         | Role allowed to use the buttons and `/voice` commands, re-checked on every interaction |
| `DISCORD_REPORT_CHANNEL_ID`         | —         | Channel reports are posted to |

Discord moderation is off unless all six `DISCORD_*` variables are set; reports are still stored
in SQLite and under `VOICE_REPORT_DIR` either way.

#### Reverse proxy for `/discord`

Discord only calls HTTPS endpoints, and the relay speaks plain HTTP on `HTTP_PORT`, so put a
proxy in front of it that forwards exactly one path. With nginx:

```nginx
server {
    listen 443 ssl;
    server_name voice.example.com;
    # ssl_certificate / ssl_certificate_key as usual

    location = /discord {
        proxy_pass http://127.0.0.1:9101;
        proxy_set_header Host $host;
    }
}
```

Do not proxy `/metrics` or anything else; the relay authenticates `/discord` requests by their
Ed25519 signature and nothing else needs to be reachable over HTTP.

#### Metrics

`/metrics` exposes sessions, auth, control and UDP traffic, routing outcomes, upstream APIs,
Discord, moderation, SQLite timings and the JVM in Prometheus text format, all prefixed `voice_`.
No label ever carries a player identity; world names are whitelisted to `WC<n>` and anything else
is `other`. A Prometheus on the same host scrapes it with:

```yaml
scrape_configs:
  - job_name: wynnvoice
    scrape_interval: 15s
    static_configs:
      - targets: ["127.0.0.1:9102"]
```

Unique-player questions (daily/weekly actives, retention) come from the `voice_sessions` table
in `DB_PATH` instead, e.g. with Grafana's SQLite datasource:
`SELECT date(started_at / 1000, 'unixepoch') AS day, count(DISTINCT uuid) FROM voice_sessions GROUP BY 1`.
Rows older than 90 days are pruned automatically.

#### Discord application setup

1. Create an application at the [Discord developer portal](https://discord.com/developers/applications),
   copy its **Application ID** and **Public Key** (General Information) into
   `DISCORD_APPLICATION_ID` and `DISCORD_PUBLIC_KEY`.
2. Under **Bot**, reset the token and put it in `DISCORD_BOT_TOKEN`.
3. Invite the bot with the `bot` and `applications.commands` scopes (OAuth2 → URL Generator) and
   the *Send Messages* and *Attach Files* permissions.
4. In your server enable Developer Mode, copy the server id into `DISCORD_GUILD_ID`, the
   moderator role id into `DISCORD_MOD_ROLE_ID` and the private report channel id into
   `DISCORD_REPORT_CHANNEL_ID`; give the bot access to that channel.
5. With the reverse proxy above in place, set the application's **Interactions Endpoint URL**
   (General Information) to `https://<your host>/discord`. Discord verifies the URL by sending a
   signed ping, so the relay must already be running with the variables above.
6. The `/voice` commands are registered on every startup with default permissions set to
   administrators only; grant the moderator role under Server Settings → Integrations → your
   application → `/voice`. The relay refuses anyone without `DISCORD_MOD_ROLE_ID` regardless.

Each report then appears in the channel as an embed with both audio files and `Ban 7d`,
`Ban 30d`, `Ban permanent` and `Dismiss` buttons; `/voice ban <player> [days] [reason]`,
`/voice unban <player>`, `/voice bans` and `/voice blocks <player>` cover the rest. A ban drops
the player's live session within a minute and refuses their next connection with `BANNED`.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Usage

The mod only activates on `wynncraft.com`. It reads `config/wynnvoice.json` from your
Minecraft directory, created on first launch:

```json
{
  "relayHost": "localhost",
  "relayPort": 9100,
  "enabled": true,
  "tier": "PARTY",
  "consentVersion": 0,
  "everyoneWarningAccepted": false
}
```

Nothing happens until you accept the consent notice, which appears on your first world join
when Simple Voice Chat is installed. Accepting stores `consentVersion`; cancelling (or closing
the notice) sets `enabled` to `false`. Every change made in game is written to the file
immediately.

Joining a world connects to the relay and authenticates through Mojang. If the relay refuses
the connection you get one chat line explaining why; leaving the world closes the connection.
Once connected, one grey line says how many players are on voice on your world and how many of
them can hear you (nothing is printed when nobody is).

| Command                                                   | Effect                                                    |
|-----------------------------------------------------------|-----------------------------------------------------------|
| `/wynnvoice who`                                          | List voice users on your world by party, friends, guild and others, marking who is muted or cannot hear you |
| `/wynnvoice blocks`                                       | List the players you have blocked                         |
| `/wynnvoice tier <party\|friends_and_guild\|everyone>`    | Set the audience; takes effect at once, also mid-session  |
| `/wynnvoice block <player>`                               | Never hear or be heard by that player, on any audience    |
| `/wynnvoice unblock <player>`                             | Lift a block                                              |
| `/wynnvoice report <player> [reason]`                     | Report someone you heard in the last two minutes          |
| `/wynnvoice enable`                                       | Turn voice on (shows the consent notice if still pending) |
| `/wynnvoice disable`                                      | Turn voice off and disconnect                             |

`tier` is the audience that may hear you: `PARTY`, `FRIENDS_AND_GUILD` or `EVERYONE`. Two
players on `EVERYONE`, on the same world and housing plot and within range, hear each other
through Simple Voice Chat; Simple Voice Chat's own disable toggle stops delivery. On
`FRIENDS_AND_GUILD` you additionally need to be mutual friends or in the same guild: the mod
reads your friend list from `/friend list`, and the relay looks your guild up on the Wynncraft
public API (cached ten minutes), so a client can never claim a guild it is not in. Choosing
`EVERYONE` shows the rules once; until you accept them (`everyoneWarningAccepted`) the relay is
told `PARTY`, and cancelling reverts the setting to `PARTY`. The relay may additionally cap the
audience at friends & guild (`VOICE_EVERYONE_ENABLED`).

Blocks are symmetric and permanent until lifted; the name is looked up among players on voice
first, then on Mojang, so you can block someone who is offline. A report needs both of you on
voice and the other player audible to you within the last two minutes; the relay then stores the
last two minutes of their speech and yours as evidence, and answers in chat with the report id.
Reports are limited to one per minute and ten per day.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Roadmap

- [x] Shared protocol and relay authentication
- [x] Mod connects and authenticates on world join
- [x] Proximity voice on the same world and housing instance
- [x] Party tier: hear your party anywhere
- [x] Friends & guild tier
- [x] Consent screen, `/wynnvoice` command and config
- [x] Block and report with audio evidence
- [x] Discord-driven moderation
- [x] Docker image for the relay
- [ ] Modrinth release

See the [open issues][issues-url] for a full list of proposed features and known issues.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Contributing

Contributions are welcome. If you have a suggestion, fork the repo and open a pull request,
or open an issue with the tag "enhancement". See [CONTRIBUTING.md](CONTRIBUTING.md) for the
full guide and the [Code of Conduct](.github/CODE_OF_CONDUCT.md).

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Make sure `./gradlew build` passes
4. Commit with a [Conventional Commits](https://www.conventionalcommits.org) subject
   (`git commit -m 'feat: add amazing feature'`)
5. Push to the branch (`git push origin feature/amazing-feature`)
6. Open a pull request

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## License

Distributed under the GNU Lesser General Public License v3.0 only. See `LICENSE` for more
information.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Contact

Discord: [https://discord.gg/QPCwpuA2b](https://discord.gg/QPCwpuA2b)

Project link: [https://github.com/DevScyu/WynnVoiceChat](https://github.com/DevScyu/WynnVoiceChat)

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Acknowledgments

* [Simple Voice Chat][svc-url], the audio engine this project builds on
* [Contributor Covenant](https://www.contributor-covenant.org)
* [Best-README-Template](https://github.com/othneildrew/Best-README-Template)

<p align="right">(<a href="#readme-top">back to top</a>)</p>



[contributors-shield]: https://img.shields.io/github/contributors/DevScyu/WynnVoiceChat.svg?style=for-the-badge
[contributors-url]: https://github.com/DevScyu/WynnVoiceChat/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/DevScyu/WynnVoiceChat.svg?style=for-the-badge
[forks-url]: https://github.com/DevScyu/WynnVoiceChat/network/members
[stars-shield]: https://img.shields.io/github/stars/DevScyu/WynnVoiceChat.svg?style=for-the-badge
[stars-url]: https://github.com/DevScyu/WynnVoiceChat/stargazers
[issues-shield]: https://img.shields.io/github/issues/DevScyu/WynnVoiceChat.svg?style=for-the-badge
[issues-url]: https://github.com/DevScyu/WynnVoiceChat/issues
[license-shield]: https://img.shields.io/github/license/DevScyu/WynnVoiceChat.svg?style=for-the-badge
[license-url]: https://github.com/DevScyu/WynnVoiceChat/blob/main/LICENSE
[fabric-badge]: https://img.shields.io/badge/Fabric-1.21.11-DBD0B4?style=for-the-badge
[fabric-url]: https://fabricmc.net/
[fabric-api-url]: https://modrinth.com/mod/fabric-api
[modrinth-url]: https://modrinth.com/mod/wynnvoice
[svc-badge]: https://img.shields.io/badge/Simple%20Voice%20Chat-2.6-1E88E5?style=for-the-badge
[svc-url]: https://modrinth.com/mod/simple-voice-chat
[java-badge]: https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white
[java-url]: https://openjdk.org/
[kotlin-badge]: https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white
[kotlin-url]: https://kotlinlang.org/
[netty-badge]: https://img.shields.io/badge/Netty-4.2-000000?style=for-the-badge
[netty-url]: https://netty.io/
