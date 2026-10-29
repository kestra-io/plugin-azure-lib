package io.kestra.plugin.azure.shared.batch.job;

import com.microsoft.azure.PagedList;
import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.BatchErrorCodeStrings;
import com.microsoft.azure.batch.TaskOperations;
import com.microsoft.azure.batch.protocol.BatchServiceClient;
import com.microsoft.azure.batch.protocol.Tasks;
import com.microsoft.azure.batch.protocol.models.BatchError;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.ErrorMessage;
import com.microsoft.azure.batch.protocol.models.TaskAddCollectionResult;
import com.microsoft.azure.batch.protocol.models.TaskAddParameter;
import com.microsoft.azure.batch.protocol.models.TaskAddResult;
import com.microsoft.azure.batch.protocol.models.TaskAddStatus;
import com.microsoft.azure.batch.protocol.models.TaskState;
import io.kestra.core.runners.RunContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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

    @Test
    void shouldSubmitTasksInChunksOfOneHundred() throws Exception {
        Tasks tasks = mock(Tasks.class);
        List<Integer> chunkSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<TaskAddParameter> chunk = invocation.getArgument(1);
            chunkSizes.add(chunk.size());
            return success(chunk);
        }).when(tasks).addCollection(eq("job-id"), anyList());

        TaskService.submitTasks(client(tasks), "job-id", tasks(250));

        assertThat(chunkSizes, is(List.of(100, 100, 50)));
    }

    @Test
    void shouldFailWhenATaskIsRejected() throws Exception {
        Tasks tasks = mock(Tasks.class);
        when(tasks.addCollection(eq("job-id"), anyList())).thenReturn(new TaskAddCollectionResult().withValue(List.of(
            new TaskAddResult().withTaskId("task-0").withStatus(TaskAddStatus.SUCCESS),
            new TaskAddResult().withTaskId("task-1").withStatus(TaskAddStatus.CLIENT_ERROR)
                .withError(new BatchError().withCode("TaskExists").withMessage(new ErrorMessage().withValue("The specified task already exists.")))
        )));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> TaskService.submitTasks(client(tasks), "job-id", tasks(2)));

        assertThat(e.getMessage(), containsString("'task-1'"));
        assertThat(e.getMessage(), containsString("TaskExists: The specified task already exists."));
    }

    @Test
    void shouldSplitChunkWhenRequestBodyIsTooLarge() throws Exception {
        Tasks tasks = mock(Tasks.class);
        List<Integer> chunkSizes = new ArrayList<>();
        doAnswer(invocation -> {
            List<TaskAddParameter> chunk = invocation.getArgument(1);
            chunkSizes.add(chunk.size());
            if (chunk.size() > 25) {
                throw new BatchErrorException("too large", null, new BatchError().withCode(BatchErrorCodeStrings.RequestBodyTooLarge));
            }
            return success(chunk);
        }).when(tasks).addCollection(eq("job-id"), anyList());

        TaskService.submitTasks(client(tasks), "job-id", tasks(100));

        assertThat(chunkSizes, is(List.of(100, 50, 25, 25, 50, 25, 25)));
    }

    @Test
    void shouldPropagateBatchErrors() throws Exception {
        Tasks tasks = mock(Tasks.class);
        when(tasks.addCollection(eq("job-id"), anyList()))
            .thenThrow(new BatchErrorException("throttled", null, new BatchError().withCode("TooManyRequests")));

        assertThrows(BatchErrorException.class, () -> TaskService.submitTasks(client(tasks), "job-id", tasks(1)));
    }

    private static BatchClient client(Tasks tasks) {
        BatchClient client = mock(BatchClient.class);
        BatchServiceClient protocolLayer = mock(BatchServiceClient.class);
        when(client.protocolLayer()).thenReturn(protocolLayer);
        when(protocolLayer.tasks()).thenReturn(tasks);
        return client;
    }

    private static List<TaskAddParameter> tasks(int count) {
        return IntStream.range(0, count).mapToObj(i -> new TaskAddParameter().withId("task-" + i)).toList();
    }

    private static TaskAddCollectionResult success(List<TaskAddParameter> chunk) {
        return new TaskAddCollectionResult().withValue(chunk.stream()
            .map(task -> new TaskAddResult().withTaskId(task.id()).withStatus(TaskAddStatus.SUCCESS))
            .toList());
    }
}
