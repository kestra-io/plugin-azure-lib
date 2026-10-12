package io.kestra.plugin.azure.shared.batch;

import okhttp3.OkHttpClient;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRetryInterceptorTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void backsOffExponentiallyWithoutRetryAfter(int status) {
        try (var response = response(status, null)) {
            assertEquals(1000, BatchRetryInterceptor.retryDelayMillis(0, response, NOW));
            assertEquals(2000, BatchRetryInterceptor.retryDelayMillis(1, response, NOW));
            assertEquals(4000, BatchRetryInterceptor.retryDelayMillis(2, response, NOW));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "-1", "+1", "1.5", "999999999999999999999999", "9223372036854775807"})
    void fallsBackToBackoffForUnusableRetryAfter(String retryAfter) {
        try (var response = response(429, retryAfter)) {
            assertEquals(2000, BatchRetryInterceptor.retryDelayMillis(1, response, NOW));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void usesRetryAfterInsteadOfBackoff(int status) {
        try (var response = response(status, " 7 ")) {
            assertEquals(7000, BatchRetryInterceptor.retryDelayMillis(2, response, NOW));
        }
        try (var response = response(status, "0")) {
            assertEquals(0, BatchRetryInterceptor.retryDelayMillis(2, response, NOW));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void handlesFutureAndExpiredHttpDates(int status) {
        try (var response = response(status, "Mon, 05 Oct 2026 12:00:03 GMT")) {
            assertEquals(3000, BatchRetryInterceptor.retryDelayMillis(2, response, NOW));
        }
        try (var response = response(status, "Mon, 05 Oct 2026 11:59:59 GMT")) {
            assertEquals(0, BatchRetryInterceptor.retryDelayMillis(2, response, NOW));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 500, 502, 504})
    void preservesImmediateRetriesForOtherStatuses(int status) {
        try (var response = response(status, "7")) {
            assertEquals(0, BatchRetryInterceptor.retryDelayMillis(0, response, NOW));
        }
    }

    @Test
    void closesTheResponseAndPreservesInterruptionWhenBackoffIsInterrupted() throws IOException {
        var closed = new AtomicBoolean();
        var source = Okio.buffer(new ForwardingSource(new Buffer().writeUtf8("{}")) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        });
        var body = new ResponseBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/json");
            }

            @Override
            public long contentLength() {
                return 2;
            }

            @Override
            public BufferedSource source() {
                return source;
            }
        };
        var response = response(429, "30").newBuilder().body(body).build();
        var client = new OkHttpClient.Builder()
            .addInterceptor(new BatchRetryInterceptor())
            .addInterceptor(chain -> response)
            .build();
        try {
            Thread.currentThread().interrupt();
            var error = assertThrows(InterruptedIOException.class, () -> client.newCall(response.request()).execute());
            assertInstanceOf(InterruptedException.class, error.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(closed.get());
        } finally {
            Thread.interrupted();
            response.close();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private static Response response(int status, String retryAfter) {
        var builder = new Response.Builder()
            .request(new Request.Builder().url("http://localhost/jobs/job/tasks").build())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Injected fault")
            .body(ResponseBody.create(MediaType.get("application/json"), "{}"));
        if (retryAfter != null) {
            builder.header("Retry-After", retryAfter);
        }
        return builder.build();
    }
}
