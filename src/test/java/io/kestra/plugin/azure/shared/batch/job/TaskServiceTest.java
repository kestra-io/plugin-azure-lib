package io.kestra.plugin.azure.shared.batch.job;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.TaskOperations;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.TaskState;
import io.kestra.core.runners.RunContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldWaitForSubSecondCompletionCheckInterval() throws Exception {
        RunContext runContext = mock(RunContext.class);
        Logger logger = mock(Logger.class);
        BatchClient client = mock(BatchClient.class);
        TaskOperations taskOperations = mock(TaskOperations.class);
        CloudTask activeTask = mock(CloudTask.class);
        CloudTask completedTask = mock(CloudTask.class);
        PagedList<CloudTask> activeTasks = mock(PagedList.class);
        PagedList<CloudTask> completedTasks = mock(PagedList.class);

        when(runContext.logger()).thenReturn(logger);
        when(client.taskOperations()).thenReturn(taskOperations);
        when(activeTask.state()).thenReturn(TaskState.ACTIVE);
        when(completedTask.state()).thenReturn(TaskState.COMPLETED);
        when(activeTasks.iterator()).thenReturn(List.of(activeTask).iterator());
        when(completedTasks.iterator()).thenReturn(List.of(completedTask).iterator());
        when(taskOperations.listTasks(eq("job-id"), any()))
            .thenReturn(activeTasks, completedTasks);

        long startedAt = System.nanoTime();

        TaskService.waitForTasksToComplete(
            runContext,
            client,
            "job-id",
            Duration.ofSeconds(1),
            Duration.ofMillis(100)
        );

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        assertThat(elapsedMillis, greaterThanOrEqualTo(80L));
        verify(taskOperations, times(2)).listTasks(eq("job-id"), any());
    }
}
