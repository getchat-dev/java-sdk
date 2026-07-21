package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RetryTest {

    @Test
    @DisplayName("429 is retried for every method — the request never ran")
    void rateLimitAlwaysRetries() {
        assertTrue(Retry.shouldRetry("GET", 429, false, false));
        assertTrue(Retry.shouldRetry("POST", 429, false, false));
        assertTrue(Retry.shouldRetry("PUT", 429, false, false));
    }

    @Test
    @DisplayName("5xx is retried only for idempotent methods")
    void serverErrorsRetryOnlyForReads() {
        assertTrue(Retry.shouldRetry("GET", 500, false, false));
        assertTrue(Retry.shouldRetry("DELETE", 503, false, false));
        assertFalse(Retry.shouldRetry("POST", 500, false, false), "a write may already have taken effect");
        assertFalse(Retry.shouldRetry("PUT", 502, false, false));
    }

    @Test
    void clientErrorsNeverRetry() {
        assertFalse(Retry.shouldRetry("GET", 404, false, false));
        assertFalse(Retry.shouldRetry("GET", 422, false, false));
        assertFalse(Retry.shouldRetry("GET", 401, false, false));
    }

    @Test
    @DisplayName("a write retries on transport failure only if it never left the process")
    void transportErrors() {
        assertTrue(Retry.shouldRetry("GET", null, true, false));
        assertFalse(Retry.shouldRetry("POST", null, true, false), "may have been received mid-flight");
        assertTrue(Retry.shouldRetry("POST", null, true, true), "connection refused — never sent");
    }

    @Test
    void nonTransportFailuresNeverRetry() {
        assertFalse(Retry.shouldRetry("GET", null, false, false), "parse errors and bugs are not retried");
    }

    @Test
    void retryAfterSeconds() {
        assertEquals(2000L, Retry.parseRetryAfter("2"));
        assertEquals(0L, Retry.parseRetryAfter("0"));
        assertNull(Retry.parseRetryAfter(null));
        assertNull(Retry.parseRetryAfter("not-a-date"));
    }

    @Test
    @DisplayName("backoff grows exponentially and is capped")
    void backoff() {
        // jitter = 0 gives the bare exponential term.
        assertEquals(200L, Retry.backoffDelay(0, 200, null, 0.0));
        assertEquals(400L, Retry.backoffDelay(1, 200, null, 0.0));
        assertEquals(800L, Retry.backoffDelay(2, 200, null, 0.0));

        // jitter = 1 doubles it.
        assertEquals(400L, Retry.backoffDelay(0, 200, null, 1.0));

        assertEquals(Retry.MAX_BACKOFF_MS, Retry.backoffDelay(20, 200, null, 1.0), "capped");
        assertEquals(Retry.MAX_BACKOFF_MS, Retry.backoffDelay(0, 200, 999_000L, 0.0), "Retry-After is capped too");
        assertEquals(5000L, Retry.backoffDelay(3, 200, 5000L, 1.0), "Retry-After wins over backoff");
    }

    @Test
    void idempotency() {
        assertTrue(Retry.isIdempotent("get"));
        assertTrue(Retry.isIdempotent("DELETE"));
        assertFalse(Retry.isIdempotent("post"));
        assertFalse(Retry.isIdempotent("PUT"));
    }
}
