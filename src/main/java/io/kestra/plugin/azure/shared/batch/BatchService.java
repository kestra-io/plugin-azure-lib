package io.kestra.plugin.azure.shared.batch;

import com.microsoft.azure.AzureResponseBuilder;
import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.DetailLevel;
import com.microsoft.azure.batch.auth.BatchSharedKeyCredentials;
import com.microsoft.azure.batch.protocol.models.CloudJob;
import com.microsoft.azure.batch.protocol.models.MetadataItem;
import com.microsoft.azure.serializer.AzureJacksonAdapter;
import com.microsoft.rest.RestClient;
import com.microsoft.rest.RestException;
import com.microsoft.rest.protocol.ResponseBuilder;
import com.microsoft.rest.protocol.SerializerAdapter;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.runners.RunContext;

import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class BatchService {
    public static BatchClient client(Property<String> endpoint, Property<String> account, Property<String> accessKey, RunContext runContext) throws IllegalVariableEvaluationException {
        var credentials = new BatchSharedKeyCredentials(
                runContext.render(endpoint).as(String.class).orElseThrow(),
                runContext.render(account).as(String.class).orElseThrow(),
                runContext.render(accessKey).as(String.class).orElseThrow()
        );
        var restClient = new RestClient.Builder()
            .withBaseUrl(credentials.baseUrl())
            .withCredentials(credentials)
            .withSerializerAdapter(new AzureJacksonAdapter())
            .withResponseBuilderFactory(new BatchResponseBuilderFactory())
            .withRetryStrategy(BatchRetryInterceptor.DISABLED_SDK_RETRIES)
            .withInterceptor(new BatchRetryInterceptor())
            .build();
        return BatchClient.open(restClient, credentials.baseUrl());
    }

    private static final String ATTEMPT_LABEL = "kestra-taskrun-attempt";

    /**
     * Finds the job of an earlier attempt of the current task run, so a retried task can resume it.
     * The job keeps the id the user gave it, so that id is looked up first. Jobs created by the task runner
     * get a new random suffix on each attempt, so the generated job name prefix is looked up next.
     */
    public static Optional<CloudJob> findResumableJob(RunContext runContext, BatchClient client, String jobId) throws IOException {
        var cloudJob = getExistingJob(runContext, client, jobId);
        if (cloudJob.isPresent()) {
            return cloudJob;
        }

        var jobName = ScriptService.jobName(runContext);
        return getExistingJob(runContext, client, jobName.substring(0, jobName.lastIndexOf('-') + 1));
    }

    public static Optional<CloudJob> getExistingJob(RunContext runContext, BatchClient client, String baseJobName) throws IOException {
        var resumeLabels = new HashMap<>(ScriptService.labels(runContext, "kestra-", true, true));
        // a resumed task runs under a new attempt, so a job created by an earlier attempt must still match
        resumeLabels.remove(ATTEMPT_LABEL);
        var listJobsResponse = client.jobOperations().listJobs(new DetailLevel.Builder().withFilterClause("startswith(id, '" + baseJobName + "')").build());
        return listJobsResponse.stream()
            .filter(cloudJob -> hasAllLabels(resumeLabels, cloudJob.metadata()))
            .min((cloudJob1, cloudJob2) -> -cloudJob1.creationTime().compareTo(cloudJob2.creationTime()));
    }

    private static boolean hasAllLabels(Map<String, String> resumeLabels, List<MetadataItem> metadata) {
        if (metadata == null) {
            return false;
        }
        var jobLabels = new HashMap<String, String>();
        metadata.forEach(item -> jobLabels.put(item.name(), item.value()));
        return resumeLabels.entrySet().stream().allMatch(label -> label.getValue().equals(jobLabels.get(label.getKey())));
    }

    private static final class BatchResponseBuilderFactory implements ResponseBuilder.Factory {
        private final AzureResponseBuilder.Factory delegate = new AzureResponseBuilder.Factory();

        @Override
        public <T, E extends RestException> ResponseBuilder<T, E> newInstance(SerializerAdapter<?> serializerAdapter) {
            // Match BatchClient.open(credentials), which throws rather than returning null for GET 404.
            return delegate.<T, E>newInstance(serializerAdapter).withThrowOnGet404(true);
        }
    }
}
