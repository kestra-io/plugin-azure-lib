package io.kestra.plugin.azure.shared.storage.blob.models;

import com.azure.storage.blob.BlobContainerClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.azure.shared.AbstractConnectionInterface;
import io.kestra.plugin.azure.shared.AzureClientInterface;
import io.kestra.plugin.azure.shared.storage.blob.services.BlobService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public class BlobStorageForBatch implements AzureClientInterface, AbstractConnectionInterface {
    protected Property<String> endpoint;
    protected Property<String> connectionString;
    protected Property<String> sharedKeyAccountName;
    protected Property<String> sharedKeyAccountAccessKey;

    @Schema(
        title = "Name of the blob container used for file staging",
        description = "Mandatory if you want to use the `namespaceFiles`, `inputFiles` or `outputFiles` properties."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> containerName;

    public boolean valid() {
        return this.containerName != null &&
            (
                this.connectionString != null ||
                    (this.endpoint != null && this.sharedKeyAccountName != null && this.sharedKeyAccountAccessKey != null)
            );
    }

    public BlobContainerClient blobContainerClient(RunContext runContext) throws IllegalVariableEvaluationException {
        return BlobService.client(this.endpoint, this.connectionString, this.sharedKeyAccountName, this.sharedKeyAccountAccessKey, null, runContext)
            .getBlobContainerClient(runContext.render(containerName).as(String.class).orElseThrow());
    }
}
