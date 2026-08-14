package net.epicpunishments.identity.application;

import net.epicpunishments.identity.domain.LoginAssessment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PendingLoginAssessments {
    private final int maximumSize;
    private final Duration timeToLive;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public PendingLoginAssessments(int maximumSize, Duration timeToLive, Clock clock) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        if (timeToLive.isNegative() || timeToLive.isZero()) {
            throw new IllegalArgumentException("timeToLive must be positive");
        }
        this.maximumSize = maximumSize;
        this.timeToLive = timeToLive;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void put(LoginAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        purgeExpired();
        entries.remove(assessment.playerId());
        if (entries.size() >= maximumSize) {
            UUID oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
        entries.put(assessment.playerId(), new Entry(assessment, clock.instant().plus(timeToLive)));
    }

    public synchronized Optional<LoginAssessment> take(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Entry entry = entries.remove(playerId);
        if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.assessment());
    }

    public synchronized int purgeExpired() {
        Instant now = clock.instant();
        int previousSize = entries.size();
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
        return previousSize - entries.size();
    }

    public synchronized int size() {
        purgeExpired();
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    private record Entry(LoginAssessment assessment, Instant expiresAt) {
    }
}
