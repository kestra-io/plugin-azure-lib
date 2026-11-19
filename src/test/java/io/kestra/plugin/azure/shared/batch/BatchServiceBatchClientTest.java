package io.kestra.plugin.azure.shared.batch;

import com.azure.compute.batch.BatchClient;
import com.azure.compute.batch.models.BatchErrorException;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class BatchServiceBatchClientTest {
    private static final String EMPTY_LIST = "{\"value\":[]}";
    private static final String THROTTLED = "{\"code\":\"InjectedFault\",\"message\":{\"value\":\"Injected fault\",\"lang\":\"en-US\"}}";

    @Inject
    RunContextFactory runContextFactory;

    @Test
    void signsRequestsWithSharedKey() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(200, EMPTY_LIST, null))) {
            assertTrue(client(endpoint).listTasks("job").stream().toList().isEmpty());

            assertEquals(1, endpoint.requests.size());
            assertTrue(endpoint.requests.getFirst().authorization().startsWith("SharedKey test-account:"));
        }
    }

    @Test
    void retriesThrottledRequestsHonoringRetryAfter() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> attempt == 1 ? new Reply(429, THROTTLED, "0") : new Reply(200, EMPTY_LIST, null))) {
            assertTrue(client(endpoint).listTasks("job").stream().toList().isEmpty());

            assertEquals(2, endpoint.requests.size());
        }
    }

    @Test
    void stopsRetryingAfterTheRetryBudget() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(503, THROTTLED, "0"))) {
            var client = client(endpoint);

            var error = assertThrows(BatchErrorException.class, () -> client.listTasks("job").stream().toList());

            assertEquals(503, error.getResponse().getStatusCode());
            assertEquals(4, endpoint.requests.size());
        }
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(404, THROTTLED, null))) {
            var client = client(endpoint);

            var error = assertThrows(BatchErrorException.class, () -> client.getJob("missing"));

            assertEquals(404, error.getResponse().getStatusCode());
            assertEquals(1, endpoint.requests.size());
        }
    }

    @Test
    void getExistingBatchJobReturnsTheLatestJobCarryingAllRunLabels() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(200, jobsJson(), null))) {
            var runContext = runContextWithTaskRun(endpoint);
            jobs = List.of(
                job("base-old", "2026-01-01T00:00:00Z", runLabels(runContext, Map.of())),
                job("base-latest", "2026-01-03T00:00:00Z", runLabels(runContext, Map.of("kestra-taskrun-attempt", "0", "team", "data"))),
                job("base-without-labels", "2026-01-04T00:00:00Z", Map.of()),
                job("base-partial-labels", "2026-01-05T00:00:00Z", Map.of("kestra-flow-id", runLabels(runContext, Map.of()).get("kestra-flow-id"))),
                job("base-other-execution", "2026-01-06T00:00:00Z", runLabels(runContext, Map.of("kestra-execution-id", "another")))
            );

            var job = BatchService.getExistingBatchJob(runContext, client(endpoint, runContext), "base-");

            assertEquals("base-latest", job.orElseThrow().getId());
            assertTrue(endpoint.requests.getFirst().query().contains("$filter=startswith(id, 'base-')"));
        }
    }

    @Test
    void getExistingBatchJobIgnoresJobsWithoutTheRunLabels() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(200, jobsJson(), null))) {
            var runContext = runContextWithTaskRun(endpoint);
            jobs = List.of(
                job("base-without-labels", "2026-01-01T00:00:00Z", Map.of()),
                job("base-user-labels", "2026-01-02T00:00:00Z", Map.of("team", "data"))
            );

            assertTrue(BatchService.getExistingBatchJob(runContext, client(endpoint, runContext), "base-").isEmpty());
        }
    }

    @Test
    void failsWithAClearMessageWhenAConnectionPropertyIsMissing() {
        var runContext = runContextFactory.of(Map.of());

        var error = assertThrows(IllegalArgumentException.class, () -> BatchService.batchClient(
            Property.ofValue("https://account.region.batch.azure.com"),
            null,
            Property.ofValue("dGVzdC1rZXk="),
            runContext
        ));

        assertTrue(error.getMessage().contains("'account' is required"));
    }

    @Test
    void getExistingBatchJobIsEmptyWithoutCandidates() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(200, EMPTY_LIST, null))) {
            var runContext = runContextWithTaskRun(endpoint);

            assertTrue(BatchService.getExistingBatchJob(runContext, client(endpoint, runContext), "base-").isEmpty());
        }
    }

    @Test
    void defaultResumeStrategySelectsTheFirstCandidate() {
        var first = new com.azure.compute.batch.models.BatchJob(new com.azure.compute.batch.models.BatchPoolInfo().setPoolId("pool"));

        assertEquals(first, new DefaultJobResumeStrategy().selectBatchJob(List.of(first)).orElseThrow());
        assertTrue(new DefaultJobResumeStrategy().selectBatchJob(List.of()).isEmpty());
    }

    private volatile List<String> jobs = List.of();

    private String jobsJson() {
        return "{\"value\": [" + String.join(",", jobs) + "]}";
    }

    private static String job(String id, String creationTime, Map<String, String> metadata) {
        var items = metadata.entrySet().stream()
            .map(e -> "{\"name\": \"" + e.getKey() + "\", \"value\": \"" + e.getValue() + "\"}")
            .toList();
        return "{\"id\": \"" + id + "\", \"creationTime\": \"" + creationTime + "\", \"metadata\": [" + String.join(",", items) + "]}";
    }

    private static Map<String, String> runLabels(RunContext runContext, Map<String, String> overrides) {
        var labels = new HashMap<>(ScriptService.labels(runContext, "kestra-", true, true));
        labels.putAll(overrides);
        return labels;
    }

    private BatchClient client(FakeBatchEndpoint endpoint) throws Exception {
        return client(endpoint, runContext(endpoint));
    }

    private BatchClient client(FakeBatchEndpoint endpoint, RunContext runContext) throws Exception {
        return BatchService.batchClient(
            Property.ofExpression("{{ endpoint }}"),
            Property.ofExpression("{{ account }}"),
            Property.ofExpression("{{ accessKey }}"),
            runContext
        );
    }

    private RunContext runContext(FakeBatchEndpoint endpoint) {
        return runContextFactory.of(Map.of(
            "endpoint", "http://127.0.0.1:" + endpoint.server.getAddress().getPort(),
            "account", "test-account",
            "accessKey", "dGVzdC1rZXk="
        ));
    }

    private RunContext runContextWithTaskRun(FakeBatchEndpoint endpoint) {
        return runContextFactory.of(Map.of(
            "endpoint", "http://127.0.0.1:" + endpoint.server.getAddress().getPort(),
            "account", "test-account",
            "accessKey", "dGVzdC1rZXk=",
            "flow", Map.of("namespace", "company.team", "id", "my-flow"),
            "task", Map.of("id", "create"),
            "execution", Map.of("id", "exec"),
            "taskrun", Map.of("id", "taskrun", "attemptsCount", 1)
        ));
    }

    private record Reply(int status, String body, String retryAfter) {
    }

    private record Request(String method, String query, String authorization) {
    }

    private static final class FakeBatchEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();

        private FakeBatchEndpoint(IntFunction<Reply> replies) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                var rawQuery = exchange.getRequestURI().getRawQuery();
                requests.add(new Request(
                    exchange.getRequestMethod(),
                    rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization")
                ));
                var reply = replies.apply(requests.size());
                var body = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.retryAfter() != null) {
                    exchange.getResponseHeaders().set("Retry-After", reply.retryAfter());
                }
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
