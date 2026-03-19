package io.kestra.plugin.azure.shared.storage.blob.abstracts;

import com.azure.storage.blob.BlobServiceClient;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.azure.shared.storage.abstracts.AbstractStorage;
import io.kestra.plugin.azure.shared.storage.blob.services.BlobService;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractBlobStorage extends AbstractStorage {
    public BlobServiceClient client(RunContext runContext) throws IllegalVariableEvaluationException {
        return BlobService.client(this.endpoint, this.connectionString, this.sharedKeyAccountName, this.sharedKeyAccountAccessKey, null, runContext);
    }
}
