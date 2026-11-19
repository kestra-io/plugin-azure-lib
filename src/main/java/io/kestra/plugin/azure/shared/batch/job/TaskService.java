package io.kestra.plugin.azure.shared.batch.job;

import com.azure.compute.batch.models.BatchTask;
import com.azure.compute.batch.models.BatchTaskState;
import com.azure.compute.batch.models.BatchTasksListOptions;
import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.DetailLevel;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.CloudTask;
import com.microsoft.azure.batch.protocol.models.TaskState;
import io.kestra.core.runners.RunContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import jakarta.annotation.Nullable;

public class TaskService {
    /**
     * @deprecated use {@link #waitForTasksToComplete(RunContext, com.azure.compute.batch.BatchClient, String, Duration, Duration)}.
     */
    @Deprecated
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

    /**
     * Polls the tasks of a job until all of them are completed, logging each task once when it ends.
     *
     * @param timeout maximum time to wait, or {@code null} to wait indefinitely
     * @return the tasks of the job, with their id, state and execution info
     */
    public static List<BatchTask> waitForTasksToComplete(
        RunContext runContext,
        com.azure.compute.batch.BatchClient client,
        String jobId,
        @Nullable Duration timeout,
        Duration completionCheckInterval
    ) throws InterruptedException, TimeoutException {
        var startTime = System.currentTimeMillis();
        var elapsedTime = 0L;
        var ended = new HashSet<String>();
        var options = new BatchTasksListOptions().setSelect(List.of("id", "state", "executionInfo"));

        while (timeout == null || elapsedTime < timeout.toMillis()) {
            var taskCollection = client.listTasks(jobId, options).stream().toList();

            boolean allComplete = true;
            for (var task : taskCollection) {
                var executionInfo = task.getExecutionInfo();
                if (executionInfo != null && executionInfo.getEndTime() != null && ended.add(task.getId())) {
                    if (executionInfo.getFailureInfo() != null) {
                        runContext.logger().warn("Task '{}' failed with exit code '{}': {}", task.getId(), executionInfo.getExitCode(), executionInfo.getFailureInfo().getMessage());
                    } else {
                        runContext.logger().info("Task ended '{}' with exit code '{}'", task.getId(), executionInfo.getExitCode());
                    }
                }

                if (!BatchTaskState.COMPLETED.equals(task.getState())) {
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

    /**
     * @deprecated use {@link #readRemoteFile(RunContext, com.azure.compute.batch.BatchClient, String, BatchTask, String, String)}.
     */
    @Deprecated
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

    /**
     * @deprecated use {@link #readRemoteFile(RunContext, com.azure.compute.batch.BatchClient, String, BatchTask, String, String)}.
     */
    @Deprecated
    public static File readRemoteFile(
        RunContext runContext,
        BatchClient client,
        String jobId,
        CloudTask task,
        String remoteFileName,
        String localFileName,
        Boolean copy
    ) throws IOException {
        File file = localFile(runContext, localFileName);

        FileOutputStream fileOutputStream = new FileOutputStream(file);
        client.fileOperations().getFileFromTask(jobId, task.id(), remoteFileName, fileOutputStream);

        if (copy) {
            fileOutputStream.flush();
        }

        return file;
    }

    /**
     * Downloads a file of a task to the working directory.
     *
     * @param localFileName path relative to the working directory, or {@code null} to download to a temporary file
     */
    public static File readRemoteFile(
        RunContext runContext,
        com.azure.compute.batch.BatchClient client,
        String jobId,
        BatchTask task,
        String remoteFileName,
        @Nullable String localFileName
    ) throws IOException {
        var file = localFile(runContext, localFileName);

        try (
            InputStream content = client.getTaskFile(jobId, task.getId(), remoteFileName).toStream();
            FileOutputStream fileOutputStream = new FileOutputStream(file)
        ) {
            content.transferTo(fileOutputStream);
        }

        return file;
    }

    private static File localFile(RunContext runContext, @Nullable String localFileName) throws IOException {
        File file = localFileName == null
            ? runContext.workingDir().createTempFile().toFile()
            : runContext.workingDir().resolve(Path.of(localFileName)).toFile();

        FileUtils.createParentDirectories(file);

        return file;
    }

    /**
     * @deprecated use {@link #readRemoteLog(RunContext, com.azure.compute.batch.BatchClient, String, BatchTask, String, Consumer)}.
     */
    @Deprecated
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

    /**
     * Downloads a log file of a task, such as {@code stdout.txt}, and passes each line to the consumer.
     */
    public static void readRemoteLog(
        RunContext runContext,
        com.azure.compute.batch.BatchClient client,
        String jobId,
        BatchTask task,
        String fileName,
        Consumer<String> consumer
    ) throws IOException {
        var file = TaskService.readRemoteFile(runContext, client, jobId, task, fileName, null);

        try (var lines = IOUtils.lineIterator(new FileInputStream(file), StandardCharsets.UTF_8)) {
            lines.forEachRemaining(consumer);
        }
    }
}
