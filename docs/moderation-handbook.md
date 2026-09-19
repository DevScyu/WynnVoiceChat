# Moderator handbook

For volunteer moderators on the WynnVoiceChat Discord. The public rules are at
[wynnvoicechat.com/rules](https://wynnvoicechat.com/rules); the ban ladder and appeal process are in
[terms §7–8](https://wynnvoicechat.com/terms#7-moderation-and-bans). This page is how to apply them.

## What you are moderating

The relay carries live voice only. Nothing is stored except the **last two minutes of speech** per
speaker, and that only leaves memory when someone files a report. A report posts to `#voice-reports`
with two clips (the reported player and the reporter), the reason typed in game, the world and
instance, and how many other players could hear the reporter at the time.

Wynncraft treats voice here like a private call. Ordinary voice reports are **ours**, never
Wynncraft's; only the cases under *Escalation* go to their team.

## Handling a report

1. **Listen to both clips.** The reporter's clip is context: it shows what they said too.
2. **Match what you heard to one rule** from the list below. If nothing matches, dismiss.
3. **Pick the length** from the ladder in terms §7 — 7 days for a first ordinary breach, 30 days for a
   repeat or a serious one, permanent for illegal content, the for-everyone list, ban evasion, or a
   third breach. `/voice history <player>` shows their previous bans so "repeat" is a fact, not a guess.
4. **Press the button** on the report (`Ban 7d`, `Ban 30d`, `Ban permanent`, or `Dismiss`) and pick the
   rule broken in the modal. The player is disconnected at once, the reason you picked is shown to them
   in game, the reporter is told the outcome, and `#mod-log` gets one line.

The reason you pick is the whole message the player sees, so choose the rule that fits rather than the
closest-sounding one. Reasons, exactly as the modal lists them:

| Reason | Rules bullet |
|---|---|
| Harassment, threats or bullying | Harassment, threats, stalking, or bullying |
| Hate speech | Abuse or hate aimed at a protected characteristic |
| Sexual content | Sexual content of any kind |
| Encouraging self-harm or drug use | Suicide, self-harm, eating disorders, dangerous challenges, drugs |
| Encouraging violence or terrorism | Violence or terrorism |
| Sharing personal information | Doxxing |
| Disruption (earrape, soundboards, spam) | Deliberate disruption |
| Ban evasion | Another account used to get around a ban |

## Commands

All under `/voice`, moderators only, answers are visible only to you:

- `/voice ban <player> <reason> [days]` — ban without a report (someone e-mailed `safety@`, say). Omit
  `days` for permanent. Any open reports against them are marked actioned and their reporters told.
- `/voice unban <player>` — lifts every active ban; use it for upheld appeals.
- `/voice history <player>` — every ban they have had, lifted ones included.
- `/voice bans` — active bans.
- `/voice blocks <player>` — who a player has blocked (useful when someone claims a report is retaliation).

Names resolve through live sessions first, then Mojang, so an offline player works too.

## Escalation

Forward to the Wynncraft team, with the clip, **as well as** banning permanently:

- grooming or any sexual talk with or about a child (also report to the relevant UK authorities);
- terrorism content (also the police);
- serious harassment that spans other platforms.

Anything else stays with us. Do not send Wynncraft ordinary voice reports; their condition for
tolerating the mod is that reports come to us and the process is clear to players.

## Appeals

Appeals arrive as **private threads in `#ban-appeals`**, opened by the *Start an appeal* button on the pinned message (the relay creates the thread and adds the player); only the appellant and moderators see them.
Rules:

- A moderator who **did not** make the original decision reviews it, listening to the clip again.
- Answer in the thread, normally within a week. Upheld: `/voice unban`, and say so. Not upheld: say why.
- Repeated appeals with nothing new can be closed without a further answer.
- Complaints about *us* (a report ignored, a wrong dismissal) come the same way and are handled the same way.

## What never to do

- Never share a clip outside `#voice-reports`, and never download one for any purpose but review. Clips
  are deleted from the relay after 30 days; Discord keeps its copy under Discord's own retention.
- Never ban on hearsay. The clip is the evidence; if the report has no audio (`no audio` in the
  window field), dismiss unless there is a clip from another report.
- Never contact a reported player outside the appeal thread, and never reveal who reported them.
- Never act on anything outside voice. Moderators are not Wynncraft staff.

## Where things are

| Channel | Purpose |
|---|---|
| `#voice-reports` | Reports with buttons; moderators only |
| `#mod-log` | One line per ban and unban; read-only |
| `#ban-appeals` | Private appeal threads |
| `#staff-chat` | Anything you want a second opinion on |

Questions about the process itself go to the project owner in `#staff-chat`.
