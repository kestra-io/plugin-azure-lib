package io.kestra.plugin.azure.shared.batch;

import com.azure.compute.batch.models.BatchJob;
import com.microsoft.azure.batch.protocol.models.CloudJob;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for selecting an existing job to resume when multiple candidates match the job name prefix and labels.
 * OSS uses the default (first match), EE implementations can override this with more specific selection logic.
 */
@FunctionalInterface
public interface JobResumeStrategy {
    /**
     * @deprecated legacy {@code com.microsoft.azure:azure-batch} type, implement {@link #selectBatchJob(List)} instead.
     */
    @Deprecated
    Optional<CloudJob> selectJob(List<CloudJob> candidates);

    /**
     * Selects the job to resume among {@code com.azure:azure-compute-batch} candidates. Defaults to the first one.
     */
    default Optional<BatchJob> selectBatchJob(List<BatchJob> candidates) {
        return candidates.stream().findFirst();
    }
}
