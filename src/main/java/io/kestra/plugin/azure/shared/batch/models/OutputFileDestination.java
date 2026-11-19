package io.kestra.plugin.azure.shared.batch.models;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import jakarta.validation.constraints.NotNull;

@Builder
@Value
public class OutputFileDestination {
    @Schema(title = "Azure Blob destination")
    @PluginProperty(dynamic = true)
    @NotNull
    OutputFileBlobContainerDestination container;

    /**
     * @deprecated legacy {@code com.microsoft.azure:azure-batch} type, use {@link #toBatch} instead.
     */
    @Deprecated
    public com.microsoft.azure.batch.protocol.models.OutputFileDestination to(RunContext runContext) throws IllegalVariableEvaluationException {
        return new com.microsoft.azure.batch.protocol.models.OutputFileDestination()
            .withContainer(this.container == null ? null : this.container.to(runContext));
    }

    public com.azure.compute.batch.models.OutputFileDestination toBatch(RunContext runContext) throws IllegalVariableEvaluationException {
        return new com.azure.compute.batch.models.OutputFileDestination()
            .setContainer(this.container == null ? null : this.container.toBatch(runContext));
    }
}
