package io.kestra.plugin.azure.shared.batch.job;

import com.azure.compute.batch.BatchClient;
import com.azure.compute.batch.models.BatchTask;
import com.azure.compute.batch.models.BatchTaskExecutionInfo;
import com.azure.compute.batch.models.BatchTaskFailureInfo;
import com.azure.compute.batch.models.BatchTaskState;
import com.azure.compute.batch.models.BatchTasksListOptions;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.BinaryData;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.WorkingDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskServiceBatchClientTest {
    @Test
    void waitsUntilAllTasksAreCompletedAndLogsEachEndOnce() throws Exception {
        var runContext = mock(RunContext.class);
        var logger = mock(Logger.class);
        var client = mock(BatchClient.class);
        var running = task("t1", BatchTaskState.RUNNING, null, null);
        var ended = task("t1", BatchTaskState.COMPLETED, 0, null);
        var failed = task("t2", BatchTaskState.COMPLETED, 1, "boom");

        var firstPoll = paged(List.of(running));
        var secondPoll = paged(List.of(ended, failed));

        when(runContext.logger()).thenReturn(logger);
        when(client.listTasks(eq("job-id"), any(BatchTasksListOptions.class))).thenReturn(firstPoll, secondPoll);

        var startedAt = System.nanoTime();
        var tasks = TaskService.waitForTasksToComplete(runContext, client, "job-id", Duration.ofSeconds(5), Duration.ofMillis(100));
        var elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(tasks, contains(ended, failed));
        assertThat(elapsedMillis, greaterThanOrEqualTo(80L));
        verify(logger).info("Task ended '{}' with exit code '{}'", "t1", 0);
        verify(logger).warn("Task '{}' failed with exit code '{}': {}", "t2", 1, "boom");

        var options = ArgumentCaptor.forClass(BatchTasksListOptions.class);
        verify(client, times(2)).listTasks(eq("job-id"), options.capture());
        assertThat(options.getValue().getSelect(), containsInAnyOrder("id", "state", "executionInfo"));
    }

    @Test
    void timesOutWhenTasksDoNotComplete() {
        var runContext = mock(RunContext.class);
        var client = mock(BatchClient.class);
        var poll = paged(List.of(task("t1", BatchTaskState.RUNNING, null, null)));

        when(runContext.logger()).thenReturn(mock(Logger.class));
        when(client.listTasks(eq("job-id"), any(BatchTasksListOptions.class))).thenReturn(poll);

        assertThrows(TimeoutException.class, () ->
            TaskService.waitForTasksToComplete(runContext, client, "job-id", Duration.ofMillis(200), Duration.ofMillis(50))
        );
    }

    @Test
    void downloadsTaskFilesIntoTheWorkingDirectory(@TempDir Path workingDir) throws Exception {
        var runContext = runContextWithWorkingDir(workingDir);
        var client = mock(BatchClient.class);
        var task = task("t1", BatchTaskState.COMPLETED, 0, null);

        when(client.getTaskFile("job-id", "t1", "wd/outs/1.txt")).thenReturn(BinaryData.fromString("content"));

        var file = TaskService.readRemoteFile(runContext, client, "job-id", task, "wd/outs/1.txt", "outs/1.txt");

        assertThat(file.toPath(), is(workingDir.resolve("outs/1.txt")));
        assertThat(Files.readString(file.toPath()), is("content"));
    }

    @Test
    void readsTaskLogsLineByLine(@TempDir Path workingDir) throws Exception {
        var runContext = runContextWithWorkingDir(workingDir);
        var client = mock(BatchClient.class);
        var task = task("t1", BatchTaskState.COMPLETED, 0, null);
        var lines = new ArrayList<String>();

        when(client.getTaskFile("job-id", "t1", "stdout.txt")).thenReturn(BinaryData.fromString("first\nsecond\n"));

        TaskService.readRemoteLog(runContext, client, "job-id", task, "stdout.txt", lines::add);

        assertThat(lines, hasSize(2));
        assertThat(lines, contains("first", "second"));
    }

    private static RunContext runContextWithWorkingDir(Path workingDir) throws Exception {
        var runContext = mock(RunContext.class);
        var dir = mock(WorkingDir.class);
        when(runContext.workingDir()).thenReturn(dir);
        when(dir.resolve(any(Path.class))).thenAnswer(invocation -> workingDir.resolve((Path) invocation.getArgument(0)));
        when(dir.createTempFile()).thenAnswer(invocation -> Files.createTempFile(workingDir, "batch", ".tmp"));
        return runContext;
    }

    private static BatchTask task(String id, BatchTaskState state, Integer exitCode, String failure) {
        var task = mock(BatchTask.class);
        when(task.getId()).thenReturn(id);
        when(task.getState()).thenReturn(state);
        if (exitCode != null) {
            var executionInfo = mock(BatchTaskExecutionInfo.class);
            when(executionInfo.getEndTime()).thenReturn(OffsetDateTime.now());
            when(executionInfo.getExitCode()).thenReturn(exitCode);
            if (failure != null) {
                var failureInfo = mock(BatchTaskFailureInfo.class);
                when(failureInfo.getMessage()).thenReturn(failure);
                when(executionInfo.getFailureInfo()).thenReturn(failureInfo);
            }
            when(task.getExecutionInfo()).thenReturn(executionInfo);
        }
        return task;
    }

    @SuppressWarnings("unchecked")
    private static PagedIterable<BatchTask> paged(List<BatchTask> tasks) {
        var paged = mock(PagedIterable.class);
        when(paged.stream()).thenAnswer(invocation -> tasks.stream());
        return paged;
    }
}
