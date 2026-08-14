package net.epicpunishments.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this(instant, ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new MutableClock(instant, requestedZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
