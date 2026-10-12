package io.kestra.plugin.azure.shared.batch;

import com.microsoft.azure.batch.BatchClient;
import com.microsoft.azure.batch.protocol.models.BatchErrorException;
import com.microsoft.azure.batch.protocol.models.PoolInformation;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class BatchServiceTest {
    @Inject
    RunContextFactory runContextFactory;

    @Test
    void listsTasksWithoutRetryingSuccessfulRequests() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(429, 0, () -> null)) {
            assertTrue(client(endpoint).taskOperations().listTasks("job").isEmpty());
            assertEquals(1, endpoint.requestTimes.size());
            assertTrue(endpoint.authorizationHeaders.getFirst().startsWith("SharedKey test-account:"));
        }
    }

    @Test
    void retriesThrottledListTasksWithBackoff() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(429, 1, () -> null)) {
            assertTrue(client(endpoint).taskOperations().listTasks("job").isEmpty());
            assertEquals(2, endpoint.requestTimes.size());
            assertTrue(endpoint.retryDelayMillis() >= 1000);
        }
    }

    @Test
    void replaysJobCreationBodyAndSharedKeyAuthenticationAfterThrottling() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(429, 1, () -> "0")) {
            client(endpoint).jobOperations().createJob("job", new PoolInformation().withPoolId("pool"));
            assertEquals(2, endpoint.requestTimes.size());
            assertEquals(java.util.List.of("POST", "POST"), endpoint.requestMethods);
            assertEquals(endpoint.requestBodies.getFirst(), endpoint.requestBodies.get(1));
            var body = JacksonMapper.ofJson().readTree(endpoint.requestBodies.getFirst());
            assertEquals("job", body.path("id").asText());
            assertEquals("pool", body.path("poolInfo").path("poolId").asText());
            assertTrue(endpoint.authorizationHeaders.stream().allMatch(header -> header.startsWith("SharedKey test-account:")));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void honorsRetryAfterSeconds(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, 1, () -> "1")) {
            assertTrue(client(endpoint).taskOperations().listTasks("job").isEmpty());
            assertEquals(2, endpoint.requestTimes.size());
            assertTrue(endpoint.retryDelayMillis() >= 1000);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void honorsRetryAfterHttpDate(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, 1, () ->
            DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(3)))) {
            assertTrue(client(endpoint).taskOperations().listTasks("job").isEmpty());
            assertEquals(2, endpoint.requestTimes.size());
            assertTrue(endpoint.retryDelayMillis() >= 1500);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void stopsAfterThreeRetriesAndSurfacesTheLastError(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, Integer.MAX_VALUE, () -> status == 503 ? "1" : "0")) {
            var client = client(endpoint);
            var error = assertThrows(BatchErrorException.class, () -> client.taskOperations().listTasks("job"));
            assertEquals(4, endpoint.requestTimes.size());
            assertEquals(status, error.response().code());
            assertEquals("Injected fault 4", error.body().message().value());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 503})
    void preservesTransportFollowUpsWhenRetryAfterIsZero(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, Integer.MAX_VALUE, () -> "0")) {
            var client = client(endpoint);
            assertThrows(BatchErrorException.class, () -> client.taskOperations().listTasks("job"));
            // OkHttp already performs one extra follow-up for 408/503 with Retry-After: 0.
            assertEquals(status == 429 ? 4 : 8, endpoint.requestTimes.size());
        }
    }

    @Test
    void cancelsRequestsDuringRetryAfterBackoff() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(429, Integer.MAX_VALUE, () -> "30")) {
            var client = client(endpoint);
            var request = new Request.Builder()
                .url("http://127.0.0.1:" + endpoint.server.getAddress().getPort() + "/jobs/job/tasks")
                .build();
            var call = client.protocolLayer().restClient().httpClient().newCall(request);
            var failure = new AtomicReference<IOException>();
            var finished = new CountDownLatch(1);
            var thread = new Thread(() -> {
                try (var ignored = call.execute()) {
                    // This endpoint never succeeds.
                } catch (IOException e) {
                    failure.set(e);
                } finally {
                    finished.countDown();
                }
            });
            thread.start();
            try {
                assertTrue(endpoint.firstResponseSent.await(5, TimeUnit.SECONDS));
                awaitBackoff(thread);
                call.cancel();
                assertTrue(finished.await(1, TimeUnit.SECONDS), "Canceled call remained in backoff");
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertEquals(1, endpoint.requestTimes.size());
            } finally {
                call.cancel();
                thread.interrupt();
                thread.join(2000);
            }
        }
    }

    private static void awaitBackoff(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var stack = thread.getStackTrace();
            var inRetry = Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals(BatchRetryInterceptor.class.getName()));
            var sleeping = Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals(Thread.class.getName())
                && frame.getMethodName().startsWith("sleep"));
            if (inRetry && sleeping) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Request did not enter Azure Batch backoff");
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 503, 504})
    void preservesExistingRetryableStatusCodes(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, 1, () -> "0")) {
            assertTrue(client(endpoint).taskOperations().listTasks("job").isEmpty());
            assertEquals(2, endpoint.requestTimes.size());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 501, 505})
    void doesNotRetryNonRetryableErrors(int status) throws Exception {
        try (var endpoint = new FakeBatchEndpoint(status, Integer.MAX_VALUE, () -> status == 503 ? "1" : "0")) {
            var client = client(endpoint);
            var error = assertThrows(BatchErrorException.class, () -> client.taskOperations().listTasks("job"));
            assertEquals(status, error.response().code());
            assertEquals(1, endpoint.requestTimes.size());
        }
    }

    @Test
    void preservesGetJobNotFoundErrors() throws Exception {
        try (var endpoint = new FakeBatchEndpoint(404, Integer.MAX_VALUE, () -> null)) {
            var client = client(endpoint);
            var error = assertThrows(BatchErrorException.class, () -> client.jobOperations().getJob("missing"));
            assertEquals(404, error.response().code());
            assertEquals(1, endpoint.requestTimes.size());
        }
    }

    private BatchClient client(FakeBatchEndpoint endpoint) throws Exception {
        endpoint.client = BatchService.client(
            Property.ofExpression("{{ endpoint }}"),
            Property.ofExpression("{{ account }}"),
            Property.ofExpression("{{ accessKey }}"),
            runContextFactory.of(Map.of(
                "endpoint", "http://127.0.0.1:" + endpoint.server.getAddress().getPort(),
                "account", "test-account",
                "accessKey", "dGVzdC1rZXk="
            ))
        );
        return endpoint.client;
    }

    private static final class FakeBatchEndpoint implements AutoCloseable {
        private final HttpServer server;
        private final CopyOnWriteArrayList<Long> requestTimes = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> requestMethods = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstResponseSent = new CountDownLatch(1);
        private BatchClient client;

        private FakeBatchEndpoint(int status, int failures, Supplier<String> retryAfter) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requestTimes.add(System.nanoTime());
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                var method = exchange.getRequestMethod();
                requestMethods.add(method);
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var attempt = requestTimes.size();
                var fail = attempt <= failures;
                var body = (fail
                    ? "{\"code\":\"InjectedFault\",\"message\":{\"value\":\"Injected fault " + attempt + "\",\"lang\":\"en-US\"}}"
                    : method.equals("POST") ? "" : "{\"value\":[]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                var header = retryAfter.get();
                if (fail && header != null) {
                    exchange.getResponseHeaders().set("Retry-After", header);
                }
                exchange.sendResponseHeaders(fail ? status : method.equals("POST") ? 201 : 200, body.length == 0 ? -1 : body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
                firstResponseSent.countDown();
            });
            server.start();
        }

        private long retryDelayMillis() {
            return TimeUnit.NANOSECONDS.toMillis(requestTimes.get(1) - requestTimes.getFirst());
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
