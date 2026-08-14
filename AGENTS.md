# EpicPunishments agent guidance

## Source of truth and scope

- Read `IMPLEMENTATION_PLAN.md` before changing code.
- Implement only the milestone or feature requested by the user.
- Treat the implementation plan as authoritative unless the current user request explicitly overrides it.
- Do not scaffold later milestones or broaden the task for convenience.
- Preserve unrelated user changes and work safely in a dirty worktree.

## Platform and build

- Use Java 25, Gradle Kotlin DSL, and the pinned stable Paper 26.2 API.
- Keep `plugin.yml` as the plugin manifest.
- Do not use NMS, CraftBukkit internals, reflection-based server internals, or deprecated APIs when a supported Paper API exists.
- Use Paper's lifecycle-based Brigadier command registration.
- Use Adventure components and MiniMessage for user-facing text.
- Do not claim Folia support unless the implementation and test suite explicitly provide it.

## Architecture

- Keep the `JavaPlugin` subclass small; construct dependencies in `PluginContainer`.
- Organize code under the lowercase `net.epicpunishments` package.
- Domain and application packages must not import Bukkit, Paper, JDBC, HikariCP, Flyway, or YAML types.
- Interaction adapters may depend on application services; infrastructure adapters implement feature-owned ports.
- Do not make application services depend on commands, listeners, or a concrete database.
- Prefer immutable records, UUIDs, `Instant`, `Duration`, enums, and an injected `Clock`.
- Do not introduce global mutable singletons or service-locator access.
- Keep public abstractions focused on use cases rather than generic CRUD.

## Threading and lifecycle

- Never perform database or file I/O on the main server thread.
- Represent I/O boundaries explicitly with `CompletionStage<T>` or the project-owned equivalent.
- Capture immutable values before asynchronous work; do not retain Bukkit objects across thread boundaries.
- A login decision in `AsyncPlayerPreLoginEvent` must complete before its handler returns and must use a bounded timeout.
- Record player/IP join history only after `PlayerJoinEvent`; rejected attempts are not successful joins.
- Never query persistence from a chat event. Enforce mutes through the session punishment cache.
- Schedule Bukkit player/entity mutations on the appropriate server or entity thread.
- Own executors, tasks, caches, and database pools explicitly and close them during `onDisable` with bounded waits.
- Do not update local enforcement state until the corresponding database transaction commits.

## Persistence, security, and privacy

- SQLite, MySQL, and PostgreSQL adapters must satisfy the same repository contract tests.
- Keep database-specific DDL and migrations in provider-specific resource directories.
- Make each moderation/report mutation and its audit entry one atomic transaction.
- Use prepared statements or safe driver bindings for all external values.
- Normalize IP addresses without reverse DNS and store them as binary IPv4/IPv6 values.
- Redact IP addresses by default and require dedicated permissions for full display.
- Never log credentials, connection URLs containing secrets, or unredacted sensitive configuration.
- Do not rely on database drivers bundled with the Minecraft server.

## Change discipline

- Inspect relevant code and tests before editing.
- Prefer the smallest complete vertical change that satisfies the current milestone.
- Explain the purpose of every new production dependency in the completion report.
- Do not leave empty packages, speculative interfaces, commented-out implementations, or placeholder TODOs outside the requested scope.
- Preserve backward compatibility unless the milestone explicitly requires a breaking migration.
- Do not create commits, tags, or releases unless the user explicitly asks.

## Verification

- Add or update automated tests with every behavior change.
- Run targeted tests during implementation.
- Before completing a Java milestone, run `./gradlew.bat clean test` and `./gradlew.bat build` from PowerShell.
- Run repository contract tests for every persistence adapter changed.
- Testcontainers tests may be skipped only when Docker is unavailable; report the skip clearly and never describe skipped tests as passing.
- Use the existing `runServer` task for Paper smoke testing when the milestone requires runtime behavior.
- Review the final diff for main-thread I/O, layer violations, resource leaks, permission bypasses, transaction gaps, and sensitive-data exposure.
- In the final report, list changed files, important design decisions, commands run, results, and any remaining limitations.
