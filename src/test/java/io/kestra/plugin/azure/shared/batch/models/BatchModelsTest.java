package io.kestra.plugin.azure.shared.batch.models;

import com.microsoft.azure.batch.protocol.models.ContainerWorkingDirectory;
import com.microsoft.azure.batch.protocol.models.OutputFileUploadCondition;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@MicronautTest
class BatchModelsTest {
    @Inject
    RunContextFactory runContextFactory;

    @Test
    @SuppressWarnings("deprecation")
    void taskToBatchMatchesTheLegacyConversion() throws Exception {
        var runContext = runContextFactory.of(Map.of("name", "world", "image", "python:3.12"));
        var identity = ComputeNodeIdentityReference.builder().resourceId(Property.ofValue("/identities/mine")).build();
        var task = Task.builder()
            .id("{{ 'task' }}-1")
            .displayName("Task {{ name }}")
            .interpreter(Property.ofValue("/bin/bash"))
            .interpreterArgs(new String[]{"-c"})
            .commands(Property.ofValue(List.of("echo \"hello {{ name }}\"", "echo done")))
            .environments(Property.ofValue(Map.of("GREETING", "hi {{ name }}")))
            .requiredSlots(Property.ofValue(2))
            .containerSettings(TaskContainerSettings.builder()
                .imageName(Property.ofExpression("{{ image }}"))
                .containerRunOptions(Property.ofValue("--rm"))
                .workingDirectory(Property.ofValue(ContainerWorkingDirectory.CONTAINER_IMAGE_DEFAULT))
                .registry(ContainerRegistry.builder()
                    .registryServer(Property.ofValue("myregistry.azurecr.io"))
                    .userName(Property.ofValue("user"))
                    .password(Property.ofValue("secret"))
                    .identityReference(identity)
                    .build())
                .build())
            .resourceFiles(List.of(ResourceFile.builder()
                .httpUrl(Property.ofValue("https://example.com/in.txt"))
                .filePath(Property.ofValue("files/in.txt"))
                .fileMode(Property.ofValue("0644"))
                .identityReference(identity)
                .build()))
            .uploadFiles(List.of(OutputFile.builder()
                .filePattern(Property.ofValue("outs/**"))
                .destination(OutputFileDestination.builder()
                    .container(OutputFileBlobContainerDestination.builder()
                        .containerUrl(Property.ofValue("https://account.blob.core.windows.net/out"))
                        .path(Property.ofValue("results"))
                        .build())
                    .build())
                .uploadOptions(OutputFileUploadOptions.builder()
                    .uploadCondition(Property.ofValue(OutputFileUploadCondition.TASK_SUCCESS))
                    .build())
                .build()))
            .constraints(TaskConstraints.builder()
                .maxWallClockTime(Property.ofValue(Duration.ofHours(2)))
                .retentionTime(Property.ofValue(Duration.ofDays(1)))
                .maxTaskRetryCount(Property.ofValue(3))
                .build())
            .build();

        var legacy = task.to(runContext);
        var batch = task.toBatch(runContext);

        assertThat(batch.getId(), is("task-1"));
        assertThat(batch.getId(), is(legacy.id()));
        assertThat(batch.getDisplayName(), is("Task world"));
        assertThat(batch.getCommandLine(), is(legacy.commandLine()));
        assertThat(batch.getRequiredSlots(), is(2));

        assertThat(batch.getEnvironmentSettings().getFirst().getName(), is("GREETING"));
        assertThat(batch.getEnvironmentSettings().getFirst().getValue(), is("hi world"));

        var container = batch.getContainerSettings();
        assertThat(container.getImageName(), is("python:3.12"));
        assertThat(container.getContainerRunOptions(), is("--rm"));
        assertThat(container.getWorkingDirectory(), is(com.azure.compute.batch.models.ContainerWorkingDirectory.CONTAINER_IMAGE_DEFAULT));
        assertThat(container.getRegistry().getRegistryServer(), is("myregistry.azurecr.io"));
        assertThat(container.getRegistry().getUsername(), is("user"));
        assertThat(container.getRegistry().getPassword(), is("secret"));
        assertThat(container.getRegistry().getIdentityReference().getResourceId(), is("/identities/mine"));

        var resourceFile = batch.getResourceFiles().getFirst();
        assertThat(resourceFile.getHttpUrl(), is("https://example.com/in.txt"));
        assertThat(resourceFile.getFilePath(), is("files/in.txt"));
        assertThat(resourceFile.getFileMode(), is("0644"));
        assertThat(resourceFile.getIdentityReference().getResourceId(), is("/identities/mine"));

        var outputFile = batch.getOutputFiles().getFirst();
        assertThat(outputFile.getFilePattern(), is("outs/**"));
        assertThat(outputFile.getDestination().getContainer().getContainerUrl(), is("https://account.blob.core.windows.net/out"));
        assertThat(outputFile.getDestination().getContainer().getPath(), is("results"));
        assertThat(outputFile.getUploadOptions().getUploadCondition(), is(com.azure.compute.batch.models.OutputFileUploadCondition.TASK_SUCCESS));

        assertThat(batch.getConstraints().getMaxWallClockTime(), is(Duration.ofHours(2)));
        assertThat(batch.getConstraints().getRetentionTime(), is(Duration.ofDays(1)));
        assertThat(batch.getConstraints().getMaxTaskRetryCount(), is(3));
    }

