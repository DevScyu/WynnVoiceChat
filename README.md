# WynnVoice

Proximity voice chat for Wynncraft. A Fabric client mod makes your existing
[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) installation talk to a
small relay server instead of to Wynncraft, which runs no voice server of its own.

Voice is off until you opt in. You only hear other players who opted in, on your world,
within range, and who chose to be heard by you (party, friends & guild, or everyone).
Blocks, reports with audio evidence and Discord-driven moderation are built in.

## Modules

| Module | Language | What |
|---|---|---|
| `protocol/` | Java 21 | Packets and framing shared by mod and server (Netty `ByteBuf` only) |
| `server/` | Kotlin | Relay: TCP control channel, Mojang session auth, SVC UDP relay, moderation |
| `mod/` | Java 21 | Fabric 1.21.11 client mod |

## Building

Requires a JDK 21 (or newer, with a JDK 21 available for the toolchain).

```
./gradlew build
```

- `mod/build/libs/` — the mod jar (bundles `protocol`)
- `server/build/libs/` — the relay jar; run with `./gradlew :server:run`

## Relay configuration

| Variable | Default |
|---|---|
| `CONTROL_HOST` / `CONTROL_PORT` | `0.0.0.0` / `9100` |
| `RATE_LIMIT_MAX_CONCURRENT_PER_IP` | `5` |
| `RATE_LIMIT_MAX_PER_MINUTE_PER_IP` | `15` |
| `RATE_LIMIT_MAX_HANDSHAKING` | `500` |

## License

LGPL-3.0-only.
