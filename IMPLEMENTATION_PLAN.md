# EpicPunishments implementation plan

EpicPunishments will be a modular Paper moderation plugin with player/IP tracking, punishments, and player reports. The design uses feature-owned domain and application layers, persistence ports, and replaceable database adapters.

Version one will support SQLite, MySQL, and PostgreSQL through configuration. SQLite will be the zero-configuration default. MongoDB is not a version-one deliverable, but persistence contracts must not depend on JDBC so that a document-database adapter can be added later.

## 1. Scope and compatibility

### Supported platform

- Java 25.
- Paper 26.2 as the minimum API and tested server version.
- Paper releases `>= 26.2` and `< 27` are the intended compatibility range, but each later 26.x release must pass the smoke suite before being declared supported.
- `plugin.yml` remains the plugin manifest; experimental Paper plugin manifests will not be introduced in version one.
- No NMS or CraftBukkit internals.
- Folia support is not claimed in version one. The execution abstraction should avoid making future Folia support unnecessarily difficult.

The Paper API dependency will be pinned to a known stable 26.2 build for reproducible releases instead of resolving `build.+`.

### Deployment model

Version one is a single-server plugin. MySQL and PostgreSQL provide external durable storage but do not imply network-wide live synchronization.

Multiple servers may point at the same database only if administrators accept that:

- Login checks see committed shared punishments.
- Online-session caches are local to each server.
- Immediate cross-server kicks, mute changes, notifications, and cache invalidation are not provided.

Network-wide enforcement will require a later messaging module using pub/sub, proxy messaging, or another explicit invalidation mechanism.

### Moderation source of truth

EpicPunishments is authoritative for punishments it creates. Its history will not be mirrored into Paper's built-in ban lists because dual writes could become inconsistent.

- Vanilla `/ban`, `/ban-ip`, `/pardon`, and `/banlist` do not manage EpicPunishments records.
- EpicPunishments checks run alongside any restrictions already enforced by the server.
- Importing existing vanilla ban lists can be added as a separate administrative migration feature.

### Version-one interaction scope

Version one includes:

- Brigadier commands.
- Console-compatible administration.
- Paper event listeners.
- Adventure and MiniMessage output.

Inventory GUIs, HTTP APIs, web dashboards, and Discord integrations are future interaction adapters.

## 2. Project foundation

- Retain Gradle Kotlin DSL and the existing Paper run task.
- Add:
  - JUnit 5 and AssertJ.
  - HikariCP for JDBC connection pooling.
  - SQLite JDBC.
  - MySQL Connector/J.
  - PostgreSQL JDBC.
  - Flyway with the required database modules, or an equivalent migration runner.
  - Testcontainers for MySQL and PostgreSQL repository tests.
- Produce a shaded plugin JAR and relocate bundled implementation libraries where appropriate.
- Do not rely on database drivers present in the Paper server distribution.
- Rename packages to conventional lowercase, such as `net.epicpunishments`.
- Keep the `JavaPlugin` subclass small and use `PluginContainer` as the composition root.
- Avoid global mutable singletons and service-locator access.
- Add validated, immutable configuration snapshots.
- Support environment-variable substitution for database secrets and never log credentials.

Suggested resources:

```text
src/main/resources/
|-- plugin.yml
|-- config.yml
|-- messages.yml
`-- db/migration/
    |-- sqlite/
    |-- mysql/
    `-- postgres/
```

Example database configuration:

```yaml
database:
  type: sqlite
  query-timeout: 3s
  login-failure-policy: deny

  sqlite:
    file: epicpunishments.db

  mysql:
    host: localhost
    port: 3306
    database: epicpunishments
    username: root
    password: ${EPIC_DB_PASSWORD}
    pool-size: 8

  postgres:
    host: localhost
    port: 5432
    database: epicpunishments
    username: postgres
    password: ${EPIC_DB_PASSWORD}
    pool-size: 8
```

SQLite will use:

- WAL journal mode.
- Foreign-key enforcement.
- A busy timeout.
- A single-writer-oriented pool configuration.
- Bounded retry for transient lock failures where the operation is safe to retry.

Database configuration changes require a restart. `/epicpunishments reload` reloads only settings designed for atomic replacement, such as messages, cooldowns, and punishment policies.

## 3. Architecture and module boundaries

