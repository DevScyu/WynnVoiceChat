# Running the relay

Operator notes for the WynnVoiceChat relay. Players never need any of this.

The relay is a single self-contained jar (JRE 21 or newer):

```sh
VOICE_ENABLED=true VOICE_HOST=voice.example.com \
  java -jar server/build/libs/server-<version>-all.jar
```

or, during development, `./gradlew :server:run`.

The `Dockerfile` builds the same jar and runs it on a JRE 21 image; `/data` holds the SQLite
database and report audio:

```sh
docker build -t wynnvoicechat-relay .
docker run -d --name wynnvoicechat-relay \
  -p 9100:9100 -p 24454:24454/udp -p 127.0.0.1:9101:9101 \
  -v wynnvoicechat-data:/data \
  -e VOICE_ENABLED=true -e VOICE_HOST=voice.example.com \
  --log-opt max-size=1m --log-opt max-file=14 \
  wynnvoicechat-relay
```

The log options cap the container log at 14 files of 1 MB, about a day each on a busy relay, so
logs last roughly the two weeks the [privacy notice](https://wynnvoicechat.com/privacy) states.
Three more things the notice relies on:

* **Log level stays `info`**, the shipped default. Do not run production with
  `-Dorg.slf4j.simpleLogger.defaultLogLevel=debug`: connection-level `debug` lines include client
  IP addresses, and nothing at `info` or above does (checked by grepping the relay sources for
  address-carrying log calls).
* **Pterodactyl** does not use the options above: Wings sets the container log driver itself from
  `docker.log_config` in its `config.yml` (default `local`, 5 MB, one file), so that is where the
  cap lives. The panel console only shows what the container currently holds; it is not a
  persistent log.
* **The reverse proxy** in front of `/discord` (below) writes its own access log with client IPs.
  Give it the same cap, e.g. `logrotate` with `rotate 14` and `daily` for nginx.

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
| `TERMS_VERSION`                     | `1`       | Terms of use version announced to every client; raise it and everyone re-accepts on their next connection |
| `DB_PATH`                           | `voice.db` | SQLite file holding blocks, bans and reports; created on startup |
| `HTTP_PORT`                         | `9101`    | HTTP port serving only `POST /discord`, the Discord interactions endpoint |
| `METRICS_BIND` / `METRICS_PORT`     | `127.0.0.1` / `9102` | Prometheus `GET /metrics`; `0` disables. No token, so keep it on loopback (or a private interface) and never behind the public proxy |
| `DISCORD_APPLICATION_ID` / `DISCORD_GUILD_ID` | — | Application id and the server the `/voice` commands are registered in |
| `DISCORD_MOD_LOG_CHANNEL_ID`        | —         | Optional: channel that gets one line per ban and unban |
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
  - job_name: wynnvoicechat
    scrape_interval: 15s
    static_configs:
      - targets: ["127.0.0.1:9102"]
```

Unique-player questions (daily/weekly actives, retention) come from the `voice_sessions` table
in `DB_PATH` instead, e.g. with Grafana's SQLite datasource:
`SELECT date(started_at / 1000, 'unixepoch') AS day, count(DISTINCT uuid) FROM voice_sessions GROUP BY 1`.
Rows older than 90 days are pruned automatically, as the privacy notice promises; the same
once-a-minute pass deletes a report's audio clips 30 days after the report, a temporary ban's row
12 months after it expired (permanent bans stay) and a report's row 12 months after it was filed.

#### Release checklist

- When the terms change, bump `TERMS_VERSION` on the relay and `version`/`effective` in the
  website's terms page together; the mod shows "The terms have changed (version N)" and holds voice
  off until the player accepts again.

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
the player's live session within a minute and refuses their next connection with `BANNED`; both
tell the player the ban reason, as the terms promise.
