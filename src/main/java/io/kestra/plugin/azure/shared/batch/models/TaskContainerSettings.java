package io.kestra.plugin.azure.shared.batch.models;

import com.microsoft.azure.batch.protocol.models.ContainerWorkingDirectory;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import jakarta.validation.constraints.NotNull;

@Builder
@Value
public class TaskContainerSettings {
    @Schema(
        title = "Container run options",
        description = "Extra arguments passed to docker create alongside Batch-managed options"
    )
    Property<String> containerRunOptions;

    @Schema(
        title = "Container image",
        description = "Full image reference (e.g. repo/name:tag); defaults to :latest if no tag provided"
    )
    @NotNull
    Property<String> imageName;

    @Schema(
        title = "Container registry",
        description = "Registry credentials; omit when provided at pool creation"
    )
    ContainerRegistry registry;

    @Schema(
        title = "Working directory scope",
        description = "Where the container starts; defaults to TASK_WORKING_DIRECTORY. Options: TASK_WORKING_DIRECTORY or CONTAINER_IMAGE_DEFAULT."
    )
    Property<ContainerWorkingDirectory> workingDirectory;

    /**
     * @deprecated legacy {@code com.microsoft.azure:azure-batch} type, use {@link #toBatch} instead.
     */
    @Deprecated
    public com.microsoft.azure.batch.protocol.models.TaskContainerSettings to(RunContext runContext) throws IllegalVariableEvaluationException {
        return new com.microsoft.azure.batch.protocol.models.TaskContainerSettings()
            .withContainerRunOptions(runContext.render(this.containerRunOptions).as(String.class).orElse(null))
            .withImageName(runContext.render(this.imageName).as(String.class).orElseThrow())
            .withRegistry(this.registry != null ? this.registry.to(runContext) : null)
            .withWorkingDirectory(runContext.render(this.workingDirectory).as(ContainerWorkingDirectory.class).orElse(null));
    }

    public com.azure.compute.batch.models.BatchTaskContainerSettings toBatch(RunContext runContext) throws IllegalVariableEvaluationException {
        var workingDirectory = runContext.render(this.workingDirectory).as(ContainerWorkingDirectory.class)
            .map(value -> switch (value) {
                case TASK_WORKING_DIRECTORY -> com.azure.compute.batch.models.ContainerWorkingDirectory.TASK_WORKING_DIRECTORY;
                case CONTAINER_IMAGE_DEFAULT -> com.azure.compute.batch.models.ContainerWorkingDirectory.CONTAINER_IMAGE_DEFAULT;
            })
            .orElse(null);
        return new com.azure.compute.batch.models.BatchTaskContainerSettings(runContext.render(this.imageName).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Container 'imageName' is required, set it to the image to run the task in")))
            .setContainerRunOptions(runContext.render(this.containerRunOptions).as(String.class).orElse(null))
            .setRegistry(this.registry != null ? this.registry.toBatch(runContext) : null)
            .setWorkingDirectory(workingDirectory);
    }
}
