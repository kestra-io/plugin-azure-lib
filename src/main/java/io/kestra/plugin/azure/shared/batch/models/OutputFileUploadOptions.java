package io.kestra.plugin.azure.shared.batch.models;

import com.microsoft.azure.batch.protocol.models.OutputFileUploadCondition;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.NotNull;

@Builder
@Value
@Jacksonized
public class OutputFileUploadOptions {
    @Schema(
        title = "Upload condition",
        description = "When to upload files (e.g., TASK_COMPLETION, TASK_SUCCESS); defaults to TASK_COMPLETION"
    )
    @NotNull
    @Builder.Default
    Property<OutputFileUploadCondition> uploadCondition = Property.ofValue(OutputFileUploadCondition.TASK_COMPLETION);

    /**
     * @deprecated legacy {@code com.microsoft.azure:azure-batch} type, use {@link #toBatch} instead.
     */
    @Deprecated
    public com.microsoft.azure.batch.protocol.models.OutputFileUploadOptions to(RunContext runContext) throws IllegalVariableEvaluationException {
        return new com.microsoft.azure.batch.protocol.models.OutputFileUploadOptions()
            .withUploadCondition(runContext.render(this.uploadCondition).as(OutputFileUploadCondition.class).orElseThrow());
    }

    public com.azure.compute.batch.models.OutputFileUploadConfig toBatch(RunContext runContext) throws IllegalVariableEvaluationException {
        var condition = switch (runContext.render(this.uploadCondition).as(OutputFileUploadCondition.class).orElse(OutputFileUploadCondition.TASK_COMPLETION)) {
            case TASK_SUCCESS -> com.azure.compute.batch.models.OutputFileUploadCondition.TASK_SUCCESS;
            case TASK_FAILURE -> com.azure.compute.batch.models.OutputFileUploadCondition.TASK_FAILURE;
            case TASK_COMPLETION -> com.azure.compute.batch.models.OutputFileUploadCondition.TASK_COMPLETION;
        };
        return new com.azure.compute.batch.models.OutputFileUploadConfig(condition);
    }
}
