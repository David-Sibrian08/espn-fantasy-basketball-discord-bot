# ESPN Fantasy Basketball Discord Bot

A self-hosted Discord bot that pulls live data from your ESPN Fantasy Basketball
league and posts standings, matchups, and weekly recaps straight into your
server.

This is a **self-hosted template**: each deployment is wired to one Discord
bot application and one ESPN league. If you want to run it for your own
league, follow the setup below and deploy your own instance — you don't need
to fork any code to do that, just configure your own `.env`.

## Features

- `/league` — league name, season, team count, current week
- `/standings` — current standings sorted by win percentage
- `/matchup` — this week's (or any week's) head-to-head matchups, with
  buttons/dropdown to page through other weeks, plus each side's top
  individual scorer for that week
- `/recap` — on-demand recap for a given week (highest/lowest scorer, biggest
  win, season trophy race, all-time record tracking)
- `/lineupcheck` — on-demand check for OUT/DOUBTFUL players currently in a
  starting lineup slot
- `/lineupalerts` — turn the automatic lineup health alerts on/off instantly,
  no restart needed (requires Manage Server permission)
- `/team <name>` — a team's current roster (starters/bench/IR), record, and
  rank, with autocomplete on team name
- `/powerrankings` — all-play power rankings (each team's record if they'd
  played every other team every week instead of just their real schedule),
  alongside actual record for comparison
- `/headtohead <team1> <team2>` — all-time series record between two teams,
  scanning every season back to `ESPN_FIRST_SEASON_ID` (see below)
- Optional automatic weekly recap posted to a channel of your choice every
  Monday
- Optional automatic lineup health alerts — pings a starting player's owner
  in a channel of your choice if they're OUT/DOUBTFUL and their game locks
  within ~30 minutes

## Prerequisites

- Java 17+
- Maven
- A Discord account and server where you have permission to add bots
- An ESPN Fantasy Basketball league (public or private)

## 1. Create a Discord bot application

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications)
   and click **New Application**.
2. Under **Bot**, click **Reset Token** and copy it — this is your
   `DISCORD_TOKEN`. Keep it secret; anyone with this token can control your bot.
3. Under **OAuth2 > URL Generator**, select scopes `bot` and
   `applications.commands`, and under **Bot Permissions** select at least
   `Send Messages` and `Embed Links`.
4. Open the generated URL and invite the bot to your server.

## 2. Get your ESPN league info

- **League ID**: visible in the URL when viewing your league on
  fantasy.espn.com, e.g. `.../leagues/lm-team?leagueId=123456` → `123456`.
- **Season ID**: the year the season ends in, e.g. `2026` for the 2025-26 season.
- **First season ID** (optional): the year your league's history starts, if
  you want `/headtohead` and the all-time record book in weekly recaps to
  look back further than the current season. Leave unset to only count the
  current season.
- **Private leagues only** — you'll also need two cookies from your browser
  session on fantasy.espn.com:
  1. Log into fantasy.espn.com.
  2. Open DevTools → Application (Chrome) or Storage (Firefox) → Cookies →
     `https://fantasy.espn.com`.
  3. Copy the values of `espn_s2` and `SWID` (include the curly braces around
     `SWID`).

## 3. Configure and run

```bash
git clone https://github.com/David-Sibrian08/espn-fantasy-basketball-discord-bot.git
cd espn-fantasy-basketball-discord-bot
cp .env.example .env
```

Fill in `.env` with the values from steps 1-2. See `.env.example` for what
each variable is for. Leave `RECAP_CHANNEL_ID` blank/commented out if you
don't want the automatic weekly recap yet — every slash command works fine
without it.

Build and run:

```bash
mvn package
java -jar target/espn-fantasy-bot-1.0-SNAPSHOT.jar
```

The bot registers its slash commands globally on startup, which can take up
to an hour to show up in Discord the first time. If you're actively
developing, set `GUILD_ID` in `.env` to your test server's ID for instant
command updates instead — just remember to unset it before running in
production. Switching between the two never leaves duplicate commands
behind; the bot clears whichever scope isn't currently in use.

