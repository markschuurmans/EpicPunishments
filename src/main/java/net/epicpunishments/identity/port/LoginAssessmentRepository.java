package net.epicpunishments.identity.port;

import net.epicpunishments.identity.domain.LoginAssessment;
import net.epicpunishments.identity.domain.PlayerAddress;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface LoginAssessmentRepository {
    CompletionStage<LoginAssessment> assessLogin(UUID playerId, PlayerAddress address, Instant assessedAt);
}
