package net.epicpunishments.report.domain;

import java.util.Objects;
import java.util.UUID;

public record ReportParticipant(UUID playerId, String nameAtCreation) {
    public ReportParticipant {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(nameAtCreation, "nameAtCreation");
        if (nameAtCreation.isBlank() || nameAtCreation.length() > 16) {
            throw new IllegalArgumentException("nameAtCreation must contain between 1 and 16 characters");
        }
    }
}
