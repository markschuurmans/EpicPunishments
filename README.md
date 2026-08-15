# EpicPunishments

EpicPunishments is a single-server moderation plugin for Paper. It tracks successful player joins, enforces player and exact-address punishments, and provides an audited report workflow.

## Compatibility

- Java 25 is required.
- Paper 26.2 build 112 is the pinned minimum and tested server build.
- Later Paper 26.x releases are not declared compatible until the smoke suite passes on that build.
- Paper 27 and Folia are not supported.
- NMS and CraftBukkit internals are not used.

The current build supports SQLite persistence. MySQL and PostgreSQL configuration is validated, but their adapters are not present yet; selecting either provider disables the plugin with an actionable startup error rather than starting with partial moderation protection.

## Installation

1. Build with `./gradlew.bat clean build` on Windows or `./gradlew clean build` on another platform.
2. Copy the unclassified JAR from `build/libs/` into the Paper server's `plugins/` directory.
3. Start Paper once to generate `plugins/EpicPunishments/config.yml` and `messages.yml`.
4. Review database, failure-policy, moderation, and report settings, then restart after any database change.

The release JAR is shaded and contains the connection pool, migration engine, and database drivers; server-provided drivers are not used. `build` also verifies that required runtime classes, migrations, and plugin resources are in the artifact.

## Configuration and safe reloads

SQLite is the zero-configuration default. Its database path is relative to the plugin data directory and it uses WAL mode, foreign keys, a bounded busy timeout, and a single-connection pool.

`/epicpunishments reload` reloads messages and reloadable moderation/report settings into a validated immutable snapshot. An invalid reload is rejected and the prior working snapshot remains active. Database settings require a server restart and are never changed by reload.

MiniMessage templates are parsed when configuration loads. Player-controlled placeholder values are escaped before insertion. Environment substitutions such as `${EPIC_DB_PASSWORD}` are supported for secrets; do not put secrets directly in files that are committed or shared.

## Commands

The canonical root is always `/epicpunishments`. Configurable convenience aliases are registered only when their root does not collide with another command.

```text
/epicpunishments status
/epicpunishments version
/epicpunishments reload
/epicpunishments punish ban <player:name|player:uuid|ip:address> [duration] <reason>
/epicpunishments punish unban <target> [reason]
/epicpunishments punish mute <target> [duration] <reason>
/epicpunishments punish unmute <target> [reason]
/epicpunishments punish warn <target> <reason>
/epicpunishments punish warnings <target> [page]
/epicpunishments punish history <target> [page]
/epicpunishments report create <player> <reason>
/epicpunishments report status <report-id>
/epicpunishments report list [page]
/epicpunishments reports list [open|in-review|resolved|dismissed] [page]
/epicpunishments reports view|claim|respond|resolve|dismiss ...
```

Durations accept `perm` or bounded values such as `30m`, `12h`, and `7d`. Targets are always explicit; names are never guessed to be addresses. The status command exposes only provider type, schema version, health, and pending task count—never credentials or connection URLs.

All permissions and safe defaults are declared in `plugin.yml`. IP actions require their dedicated `.ip` permission, and full IP history requires `epicpunishments.punishment.history.ip`. Ordinary moderators cannot punish players with `epicpunishments.exempt` unless they also have `epicpunishments.punishment.override-exempt`.

## Failure and consistency model

`database.login-failure-policy` controls login behavior when an assessment fails or exceeds `database.query-timeout`:

- `deny` is the secure default and rejects the login temporarily.
- `allow-with-cache` still rejects a matching locally cached ban; otherwise it permits login and warns authorized staff that protection is degraded.

Moderation and report state enters the local cache only after its database transaction commits. Chat mute checks use only the session cache and do not query persistence. This is a single-server cache: multiple servers sharing a future external database do not receive live kicks, notifications, or cache invalidations from one another.

Only `PlayerJoinEvent` records successful join/name/address history. Rejected login attempts are not recorded. When running behind Velocity or BungeeCord, configure Paper's supported forwarding correctly; otherwise Paper supplies the proxy address, which EpicPunishments will assess and store as if it were the client address.

Startup configuration, migrations, and health checks must all succeed before moderation listeners become active. Unsafe startup failures disable the plugin. Shutdown rejects new work, closes persistence and the owned executor with bounded waits, then clears local caches.

## Verification

Run:

```powershell
.\gradlew.bat clean test
.\gradlew.bat build
git diff --check
```

The automated suite covers domain/application behavior, SQLite repository contracts and migrations, login outage policies, shutdown with queued work, configuration rollback, and release-JAR contents. The real Paper checklist and repeatable `runServer` procedure are in [docs/PAPER_SMOKE_TESTS.md](docs/PAPER_SMOKE_TESTS.md).