On startup, the bot validates your `.env` and will refuse to start (with a
full list of what's wrong, not just the first error) if anything required is
missing or malformed — e.g. a non-numeric `ESPN_LEAGUE_ID`, or only one of
`ESPN_S2`/`SWID` set.

### Alternative: run with Docker

```bash
cp .env.example .env   # fill it in first
touch all_time_records.json   # so Docker bind-mounts a file, not a directory
touch team_owners.json        # same reason - see below if you want lineup alerts to actually @mention owners
docker compose up -d --build
```

An empty `team_owners.json` just means no owners are configured yet (lineup
alerts still work, they just won't @mention anyone) - see
[Setting up lineup health alerts](#setting-up-lineup-health-alerts-optional)
to fill it in for real.

Or without Compose:

```bash
docker build -t espn-fantasy-bot .
touch all_time_records.json
touch team_owners.json
docker run -d --name espn-fantasy-bot \
  --env-file .env \
  -v "$(pwd)/all_time_records.json:/app/all_time_records.json" \
  -v "$(pwd)/team_owners.json:/app/team_owners.json" \
  espn-fantasy-bot
```

## Setting up lineup health alerts (optional)

To have the bot @mention team owners when they're about to start an
OUT/DOUBTFUL player:

1. Set `LINEUP_ALERT_CHANNEL_ID` in `.env` to the channel where alerts should
   post.
2. `cp team_owners.example.json team_owners.json` and fill in each ESPN team
   ID (see `/league` or `/standings`) with the corresponding Discord user ID
   (enable Developer Mode in Discord settings, then right-click a user >
   Copy User ID). `team_owners.json` is gitignored since it contains real
   people's Discord IDs — don't commit it.
3. Restart the bot. It polls every 10 minutes and alerts once per
   player/game (no repeat pings for the same lock).

NBA fantasy locks each player individually at their own game's tip-off, not
once a week like football — so this runs on its own schedule, independent of
the weekly recap.

Use `/lineupalerts enabled:false` to pause alerts anytime (e.g. mid-season)
without touching `.env` or restarting the bot; `/lineupalerts enabled:true`
turns them back on. The setting persists across restarts.

## Notes on the weekly recap scheduler

If `RECAP_CHANNEL_ID` is set, the bot posts a recap to that channel every
Monday at 09:00 UTC, covering the most recently completed week. It also
maintains an all-time record book (`all_time_records.json`, generated
automatically, gitignored) tracking highest/lowest score and biggest/smallest
margin across your league's history. You can trigger a recap manually anytime
with `/recap`, regardless of whether the scheduler is enabled.

## Reliability

Meant to run unattended for months, so a bad response from ESPN shouldn't
take a command (or the whole bot) down with it:

- A single command failing (bad data, ESPN hiccup) only affects that
  interaction — it can't crash the bot process.
- Transient ESPN failures (timeouts, 5xx errors) get retried automatically
  before giving up; a real config problem (bad league ID, a private league
  missing cookies) fails immediately instead of wasting time retrying
  something a retry can't fix.
- If ESPN is down or erroring and there's no way to get a fresh response, the
  bot falls back to the last successful response it cached, rather than
  failing outright — so a short ESPN outage shows slightly stale data
  instead of an error.
- Discord gateway disconnects are handled by JDA's built-in auto-reconnect;
  the bot logs when this happens so it's visible in your logs, not silent.

## Troubleshooting

Run `/diagnostics` first (requires Manage Server permission) — it checks ESPN
connectivity, whether `team_owners.json` is configured, whether your recap/
lineup-alert channels exist and the bot can post in them, and whether the bot
is accidentally still in dev mode. It's the fastest way to see what's actually
misconfigured.

Common issues:

- **Slash commands aren't showing up at all.** Global command registration
  can take up to an hour to propagate the first time. For instant updates
  while setting up, set `GUILD_ID` to your server's ID and restart — just
  remember to unset it again before running in production (see step 3 above).
- **Commands like `/league` reply with "Failed to fetch league data" and a
  message about private leagues.** Your league is private. Set `ESPN_S2` and
  `SWID` in `.env` (see step 2 above) and restart.
- **Lineup alerts fire but never @mention anyone.** `team_owners.json`
  doesn't exist yet, is missing entries for the affected team, or has a
  syntax error (the bot logs this at startup and falls back to no owners
  rather than crashing — check your logs). Run `/diagnostics` to confirm.
- **The bot doesn't come online at all.** Check the logs right after
  startup — a bad `.env` (missing `DISCORD_TOKEN`, non-numeric
  `ESPN_LEAGUE_ID`, etc.) prints every problem found and exits before ever
  connecting to Discord.
- **Duplicate commands in Discord's command picker.** Shouldn't happen — the
  bot always clears whichever scope (global vs. your `GUILD_ID`) it isn't
  currently using. If you still see duplicates, double check you didn't
  invite the bot to the same server under two different applications.

## Running tests

```bash
mvn test
```

Unit tests cover the pure calculation logic (lineup health alerts, box
scores, power rankings, head-to-head) using synthetic fixtures, plus the
ESPN API client's retry/fallback/stale-cache behavior (using a fake HTTP
server, no real ESPN credentials or network access needed). CI runs this on
every push/PR via GitHub Actions.

## Security

- Never commit your `.env` file — it's already gitignored, but double-check
  before pushing if you copy it elsewhere.
- If your Discord token or ESPN cookies ever leak, reset/re-generate them
  immediately (Discord Developer Portal for the token; log out and back into
  ESPN for fresh cookies).

## Support

If you're enjoying the bot, consider supporting development: Cash App
`$DavidSibrian08`, Venmo `@David-Sibrian08`, or PayPal `@DavidSibrian08`. Never
required — just appreciated.

## License

MIT — see [LICENSE](LICENSE).
