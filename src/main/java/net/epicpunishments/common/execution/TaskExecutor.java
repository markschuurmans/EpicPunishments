package net.epicpunishments.common.execution;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

public interface TaskExecutor {
    <T> CompletionStage<T> submit(Callable<T> task);

    int pendingTaskCount();
}
