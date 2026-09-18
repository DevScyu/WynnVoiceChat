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

1. Clone the repo
   ```sh
   git clone https://github.com/DevScyu/WynnVoiceChat.git
   ```
2. Build everything
   ```sh
   ./gradlew build
   ```
3. Drop `mod/build/libs/mod-<version>.jar` into your `mods` folder next to Fabric API and
   Simple Voice Chat.

### Running a relay

```sh
./gradlew :server:run
```

| Variable                            | Default   | Meaning                                            |
|-------------------------------------|-----------|----------------------------------------------------|
| `CONTROL_HOST` / `CONTROL_PORT`     | `0.0.0.0` / `9100` | TCP control channel the mod connects to    |
| `RATE_LIMIT_MAX_CONCURRENT_PER_IP`  | `5`       | Open connections allowed per IP                     |
| `RATE_LIMIT_MAX_PER_MINUTE_PER_IP`  | `15`      | New connections per minute per IP                   |
| `RATE_LIMIT_MAX_HANDSHAKING`        | `500`     | Unauthenticated connections allowed at once         |
| `VOICE_ENABLED`                     | `false`   | Must be `true`; otherwise every client is refused with `DISABLED` |
| `VOICE_ALLOWED_UUIDS`               | —         | Comma-separated player UUIDs; when set, anyone else is refused with `NOT_ALLOWED` |
| `VOICE_HOST`                        | —         | Public host Simple Voice Chat clients send audio to |
| `VOICE_PORT` / `VOICE_BIND`         | `24454` / `0.0.0.0` | UDP port for audio and the address it binds to |
| `VOICE_RANGE`                       | `32`      | Proximity range in blocks; whispering halves it     |
| `VOICE_EVERYONE_ENABLED`            | `false`   | Allow the everyone audience; otherwise tiers are capped at friends & guild |
| `VOICE_RING_CAP_MB`                 | `512`     | Total memory kept for report audio evidence         |

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Usage

The mod only activates on `wynncraft.com`. It reads `config/wynnvoice.json` from your
Minecraft directory, created on first launch:

```json
{
  "relayHost": "localhost",
  "relayPort": 9100,
  "tier": "PARTY"
}
```

Joining a world connects to the relay and authenticates through Mojang. If the relay refuses
the connection you get one chat line explaining why; leaving the world closes the connection.

`tier` is the audience that may hear you: `PARTY`, `FRIENDS_AND_GUILD` or `EVERYONE`. Two
players on `EVERYONE`, on the same world and housing plot and within range, hear each other
through Simple Voice Chat; Simple Voice Chat's own disable toggle stops delivery.

<p align="right">(<a href="#readme-top">back to top</a>)</p>



## Roadmap

- [x] Shared protocol and relay authentication
- [x] Mod connects and authenticates on world join
- [x] Proximity voice on the same world and housing instance
- [ ] Party tier: hear your party anywhere
- [ ] Friends & guild tier
- [ ] Consent screen, `/wynnvoice` command and config
- [ ] Block and report with audio evidence
- [ ] Discord-driven moderation
- [ ] Modrinth release and Docker image for the relay

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
[svc-badge]: https://img.shields.io/badge/Simple%20Voice%20Chat-2.6-1E88E5?style=for-the-badge
[svc-url]: https://modrinth.com/mod/simple-voice-chat
[java-badge]: https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white
[java-url]: https://openjdk.org/
[kotlin-badge]: https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white
[kotlin-url]: https://kotlinlang.org/
[netty-badge]: https://img.shields.io/badge/Netty-4.2-000000?style=for-the-badge
[netty-url]: https://netty.io/
