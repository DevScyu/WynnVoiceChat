# Contributing to WynnVoiceChat

WynnVoiceChat is a solo-maintained, open-source voice chat for Wynncraft. Contributions of any
size are welcome: bug reports, feature ideas, docs and code.

## Prerequisites

- JDK 21
- Git

## Getting started

```sh
git clone https://github.com/DevScyu/WynnVoiceChat.git
cd WynnVoiceChat
./gradlew build
```

`./gradlew build` compiles all three modules and runs every test. The mod jar lands in
`mod/build/libs/`; the relay runs with `./gradlew :server:run`.

## Branch naming

| Prefix   | Use for                    |
|----------|----------------------------|
| `feat/`  | New features               |
| `fix/`   | Bug fixes                  |
| `chore/` | Maintenance, deps, tooling |
| `docs/`  | Documentation only         |

## Commits

Use a [Conventional Commits](https://www.conventionalcommits.org) subject, imperative mood,
no trailing period: `feat: add party voice group`, `fix(server): close idle handshakes`.

## Making a pull request

1. Fork the repo and branch from `main` using the prefixes above
2. Keep the change focused; one concern per pull request
3. Add or update tests for behaviour you change, and make sure `./gradlew build` passes
4. Open a draft pull request, then mark it ready when it is complete

## Ground rules

Everything must stay within [Wynncraft's rules](https://wynncraft.com/rules); anything that
gives an in-game advantage or automates play will not be merged. Be kind: this project follows
the [Contributor Covenant](.github/CODE_OF_CONDUCT.md).

## Questions?

Ask on the [WynnVoiceChat Discord](https://discord.gg/QPCwpuA2b).
