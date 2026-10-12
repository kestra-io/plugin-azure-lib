package io.kestra.plugin.azure.shared.batch;

import com.microsoft.rest.retry.RetryHandler;
import com.microsoft.rest.retry.RetryStrategy;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class BatchRetryInterceptor implements Interceptor {
    private static final int MAX_RETRIES = 3;
    private final RetryStrategy defaultStrategy = new RetryHandler().strategy();

    // RetryHandler has no delay hook; disable its inner loop so the retry budget is not multiplied.
    static final RetryStrategy DISABLED_SDK_RETRIES = new RetryStrategy("Batch retry interceptor", false) {
        @Override
        public boolean shouldRetry(int retryCount, Response response) {
            return false;
        }
    };

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        var response = chain.proceed(request);
        for (int retryCount = 0; retryCount < MAX_RETRIES
            && (response.code() == 429 || defaultStrategy.shouldRetry(retryCount, response)); retryCount++) {
            var delay = retryDelayMillis(retryCount, response, Instant.now());
            if (response.body() != null) {
                response.body().close();
            }
            try {
                var remaining = delay;
                do {
                    if (chain.call().isCanceled()) {
                        throw new InterruptedIOException("Azure Batch request canceled while retrying");
                    }
                    var interval = Math.min(remaining, 100L);
                    Thread.sleep(interval);
                    remaining -= interval;
                } while (remaining > 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                var interrupted = new InterruptedIOException("Interrupted while retrying an Azure Batch request");
                interrupted.initCause(e);
                throw interrupted;
            }
            response = chain.proceed(request);
        }
        return response;
    }

    static long retryDelayMillis(int retryCount, Response response, Instant now) {
        if (response.code() != 429 && response.code() != 503) {
            return 0;
        }
        var retryAfter = response.header("Retry-After");
        if (retryAfter != null) {
            retryAfter = retryAfter.trim();
            try {
                if (retryAfter.matches("[0-9]+")) {
                    return Math.multiplyExact(Long.parseLong(retryAfter), 1000L);
                }
                var retryAt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(now, retryAt).toMillis());
            } catch (NumberFormatException | DateTimeParseException | ArithmeticException ignored) {
                // Missing or unusable Retry-After values fall back to exponential backoff.
            }
        }
        return 1000L << retryCount;
    }
}
