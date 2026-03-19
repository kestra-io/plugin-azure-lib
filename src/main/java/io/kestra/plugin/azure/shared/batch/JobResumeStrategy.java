package io.kestra.plugin.azure.shared.batch;

import com.microsoft.azure.batch.protocol.models.CloudJob;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for selecting an existing job to resume when multiple candidates match the job name prefix and labels.
 * OSS uses the default (first match), EE implementations can override this with more specific selection logic.
 */
@FunctionalInterface
public interface JobResumeStrategy {
    Optional<CloudJob> selectJob(List<CloudJob> candidates);
}
