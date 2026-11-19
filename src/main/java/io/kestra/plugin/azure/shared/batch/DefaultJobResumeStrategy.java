package io.kestra.plugin.azure.shared.batch;

import com.azure.compute.batch.models.BatchJob;
import com.microsoft.azure.batch.protocol.models.CloudJob;

import java.util.List;
import java.util.Optional;

/**
 * Default job resume strategy: returns the first matching candidate.
 * Used by the OSS plugin; EE plugins can supply their own implementation.
 */
public class DefaultJobResumeStrategy implements JobResumeStrategy {
    @Deprecated
    @Override
    public Optional<CloudJob> selectJob(List<CloudJob> candidates) {
        return candidates.stream().findFirst();
    }

    @Override
    public Optional<BatchJob> selectBatchJob(List<BatchJob> candidates) {
        return candidates.stream().findFirst();
    }
}
