package io.kestra.plugin.azure.shared.batch;

import com.azure.compute.batch.BatchClientBuilder;
import com.azure.compute.batch.models.BatchJob;
import com.azure.compute.batch.models.BatchJobsListOptions;
import com.azure.compute.batch.models.BatchMetadataItem;
import com.azure.core.credential.AzureNamedKeyCredential;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.RetryOptions;
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
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class BatchService {
    // Same retry budget as BatchRetryInterceptor; azure-core retries 408, 429, 500, 502, 503 and 504 and honors Retry-After.
    private static final int MAX_RETRIES = 3;
    private static final String ATTEMPT_LABEL = "kestra-taskrun-attempt";

    /**
     * Creates a client for the legacy {@code com.microsoft.azure:azure-batch} SDK.
     *
     * @deprecated use {@link #batchClient(Property, Property, Property, RunContext)}, based on {@code com.azure:azure-compute-batch}.
     */
    @Deprecated
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

    /**
     * Creates a client for the {@code com.azure:azure-compute-batch} SDK, authenticated with the Batch account shared key.
     */
    public static com.azure.compute.batch.BatchClient batchClient(Property<String> endpoint, Property<String> account, Property<String> accessKey, RunContext runContext) throws IllegalVariableEvaluationException {
        return new BatchClientBuilder()
            .endpoint(runContext.render(endpoint).as(String.class)
                .orElseThrow(() -> new IllegalArgumentException("Batch 'endpoint' is required, set it to the Batch account URL")))
            .credential(new AzureNamedKeyCredential(
                runContext.render(account).as(String.class)
                    .orElseThrow(() -> new IllegalArgumentException("Batch 'account' is required, set it to the Batch account name")),
                runContext.render(accessKey).as(String.class)
                    .orElseThrow(() -> new IllegalArgumentException("Batch 'accessKey' is required, set it to the Batch account access key"))
            ))
            .retryOptions(new RetryOptions(new ExponentialBackoffOptions().setMaxRetries(MAX_RETRIES)))
            .buildClient();
    }

    /**
     * @deprecated use {@link #getExistingBatchJob(RunContext, com.azure.compute.batch.BatchClient, String)}.
     */
    @Deprecated
    public static Optional<CloudJob> getExistingJob(RunContext runContext, BatchClient client, String baseJobName) throws IOException {
        var listJobsResponse = client.jobOperations().listJobs(new DetailLevel.Builder().withFilterClause(startsWithFilter(baseJobName)).build());
        return listJobsResponse.stream()
            .filter(cloudJob -> hasAllLabels(runContext, toMap(cloudJob.metadata(), MetadataItem::name, MetadataItem::value)))
            .min((cloudJob1, cloudJob2) -> -cloudJob1.creationTime().compareTo(cloudJob2.creationTime()));
    }

    /**
     * Returns the most recently created job whose id starts with {@code baseJobName} and whose metadata holds every
     * {@code kestra-*} label of the current task run with the same value. The attempt label is not compared, so a
     * retried task run resumes the job of its previous attempt; other metadata, such as user labels, is ignored.
     */
    public static Optional<BatchJob> getExistingBatchJob(RunContext runContext, com.azure.compute.batch.BatchClient client, String baseJobName) {
        var runLabels = new HashMap<>(ScriptService.labels(runContext, "kestra-", true, true));
        runLabels.remove(ATTEMPT_LABEL);

        return client.listJobs(new BatchJobsListOptions().setFilter(startsWithFilter(baseJobName)))
            .stream()
            .filter(batchJob -> hasRunLabels(runLabels, toMap(batchJob.getMetadata(), BatchMetadataItem::getName, BatchMetadataItem::getValue)))
            .max(Comparator.comparing(BatchJob::getCreationTime, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static boolean hasRunLabels(Map<String, String> runLabels, Map<String, String> metadata) {
        return runLabels.entrySet().stream()
            .allMatch(label -> label.getValue().equals(metadata.get(label.getKey())));
    }

    private static String startsWithFilter(String baseJobName) {
        return "startswith(id, '" + baseJobName + "')";
    }

    private static <T> Map<String, String> toMap(List<T> metadata, Function<T, String> name, Function<T, String> value) {
        var map = new HashMap<String, String>();
        if (metadata != null) {
            metadata.forEach(item -> map.put(name.apply(item), value.apply(item)));
        }
        return map;
    }

    private static boolean hasAllLabels(RunContext runContext, Map<String, String> metadata) {
        Map<String, String> labels = ScriptService.labels(runContext, "kestra-", true, true);
        var resumeLabel = new HashMap<>(labels);
        resumeLabel.remove(ATTEMPT_LABEL); // job resubmission will create a new attempt
        for (Map.Entry<String, String> metadataItem : metadata.entrySet()) {
            String value = resumeLabel.get(metadataItem.getKey());
            if (value == null || !value.equals(metadataItem.getValue())) {
                return false;
            }
        }
        return true;
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
