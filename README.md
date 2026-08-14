# EpicPunishments

EpicPunishments is a Paper moderation plugin under active development. The current implementation targets Java 25 and Paper 26.2.

## Proxy forwarding

When the server is behind Velocity or BungeeCord, configure Paper's supported player-information forwarding before using EpicPunishments. The plugin intentionally assesses and records the address supplied by Paper during login and join events; it does not attempt to discover a client address itself.

If forwarding is missing or configured incorrectly, the proxy address will be assessed and stored instead of the player's address. That can make address-based moderation affect the wrong scope. Restart the server after correcting forwarding or database settings.

## Login database failures

`database.login-failure-policy` controls login behavior when a definitive assessment cannot complete within `database.query-timeout`:

- `deny` rejects the login temporarily and is the secure default.
- `allow-with-cache` still rejects a matching ban already known by the local session cache, but otherwise permits the login and warns staff that protection is degraded.
