package io.kestra.plugin.azure.shared.batch;

import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.protocol.models.PoolInformation;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.azure.shared.batch.models.Job;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class BatchServiceResumeTest {
    private static final String BASE_JOB_NAME = "company-team-my-flow-create-";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void resumesTheJobOfThePreviousAttempt() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "a", "2026-01-01T00:00:00Z", labels(runContext, Map.of("kestra-taskrun-attempt", "0"))));

            var job = BatchService.getExistingJob(runContext, client(endpoint, runContext), BASE_JOB_NAME);

            assertEquals(BASE_JOB_NAME + "a", job.orElseThrow().id());
            assertTrue(endpoint.queries.getFirst().contains("startswith(id, '" + BASE_JOB_NAME + "')"));
        }
    }

    @Test
    void resumesAJobCreatedByTheJobModelOnAnEarlierAttempt() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var firstAttempt = runContext(endpoint, 1);
            var created = Job.builder()
                .id(BASE_JOB_NAME + "a")
                .labels(Property.ofValue(Map.of("team", "data", "kestra-taskrun-id", "overridden")))
                .build()
                .to(firstAttempt, new PoolInformation().withPoolId("pool"));
            var metadata = new LinkedHashMap<String, String>();
            created.metadata().forEach(item -> metadata.put(item.name(), item.value()));
            endpoint.jobs.add(job(created.id(), "2026-01-01T00:00:00Z", metadata));

            var retry = runContext(endpoint, 2);
            var job = BatchService.getExistingJob(retry, client(endpoint, retry), BASE_JOB_NAME);

            assertEquals(BASE_JOB_NAME + "a", job.orElseThrow().id());
            assertEquals("data", metadata.get("team"));
            assertEquals("taskrun", metadata.get("kestra-taskrun-id"));
            assertNotEquals(labels(retry, Map.of()).get("kestra-taskrun-attempt"), metadata.get("kestra-taskrun-attempt"));
        }
    }

    @Test
    void resumesAJobCarryingExtraMetadata() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "a", "2026-01-01T00:00:00Z", labels(runContext, Map.of("team", "data"))));

            var job = BatchService.getExistingJob(runContext, client(endpoint, runContext), BASE_JOB_NAME);

            assertEquals(BASE_JOB_NAME + "a", job.orElseThrow().id());
        }
    }

    @Test
    void resumesTheNewestMatchingJob() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "old", "2026-01-01T00:00:00Z", labels(runContext, Map.of())));
            endpoint.jobs.add(job(BASE_JOB_NAME + "new", "2026-01-02T00:00:00Z", labels(runContext, Map.of())));

            var job = BatchService.getExistingJob(runContext, client(endpoint, runContext), BASE_JOB_NAME);

            assertEquals(BASE_JOB_NAME + "new", job.orElseThrow().id());
        }
    }

    @Test
    void ignoresJobsOfAnotherTaskRunOrWithoutLabels() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "other", "2026-01-01T00:00:00Z", labels(runContext, Map.of("kestra-taskrun-id", "another-taskrun"))));
            endpoint.jobs.add(job(BASE_JOB_NAME + "partial", "2026-01-01T00:00:00Z", Map.of("kestra-taskrun-id", "taskrun")));
            endpoint.jobs.add(job(BASE_JOB_NAME + "bare", "2026-01-01T00:00:00Z", null));

            var job = BatchService.getExistingJob(runContext, client(endpoint, runContext), BASE_JOB_NAME);

            assertTrue(job.isEmpty());
        }
    }

    private RunContext runContext(FakeJobList endpoint) {
        return runContext(endpoint, 1);
    }

    private RunContext runContext(FakeJobList endpoint, int attemptsCount) {
        return runContextFactory.of(Map.of(
            "endpoint", endpoint.url(),
            "account", "test-account",
            "accessKey", "dGVzdC1rZXk=",
            "flow", Map.of("namespace", "company.team", "id", "my-flow"),
            "task", Map.of("id", "create"),
            "execution", Map.of("id", "exec"),
            "taskrun", Map.of("id", "taskrun", "attemptsCount", attemptsCount)
        ));
    }

    private static BatchClient client(FakeJobList endpoint, RunContext runContext) throws Exception {
        endpoint.client = BatchService.client(
            Property.ofExpression("{{ endpoint }}"),
            Property.ofExpression("{{ account }}"),
            Property.ofExpression("{{ accessKey }}"),
            runContext
        );
        return endpoint.client;
    }

    private static Map<String, String> labels(RunContext runContext, Map<String, String> overrides) {
        var labels = new HashMap<>(ScriptService.labels(runContext, "kestra-", true, true));
        labels.putAll(overrides);
        return labels;
    }

    private static Map<String, Object> job(String id, String creationTime, Map<String, String> metadata) {
        var job = new HashMap<String, Object>();
        job.put("id", id);
        job.put("creationTime", creationTime);
        if (metadata != null) {
            job.put("metadata", metadata.entrySet().stream()
                .map(entry -> Map.of("name", entry.getKey(), "value", entry.getValue()))
                .toList());
        }
        return job;
    }

    private static final class FakeJobList implements AutoCloseable {
        private final HttpServer server;
        private final List<Map<String, Object>> jobs = new CopyOnWriteArrayList<>();
        private final List<String> queries = new CopyOnWriteArrayList<>();
        private BatchClient client;

        private FakeJobList() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jobs", exchange -> {
                var query = exchange.getRequestURI().getRawQuery();
                queries.add(query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8));
                var body = JacksonMapper.ofJson().writeValueAsBytes(Map.of("value", jobs));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            if (client != null) {
                client.protocolLayer().restClient().close();
            }
            server.stop(0);
        }
    }
}
