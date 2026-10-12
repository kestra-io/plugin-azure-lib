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

    public static Optional<CloudJob> getExistingJob(RunContext runContext, BatchClient client, String baseJobName) throws IOException {
        var listJobsResponse = client.jobOperations().listJobs(new DetailLevel.Builder().withFilterClause("startswith(id, '" + baseJobName + "')").build());
        return listJobsResponse.stream()
            .filter(cloudJob -> hasAllLabels(runContext, cloudJob.metadata()))
            .min((cloudJob1, cloudJob2) -> -cloudJob1.creationTime().compareTo(cloudJob2.creationTime()));
    }

    private static boolean hasAllLabels(RunContext runContext, List<MetadataItem> metadata) {
        Map<String, String> labels = ScriptService.labels(runContext, "kestra-", true, true);
        var resumeLabel = new HashMap<>(labels);
        resumeLabel.remove("kestra-taskrun-attempt"); // job resubmission will create a new attempt
        for (MetadataItem metadataItem : metadata) {
            String value = resumeLabel.get(metadataItem.name());
            if (value == null || !value.equals(metadataItem.value())) {
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
