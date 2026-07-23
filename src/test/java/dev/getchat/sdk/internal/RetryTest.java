package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class RetryTest {

    // The whole Retry.shouldRetry(method, status, transportError, connectionError)
    // decision matrix in one place: 429 retries for every method (never ran); 5xx
    // retries only for idempotent reads (a write may already have taken effect); 4xx
    // never retries; a transport failure retries a write only when the connection was
    // refused so the request never left the process; a non-transport failure (a parse
    // error or bug) never retries.
    static Stream<Arguments> retryDecisions() {
        return Stream.of(
                arguments("GET 429 -> retry (never ran)", "GET", 429, false, false, true),
                arguments("POST 429 -> retry (never ran)", "POST", 429, false, false, true),
                arguments("PUT 429 -> retry (never ran)", "PUT", 429, false, false, true),
                arguments("GET 500 -> retry (idempotent)", "GET", 500, false, false, true),
                arguments("DELETE 503 -> retry (idempotent)", "DELETE", 503, false, false, true),
                arguments("POST 500 -> no retry (write may have landed)", "POST", 500, false, false, false),
                arguments("PUT 502 -> no retry (write may have landed)", "PUT", 502, false, false, false),
                arguments("GET 404 -> no retry (client error)", "GET", 404, false, false, false),
                arguments("GET 422 -> no retry (client error)", "GET", 422, false, false, false),
                arguments("GET 401 -> no retry (client error)", "GET", 401, false, false, false),
                arguments("GET transport error -> retry", "GET", null, true, false, true),
                arguments("POST transport error -> no retry (may be mid-flight)", "POST", null, true, false, false),
                arguments("POST connection refused -> retry (never sent)", "POST", null, true, true, true),
                arguments("GET non-transport failure -> no retry", "GET", null, false, false, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryDecisions")
    @DisplayName("shouldRetry follows the method/status/transport decision matrix")
    void shouldRetry(
            String label,
            String method,
            @Nullable Integer status,
            boolean transportError,
            boolean connectionError,
            boolean expected) {
        assertEquals(expected, Retry.shouldRetry(method, status, transportError, connectionError));
    }

    static Stream<Arguments> retryAfterValues() {
        return Stream.of(
                arguments("2", 2000L), arguments("0", 0L), arguments(null, null), arguments("not-a-date", null));
    }

    @ParameterizedTest(name = "parseRetryAfter({0}) = {1}")
    @MethodSource("retryAfterValues")
    void retryAfterSeconds(@Nullable String header, @Nullable Long expected) {
        assertEquals(expected, Retry.parseRetryAfter(header));
    }

    @Test
    @DisplayName("backoff grows exponentially and is capped")
    void backoff() {
        assertAll(
                // jitter = 0 gives the bare exponential term.
                () -> assertEquals(200L, Retry.backoffDelay(0, 200, null, 0.0)),
                () -> assertEquals(400L, Retry.backoffDelay(1, 200, null, 0.0)),
                () -> assertEquals(800L, Retry.backoffDelay(2, 200, null, 0.0)),
                // jitter = 1 doubles it.
                () -> assertEquals(400L, Retry.backoffDelay(0, 200, null, 1.0)),
                () -> assertEquals(Retry.MAX_BACKOFF_MS, Retry.backoffDelay(20, 200, null, 1.0), "capped"),
                () -> assertEquals(
                        Retry.MAX_BACKOFF_MS, Retry.backoffDelay(0, 200, 999_000L, 0.0), "Retry-After is capped too"),
                () -> assertEquals(5000L, Retry.backoffDelay(3, 200, 5000L, 1.0), "Retry-After wins over backoff"));
    }

    @ParameterizedTest(name = "isIdempotent({0}) = {1}")
    @CsvSource({"get,true", "DELETE,true", "post,false", "PUT,false"})
    void idempotency(String method, boolean expected) {
        assertEquals(expected, Retry.isIdempotent(method));
    }
}