    @Test
    void minimalTaskToBatchLeavesOptionalSettingsUnset() throws Exception {
        var runContext = runContextFactory.of(Map.of());
        var task = Task.builder()
            .id("minimal")
            .commands(Property.ofValue(List.of("echo hi")))
            .build();

        var batch = task.toBatch(runContext);

        assertThat(batch.getCommandLine(), is("/bin/sh -c \"echo hi\""));
        assertThat(batch.getContainerSettings(), nullValue());
        assertThat(batch.getEnvironmentSettings(), nullValue());
        assertThat(batch.getResourceFiles(), nullValue());
        assertThat(batch.getOutputFiles(), nullValue());
        assertThat(batch.getConstraints(), nullValue());
        assertThat(batch.getRequiredSlots(), nullValue());
    }

    @Test
    void jobToBatchSetsPoolAndLabels() throws Exception {
        var runContext = runContextFactory.of(Map.of("suffix", "42"));
        var job = Job.builder()
            .id("job-{{ suffix }}")
            .displayName("My job")
            .priority(Property.ofValue(100))
            .maxParallelTasks(Property.ofValue(4))
            .labels(Property.ofValue(Map.of("team", "data")))
            .build();

        var batch = job.toBatch(runContext, "my-pool");

        assertThat(batch.getId(), is("job-42"));
        assertThat(batch.getDisplayName(), is("My job"));
        assertThat(batch.getPriority(), is(100));
        assertThat(batch.getMaxParallelTasks(), is(4));
        assertThat(batch.getPoolInfo().getPoolId(), is("my-pool"));
        assertThat(batch.getMetadata().getFirst().getName(), is("team"));
        assertThat(batch.getMetadata().getFirst().getValue(), is("data"));
    }

    @ParameterizedTest
    @EnumSource(OutputFileUploadCondition.class)
    void mapsEveryUploadCondition(OutputFileUploadCondition condition) throws Exception {
        var options = OutputFileUploadOptions.builder().uploadCondition(Property.ofValue(condition)).build();

        var batch = options.toBatch(runContextFactory.of(Map.of()));

        assertThat(batch.getUploadCondition().toString(), is(condition.toString()));
    }

    @ParameterizedTest
    @EnumSource(ContainerWorkingDirectory.class)
    void mapsEveryContainerWorkingDirectory(ContainerWorkingDirectory workingDirectory) throws Exception {
        var settings = TaskContainerSettings.builder()
            .imageName(Property.ofValue("alpine"))
            .workingDirectory(Property.ofValue(workingDirectory))
            .build();

        var batch = settings.toBatch(runContextFactory.of(Map.of()));

        assertThat(batch.getWorkingDirectory().toString(), is(workingDirectory.toString()));
    }
}