Organize by feature, with ports owned by the feature that consumes them and adapters kept in infrastructure:

```text
net.epicpunishments
|-- bootstrap
|   |-- EpicPunishments
|   `-- PluginContainer
|-- common
|   |-- config
|   |-- execution
|   |-- message
|   `-- validation
|-- identity
|   |-- domain
|   |-- application
|   `-- port
|-- punishment
|   |-- domain
|   |-- application
|   `-- port
|-- report
|   |-- domain
|   |-- application
|   `-- port
|-- infrastructure
|   `-- persistence
|       |-- sqlite
|       |-- mysql
|       `-- postgres
`-- interaction
    |-- command
    `-- listener
```

Dependency direction:

```text
Commands and listeners -> Application services -> Feature ports
                                                   ^
                                      Persistence adapters
```

Rules:

- Domain and application packages do not import Bukkit, Paper, JDBC, HikariCP, Flyway, or YAML types.
- Interaction adapters capture Bukkit values before crossing an asynchronous boundary.
- Application services use domain objects, UUIDs, `Instant`, `Duration`, and an injected `Clock`.
- Database adapters map domain values to storage-specific representations.
- Paper mutations are scheduled on the correct server or entity thread.
- All I/O is represented explicitly through `CompletionStage<T>` or a small project-owned asynchronous result abstraction.
- No database or file operation runs on the main server thread.

## 4. Domain and persistence contracts

Core immutable domain types:

- `PlayerIdentity`
- `PlayerAddress`
- `LoginAssessment`
- `SessionPunishments`
- `Punishment`
- `PunishmentTarget`
  - `PLAYER`
  - `IP_ADDRESS`
- `PunishmentType`
  - `BAN`
  - `MUTE`
  - `WARNING`
- `Report`
- `ReportResponse`
- `ReportNotification`
- `ReportStatus`
  - `OPEN`
  - `IN_REVIEW`
  - `RESOLVED`
  - `DISMISSED`
- `Actor`
  - Player.
  - Console.
  - Future web/API actor.

Feature ports:

```text
PlayerIdentityRepository
LoginAssessmentRepository
PunishmentRepository
ReportRepository
ModerationMutationStore
ReportMutationStore
PersistenceProvider
```

`PersistenceProvider` owns connection initialization, migration selection, health checks, and shutdown. A provider factory selects the adapter from validated configuration.

Transactions are exposed as use-case operations rather than a JDBC-oriented `TransactionManager`. For example:

```text
create punishment + write audit entry
revoke punishment + write audit entry
claim report + write audit entry
respond/resolve/dismiss report + write audit entry
```

Each pair must commit or roll back atomically. MongoDB or another future adapter may implement the same port using its own transaction mechanism.

## 5. Data model

Principal tables or equivalent future collections:

- `players`
  - UUID, current name, first seen, and last seen.
- `player_names`
  - Player UUID, historical name, first seen, and last seen.
- `addresses`
  - Binary address, address family, first seen, and last seen.
- `player_addresses`
  - Player UUID, address ID, first successful join, last successful join, and join count.
- `punishments`
  - ID, type, target type, player UUID or address ID, reason, issuer, creation, expiry, and revocation details.
- `punishment_deliveries`
  - Punishment ID, affected player UUID, delivered timestamp, and optional acknowledged timestamp.
- `reports`
  - ID, reporter UUID, reported UUID, reason, status, assignee, optimistic-lock version, and timestamps.
- `report_responses`
  - Report ID, administrator actor, message, visibility, and timestamp.
- `report_notifications`
  - Recipient UUID, report/response reference, created timestamp, and read timestamp.
- `audit_log`
  - Actor, action, entity type/ID, timestamp, and structured details stored in a portable text representation.

Storage rules:

- UUIDs have one canonical representation per adapter.
- IPv4 and IPv6 addresses are stored as 4- or 16-byte binary values, not hostnames.
- Address handling never performs reverse DNS.
- IPv4-mapped IPv6 values are normalized consistently to IPv4.
- Version one supports exact-address targets only; CIDR and range punishments are future features.
- Timestamps are stored and interpreted as UTC.
- Expiry is calculated from an injected clock; expired records remain part of history.
- An address referenced by an active punishment cannot be removed by retention cleanup.
- IP addresses are redacted by default in logs and messages.
- Only dedicated IP permissions expose complete addresses.

Constraints and indexes will cover:

- Current and historical player-name lookup.
- UUID and normalized binary-address lookup.
- Active punishments by target and type.
- Open reports by creation time and assignee.
- Player-to-address history.
- Warning delivery uniqueness per punishment/player.
- Optimistic report updates.
- Idempotent migrations.
- Concurrent create/revoke and claim/close behavior.

Database-specific DDL and upsert behavior live in the adapter-specific migration directories. All adapters must pass the same repository contract suite before a migration or repository change is accepted.

## 6. Execution, concurrency, and failure policy

### Asynchronous execution

- Use a plugin-owned, lifecycle-managed executor for repository work.
- Bound database concurrency by the configured connection pool rather than creating unbounded JDBC work.
- Do not retain Bukkit objects in asynchronous jobs; capture immutable UUID, name, address bytes, and message data first.
- Ignore or safely terminate callbacks after shutdown begins.
- Use bounded shutdown waits and close the executor and persistence provider in `onDisable`.

### Runtime database failures

Every database operation has a timeout and returns a classified failure rather than leaking SQL exceptions into services.

Login failure policy is configurable:

- `deny` is the secure default: reject login with a configurable temporary-error message when a definitive assessment cannot be obtained.
- `allow-with-cache` denies known cached bans but otherwise allows login while reporting degraded protection loudly to staff and logs.

Moderation commands fail without changing local enforcement state if their database transaction does not commit. Tracking writes may retry safely and report failure without disconnecting an otherwise allowed player.

### Cache consistency

The plugin maintains a server-local `SessionPunishmentCache` keyed by player UUID and current normalized address.

- It is populated from `LoginAssessment`.
- It is updated immediately after a local punishment transaction commits.
- Revocations update it immediately after commit.
- Expiration is checked against `expiresAt` during cache access, so correctness does not depend on a cleanup scheduler.
- An IP punishment updates every matching online session on the current server.
- A database outage never turns an uncommitted command into an in-memory punishment.

## 7. Player tracking and login assessment

The plugin distinguishes a connection attempt from a successful join.

### Pre-login assessment

During `AsyncPlayerPreLoginEvent`:

1. Capture the authenticated UUID, username, forwarded address, and timestamp.
2. Normalize the address without DNS access.
3. Obtain one `LoginAssessment` containing active player/IP bans, mutes, and undelivered warnings.
4. Complete the assessment before the event handler returns, using the configured query timeout.
5. If a ban applies, disallow login with an Adventure component.
6. If login is allowed, place the immutable assessment in a bounded pending-login cache keyed by UUID.

The event is already asynchronous. The implementation must not start work, return from the event, and attempt to disallow the player later.

### Successful join

During `PlayerJoinEvent`:

1. Consume the pending assessment or perform a safe fallback lookup.
2. Initialize the session punishment cache.
3. Capture the final UUID, username, and forwarded address.
4. Persist the player, name history, and player-address association asynchronously.
5. Deliver applicable warnings on the player's entity thread.
6. Record successful warning deliveries asynchronously.

Only this stage increments join history. Rejected login attempts are not recorded as successful joins.

Pending assessments are removed on consumption and expire automatically after a short bounded interval.

### Proxy behavior

The plugin relies on Paper's configured Velocity or Bungee forwarding. Documentation must explain that incorrect forwarding causes the proxy address to be assessed and stored instead of the client address.

## 8. Punishment module

### Canonical command tree

The guaranteed command tree is:

```text
/epicpunishments punish ban <target> [duration] <reason>
/epicpunishments punish unban <target> [reason]
/epicpunishments punish mute <target> [duration] <reason>
/epicpunishments punish unmute <target> [reason]
/epicpunishments punish warn <target> <reason>
/epicpunishments punish warnings <target> [page]
/epicpunishments punish history <target> [page]
```

Convenience roots such as `/ban`, `/mute`, and `/warn` are configurable aliases. Registration collisions with vanilla or other plugins are detected and logged; the canonical EpicPunishments command remains available.

Targets use an explicit typed grammar:

```text
player:<name>
player:<uuid>
ip:<ipv4-or-ipv6-address>
```

Historical names that resolve to more than one UUID produce an ambiguity error and require a UUID. Target parsing never guesses between a player and an IP address.

Durations support `perm` and bounded units such as `30m`, `12h`, and `7d`. Reasons, notes, and response messages have configurable maximum lengths.

### Behavior

- Support permanent and temporary player/IP bans and mutes.
- Support player/IP warnings.
- Resolve offline players from stored identity history.
- Check player bans by UUID rather than mutable username.
- Disconnect matching online players after a ban transaction commits.
- Enforce chat mutes using only `SessionPunishmentCache`; never query a database from a chat event.
- Apply IP mutes to every current session using the punished address.
- Deliver an IP warning once to each affected player unless policy explicitly allows repeat delivery.
- Preserve expired and revoked punishment history.
- Store the revoking actor, timestamp, and reason without mutating the original issuer data.
- Audit every successful moderation change in the same transaction.

### Authorization

Suggested permissions:

```text
epicpunishments.command
epicpunishments.punishment.ban
epicpunishments.punishment.ban.ip
epicpunishments.punishment.unban
epicpunishments.punishment.mute
epicpunishments.punishment.mute.ip
epicpunishments.punishment.unmute
epicpunishments.punishment.warn
epicpunishments.punishment.warn.ip
epicpunishments.punishment.warnings
epicpunishments.punishment.history
epicpunishments.punishment.history.ip
epicpunishments.punishment.override-exempt
epicpunishments.exempt
epicpunishments.notify.punishment
```

Permissions and safe defaults are declared in `plugin.yml`. Brigadier requirements are applied to each branch so inaccessible commands are hidden.

A `TargetAuthorizationService` applies exemption policy before every punishment:

- Players with `epicpunishments.exempt` cannot be punished by ordinary moderators.
- `override-exempt` is required to bypass the exemption.
- Console bypass behavior is explicit and configurable.
- IP actions require the matching `.ip` permission because they expose sensitive data and may affect several accounts.

## 9. Reporting module

### Commands

Player commands:

```text
/epicpunishments report create <player> <reason>
/epicpunishments report status <report-id>
/epicpunishments report list [page]
```

Administrative commands:

```text
/epicpunishments reports list [open|in-review|resolved|dismissed] [page]
/epicpunishments reports view <report-id>
/epicpunishments reports claim <report-id>
/epicpunishments reports respond <report-id> <message>
/epicpunishments reports resolve <report-id> [resolution]
/epicpunishments reports dismiss <report-id> <reason>
```

`/report` and `/reports` may be registered as configurable convenience aliases.

### Workflow and access rules

- Players cannot report themselves.
- Apply configurable per-player cooldowns and duplicate-report protection.
- Preserve the names and UUIDs involved at report creation.
- Validate status transitions in the application service.
- Use optimistic locking so two staff actions cannot silently overwrite one another.
- Store administrator responses as immutable history.
- Track unread responses and notifications explicitly.
- Notify reporters immediately when online or on their next join.
- Alert staff with the appropriate permission to new reports.
- Allow reporters to view only reports they created.
- Require staff permissions to list or view other players' reports.
- Support administrative actions from console.
- Audit claims, responses, resolutions, and dismissals atomically with their state changes.

Suggested permissions:

```text
epicpunishments.report.create
epicpunishments.report.own
epicpunishments.report.staff.list
epicpunishments.report.staff.view
epicpunishments.report.staff.claim
epicpunishments.report.staff.respond
epicpunishments.report.staff.resolve
epicpunishments.report.staff.dismiss
epicpunishments.notify.report
```

## 10. Messaging, configuration, and observability

- Use Adventure components and MiniMessage templates.
- Parse configured templates when configuration loads rather than for every message.
- Escape player-provided placeholders before inserting them into MiniMessage templates.
- Keep messages keyed by stable identifiers.
- Include actionable startup and migration errors without logging credentials.
- Log moderation IDs and report IDs for correlation.
- Expose `/epicpunishments status` with provider type, schema version, health state, and pending-task counts without exposing secrets.
- Reload configuration into a validated immutable snapshot, then atomically replace the active snapshot.
- Reject invalid reloads and retain the previous working configuration.

## 11. Lifecycle

Startup sequence:

1. Load and validate configuration and messages.
2. Create the bounded execution infrastructure.
3. Select and initialize the persistence provider.
4. Run the adapter-specific migrations.
5. Verify repository health and schema version.
6. Construct repositories, application services, caches, and interaction adapters.
7. Register listeners and lifecycle-based Brigadier commands.
8. Log the active database provider and compatibility version.

If persistence or migrations cannot initialize safely, disable the plugin instead of enabling partial moderation behavior.

Shutdown sequence:

1. Mark application services as stopping and reject new work.
2. Cancel scheduled maintenance and expire pending login assessments.
3. Allow already-committed result callbacks a bounded completion window.
4. Close the persistence provider.
5. Shut down the plugin executor.
6. Clear session and pending-login caches.

## 12. Testing strategy

Testing accompanies each vertical feature rather than being deferred until the end.

### Unit tests

- Duration parsing and bounds.
- Address normalization, including IPv4-mapped IPv6.
- Player and IP target parsing.
- Punishment expiry and revocation.
- Exemption and permission policy.
- Historical-name ambiguity.
- Login failure policies.
- Session cache updates and expiry.
- Report cooldowns and valid/invalid transitions.
- Reporter/staff access control.
- Message placeholder escaping.

### Repository contract tests

Every SQLite, MySQL, and PostgreSQL adapter runs the same behavioral suite:

- Identity/name/address upserts.
- Successful-join counting.
- Active punishment lookup.
- Punishment and audit atomicity.
- Concurrent punishment creation/revocation.
- Warning delivery idempotency.
- Report optimistic locking.
- Report transition and audit atomicity.
- Pagination and ordering.
- Migration from every supported schema version.

SQLite uses temporary databases. MySQL and PostgreSQL use Testcontainers in CI.

### Paper integration and smoke tests

- Command tree, suggestions, permissions, and alias collisions.
- Allowed login, player ban, IP ban, and database-timeout behavior.
- Rejected attempts do not increment successful-join history.
- Player and IP mute enforcement without chat-event database access.
- Immediate local cache changes after punish/revoke.
- Warning delivery and delivery persistence.
- Offline punishment.
- Report creation, response, notification, and resolution.
- Restart persistence.
- Safe shutdown with work in flight.
- Invalid configuration and migration failure.

The existing `runServer` task is used for manual and automated smoke scenarios where practical.

## 13. Delivery order

1. Pin the toolchain/API build and add build, test, shading, and migration dependencies.
2. Introduce validated configuration, messages, execution boundaries, and lifecycle cleanup.
3. Add domain types, feature ports, database contract tests, and test fakes.
4. Implement SQLite migrations and adapters until all repository contracts pass.
5. Implement login assessment, pending-login state, successful-join tracking, and tests.
6. Implement player punishments, authorization, atomic auditing, cache enforcement, commands, and tests.
7. Implement IP punishments, privacy controls, local session matching, and tests.
8. Implement reporting, optimistic transitions, notifications, commands, and tests.
9. Implement MySQL and PostgreSQL migrations/adapters against the existing contract suite.
10. Complete Paper smoke tests, outage tests, documentation, and release packaging.

Permissions, auditing, failure behavior, and automated tests are part of each feature step and are not deferred to final hardening.

## 14. Version-one completion criteria

Version one is complete when:

- The plugin starts cleanly on Java 25 and the supported Paper 26.2 build.
- The compatibility policy for later 26.x versions is documented and tested before those versions are declared supported.
- Player UUID, name history, and successful-join IP history survive restarts.
- Rejected connection attempts are not recorded as successful joins.
- Permanent and temporary player/IP bans and mutes are enforced.
- Player/IP warnings are stored and delivered according to policy.
- No database lookup occurs in chat-event handling.
- Punishments can be inspected and revoked without losing history.
- Players can submit reports, list their own reports, and view responses.
- Staff can claim, respond to, resolve, and dismiss reports without lost updates.
- All moderation and report mutations are permission-checked and atomically audited.
- Complete IP addresses are visible only to explicitly authorized senders.
- SQLite, MySQL, and PostgreSQL pass the shared repository contract and migration suites.
- No database or file work blocks the main server thread.
- Login database failures follow the configured, tested failure policy.
- Configuration errors, unavailable databases, and migration failures fail safely with actionable messages.
- The documented single-server cache and consistency guarantees are satisfied.
