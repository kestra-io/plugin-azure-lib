package io.kestra.plugin.azure.shared.batch;

import com.microsoft.azure.batch.protocol.models.CloudJob;

import java.util.List;
import java.util.Optional;

/**
 * Default job resume strategy: returns the first matching candidate.
 * Used by the OSS plugin; EE plugins can supply their own implementation.
 */
public class DefaultJobResumeStrategy implements JobResumeStrategy {
    @Override
    public Optional<CloudJob> selectJob(List<CloudJob> candidates) {
        return candidates.stream().findFirst();
    }
}
