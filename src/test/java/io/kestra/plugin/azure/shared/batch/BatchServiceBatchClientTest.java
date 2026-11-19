package io.kestra.plugin.azure.shared.batch;

import com.azure.compute.batch.BatchClient;
import com.azure.compute.batch.models.BatchErrorException;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    void getExistingBatchJobReturnsTheLatestJobMatchingTheLabels() throws Exception {
        var jobs = """
            {"value": [
              {"id": "base-old", "creationTime": "2026-01-01T00:00:00Z", "metadata": []},
              {"id": "base-latest", "creationTime": "2026-01-03T00:00:00Z", "metadata": [
                {"name": "kestra-flow-id", "value": "my-flow"},
                {"name": "kestra-namespace", "value": "company.team"}
              ]},
              {"id": "base-other-flow", "creationTime": "2026-01-04T00:00:00Z", "metadata": [{"name": "kestra-flow-id", "value": "another"}]},
              {"id": "base-middle", "creationTime": "2026-01-02T00:00:00Z"}
            ]}
            """;
        try (var endpoint = new FakeBatchEndpoint(attempt -> new Reply(200, jobs, null))) {
            var runContext = runContextWithTaskRun(endpoint);

            var job = BatchService.getExistingBatchJob(runContext, client(endpoint, runContext), "base-");

            assertEquals("base-latest", job.orElseThrow().getId());
            assertTrue(endpoint.requests.getFirst().query().contains("$filter=startswith(id, 'base-')"));
        }
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
