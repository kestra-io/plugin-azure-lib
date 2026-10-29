package io.kestra.plugin.azure.shared.batch.job;

import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.BatchErrorCodeStrings;
import com.microsoft.azure.batch.DetailLevel;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.TaskAddParameter;
import com.microsoft.azure.batch.protocol.models.TaskAddResult;
import com.microsoft.azure.batch.protocol.models.TaskAddStatus;
import com.microsoft.azure.batch.protocol.models.TaskState;
import io.kestra.core.runners.RunContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import jakarta.annotation.Nullable;

public class TaskService {
    // Batch API limit for a single addtaskcollection call
    static final int MAX_TASKS_PER_REQUEST = 100;

    // Replaces TaskOperations.createTasks, whose worker pool can block forever when a chunk fails (kestra-io/plugin-ee-azure#156)
    public static void submitTasks(BatchClient client, String jobId, List<TaskAddParameter> tasks) throws BatchErrorException {
        for (int from = 0; from < tasks.size(); from += MAX_TASKS_PER_REQUEST) {
            submitChunk(client, jobId, tasks.subList(from, Math.min(from + MAX_TASKS_PER_REQUEST, tasks.size())));
        }
    }

    private static void submitChunk(BatchClient client, String jobId, List<TaskAddParameter> chunk) throws BatchErrorException {
        List<TaskAddResult> results;
        try {
            results = client.protocolLayer().tasks().addCollection(jobId, chunk).value();
        } catch (BatchErrorException e) {
            // same fallback as the SDK: halve the chunk until the body fits
            if (chunk.size() > 1 && e.body() != null && BatchErrorCodeStrings.RequestBodyTooLarge.equals(e.body().code())) {
                int middle = chunk.size() / 2;
                submitChunk(client, jobId, chunk.subList(0, middle));
                submitChunk(client, jobId, chunk.subList(middle, chunk.size()));
                return;
            }
            throw e;
        }

        for (TaskAddResult result : results) {
            if (result.status() != TaskAddStatus.SUCCESS) {
                String error = result.error() == null ? "unknown error" : result.error().code() +
                    (result.error().message() == null ? "" : ": " + result.error().message().value());
                throw new IllegalStateException("Failed to add task '" + result.taskId() + "' to job '" + jobId + "': " + error);
            }
        }
    }

    public static List<CloudTask> waitForTasksToComplete(
        RunContext runContext,
        BatchClient client,
        String jobId,
        @Nullable Duration timeout,
        Duration completionCheckInterval
    ) throws BatchErrorException, IOException, InterruptedException, TimeoutException {
        long startTime = System.currentTimeMillis();
        long elapsedTime = 0L;
        ArrayList<String> ended = new ArrayList<>();

        while (timeout == null || elapsedTime < timeout.toMillis()) {
            List<CloudTask> taskCollection = client
                .taskOperations()
                .listTasks(
                    jobId,
                    new DetailLevel.Builder().withSelectClause("id, state").build()
                );

            boolean allComplete = true;
            for (CloudTask task : taskCollection) {
                if (task.executionInfo() != null && task.executionInfo().endTime() != null && !ended.contains(task.id())) {
                    ended.add(task.id());

                    if (task.executionInfo().failureInfo() != null) {
                        runContext.logger().warn("Task '{}' failed with exit code '{}': {}", task.id(), task.executionInfo().exitCode(), task.executionInfo().failureInfo().message());
                    } else {
                        runContext.logger().info("Task ended '{}' with exit code '{}'", task.id(), task.executionInfo().exitCode());
                    }
                }

                if (task.state() != TaskState.COMPLETED) {
                    allComplete = false;
                    break;
                }
            }

            if (allComplete) {
                runContext.logger().info("{} tasks completed", taskCollection.size());
                return taskCollection;
            }

            TimeUnit.MILLISECONDS.sleep(completionCheckInterval.toMillis());
            elapsedTime = (new Date()).getTime() - startTime;
        }

        throw new TimeoutException("Task did not complete within the specified timeout");
    }

    public static File readRemoteFile(
        RunContext runContext,
        BatchClient client,
        String jobId,
        CloudTask task,
        String remoteFileName,
        Boolean copy
    ) throws IOException {
        return readRemoteFile(runContext, client, jobId, task, remoteFileName, null, copy);
    }

    public static File readRemoteFile(
        RunContext runContext,
        BatchClient client,
        String jobId,
        CloudTask task,
        String remoteFileName,
        String localFileName,
        Boolean copy
    ) throws IOException {
        File file = localFileName == null
            ? runContext.workingDir().createTempFile().toFile()
            : runContext.workingDir().resolve(Path.of(localFileName)).toFile();

        FileUtils.createParentDirectories(file);

        FileOutputStream fileOutputStream = new FileOutputStream(file);
        client.fileOperations().getFileFromTask(jobId, task.id(), remoteFileName, fileOutputStream);

        if (copy) {
            fileOutputStream.flush();
        }

        return file;
    }

    public static void readRemoteLog(
        RunContext runContext,
        BatchClient client,
        String jobId,
        CloudTask task,
        String fileName,
        Consumer<String> consumer
    ) throws IOException {
        File file = TaskService.readRemoteFile(runContext, client, jobId, task, fileName, false);

        IOUtils.lineIterator(new FileInputStream(file), StandardCharsets.UTF_8)
            .forEachRemaining(consumer);
    }
}
