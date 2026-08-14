package net.epicpunishments.identity.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public record JoinProcessing(
        CompletionStage<JoinOutcome> assessment,
        CompletionStage<Void> successfulJoinWrite
) {
    public JoinProcessing {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(successfulJoinWrite, "successfulJoinWrite");
    }
}
