# Paper 26.2 smoke tests

Run this checklist for the pinned Paper 26.2 build and again before declaring compatibility with any later 26.x build. Use a disposable server directory and test accounts; the scenarios intentionally create moderation data.

## Automated prerequisites

From PowerShell:

```powershell
.\gradlew.bat clean test
.\gradlew.bat build
```

`build` produces and validates the shaded plugin JAR. Repository tests use temporary SQLite databases and do not modify the `run/` server database.

## Server startup and shutdown

1. Accept the Minecraft EULA in the disposable `run/eula.txt` file.
2. Start the pinned server with `.\gradlew.bat runServer`.
3. Confirm Paper reports version `26.2` build `112`, EpicPunishments enables, both migrations validate, and the log reports provider `sqlite` and schema version `2` without a connection URL or credentials.
4. Run `epicpunishments status` from the console. Expect `sqlite`, schema `2`, health `healthy`, and a non-negative pending-task count.
5. Run `epicpunishments version`, `epicpunishments reload`, and `epicpunishments punish`. Confirm the canonical Brigadier tree is present and invalid/incomplete input returns a message rather than an exception.
6. Stop the server while it is idle and again while commands are in flight. Confirm bounded shutdown completes without thread-leak warnings or uncaught exceptions.

## Login, punishment, and persistence

1. Join with a test player, stop the server, restart it, and confirm the player resolves by current name and UUID.
2. Ban the offline player by UUID, confirm the command logs the punishment ID, then verify the next login is rejected.
3. Revoke the ban, restart, and confirm login succeeds while punishment history still shows the revoked entry.
4. Apply a temporary ban and repeat after its expiry. The expired entry must remain in history but no longer deny login.
5. Apply exact IPv4 and IPv6 bans. Matching sessions must be disconnected only after commit; unrelated addresses must remain online.
6. Apply player and IP mutes. Matching chat must be blocked from the session cache without a persistence query.
7. Apply player and IP warnings. Confirm each applicable warning is delivered and its delivery is not repeated after restart.
8. Reject a login with a ban and verify its player/address join count did not increase.

Full IP addresses must be absent from ordinary history and staff notifications. Repeat history as a sender with `epicpunishments.punishment.history.ip` to verify authorized full display.

## Reports and authorization

1. Create a report as a player and verify authorized staff receive a new-report notification.
2. Verify another ordinary player cannot view the report.
3. Claim, respond to, and resolve the report from player and console staff senders. Confirm the reporter receives the response online or on the next join.
4. Race two staff transitions from the same visible report version. Exactly one must apply; the other must receive a version-conflict response.
5. Restart and confirm the report, immutable responses, notifications, status, and audit history persist.
6. Verify each command branch is hidden or rejected without its declared permission. Test exemption override separately.

## Outage and invalid-configuration checks

1. With `login-failure-policy: deny`, make the SQLite file temporarily unavailable before login. Confirm the bounded assessment rejects with the configured temporary-error message.
2. Repeat with `allow-with-cache`: a known cached ban remains denied; an otherwise unknown player is allowed and staff with `epicpunishments.notify.degraded` receive a warning.
3. Make persistence unavailable during a moderation/report mutation. Confirm the command fails, no local enforcement state changes, and no partial audit or entity mutation is stored.
4. Break YAML or a MiniMessage template and run reload. Confirm reload fails and the previous messages/settings continue working.
5. Select `mysql` or `postgres` in the current build. Confirm startup fails closed with an adapter-unavailable message and does not print the configured password or full connection URL.
6. Corrupt or make the SQLite migration target unwritable in the disposable environment. Confirm EpicPunishments disables rather than enabling partial moderation behavior.

Restore file permissions and valid configuration after each scenario. Never run destructive outage scenarios against a production database.
