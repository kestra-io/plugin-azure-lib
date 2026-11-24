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

    @Test
    void resumesAnActiveJobWithoutTasksOrWithARunningTask() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "a", "2026-01-01T00:00:00Z", labels(runContext, Map.of())));

            assertEquals(BASE_JOB_NAME + "a", resumable(endpoint, runContext).orElseThrow().id());

            endpoint.tasks.put(BASE_JOB_NAME + "a", List.of(task("running", null)));
            assertEquals(BASE_JOB_NAME + "a", resumable(endpoint, runContext).orElseThrow().id());

            endpoint.tasks.put(BASE_JOB_NAME + "a", List.of(task("completed", "success")));
            assertEquals(BASE_JOB_NAME + "a", resumable(endpoint, runContext).orElseThrow().id());
        }
    }

    @Test
    void doesNotResumeAJobThatIsNotActive() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            for (var state : List.of("completed", "terminating", "deleting")) {
                var job = job(BASE_JOB_NAME + state, "2026-01-01T00:00:00Z", labels(runContext, Map.of()));
                job.put("state", state);
                endpoint.jobs.add(job);
            }

            assertTrue(resumable(endpoint, runContext).isEmpty());
            // still visible to the plain lookup
            assertTrue(BatchService.getExistingJob(runContext, endpoint.client, BASE_JOB_NAME).isPresent());
        }
    }

    @Test
    void doesNotResumeAnActiveJobWhoseTasksAllCompletedWithAFailure() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "failed", "2026-01-02T00:00:00Z", labels(runContext, Map.of())));
            endpoint.tasks.put(BASE_JOB_NAME + "failed", List.of(task("completed", "success"), task("completed", "failure")));

            assertTrue(resumable(endpoint, runContext).isEmpty());

            // a task still running means the job is not finished yet
            endpoint.tasks.put(BASE_JOB_NAME + "failed", List.of(task("running", null), task("completed", "failure")));
            assertEquals(BASE_JOB_NAME + "failed", resumable(endpoint, runContext).orElseThrow().id());
        }
    }

    @Test
    void resumesTheNewestJobThatCanStillRun() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            endpoint.jobs.add(job(BASE_JOB_NAME + "old", "2026-01-01T00:00:00Z", labels(runContext, Map.of())));
            endpoint.jobs.add(job(BASE_JOB_NAME + "new", "2026-01-02T00:00:00Z", labels(runContext, Map.of())));
            endpoint.tasks.put(BASE_JOB_NAME + "new", List.of(task("completed", "failure")));

            assertEquals(BASE_JOB_NAME + "old", resumable(endpoint, runContext).orElseThrow().id());
        }
    }

    @Test
    void findsTheJobByTheGeneratedPrefixWhenTheJobIdIsNotFound() throws Exception {
        try (var endpoint = new FakeJobList()) {
            var runContext = runContext(endpoint);
            var jobName = ScriptService.jobName(runContext);
            var prefix = jobName.substring(0, jobName.lastIndexOf('-') + 1);
            endpoint.jobs.add(job(prefix + "a", "2026-01-01T00:00:00Z", labels(runContext, Map.of())));

            var found = BatchService.findResumableJob(runContext, client(endpoint, runContext), "unused-id");

            assertEquals(prefix + "a", found.orElseThrow().id());
            assertTrue(endpoint.queries.getFirst().contains("startswith(id, 'unused-id')"));
            assertTrue(endpoint.queries.getLast().contains("startswith(id, '" + prefix + "')"));
        }
    }

    private java.util.Optional<com.microsoft.azure.batch.protocol.models.CloudJob> resumable(FakeJobList endpoint, RunContext runContext) throws Exception {
        return BatchService.findResumableJob(runContext, client(endpoint, runContext), BASE_JOB_NAME);
    }

    private static Map<String, Object> task(String state, String result) {
        var task = new HashMap<String, Object>();
        task.put("id", "task-" + state);
        task.put("state", state);
        if (result != null) {
            task.put("executionInfo", Map.of("result", result, "retryCount", 0, "requeueCount", 0));
        }
        return task;
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
        job.put("state", "active");
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
        private final Map<String, List<Map<String, Object>>> tasks = new java.util.concurrent.ConcurrentHashMap<>();
        private BatchClient client;

        private FakeJobList() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jobs", exchange -> {
                var path = exchange.getRequestURI().getPath();
                List<Map<String, Object>> value = jobs;
                if (path.endsWith("/tasks")) {
                    var jobId = path.substring("/jobs/".length(), path.length() - "/tasks".length());
                    value = tasks.getOrDefault(jobId, List.of());
                } else {
                    var query = exchange.getRequestURI().getRawQuery();
                    var decoded = query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
                    queries.add(decoded);
                    // honor the startswith(id, 'prefix') filter like Batch does
                    var matcher = java.util.regex.Pattern.compile("startswith\\(id, '([^']*)'\\)").matcher(decoded);
                    if (matcher.find()) {
                        var prefix = matcher.group(1);
                        value = jobs.stream().filter(job -> String.valueOf(job.get("id")).startsWith(prefix)).toList();
                    }
                }
                var body = JacksonMapper.ofJson().writeValueAsBytes(Map.of("value", value));
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
