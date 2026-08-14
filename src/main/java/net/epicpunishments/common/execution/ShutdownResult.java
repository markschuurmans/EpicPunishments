package net.epicpunishments.common.execution;

public record ShutdownResult(boolean terminated, int cancelledTasks) {
}
