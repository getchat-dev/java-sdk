package dev.getchat.sdk.internal;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/** Port of {@code src/libs/retry.ts}. */
public final class Retry {

    private Retry() {}

    /** Transient 5xx worth replaying — but only for idempotent methods. */
    private static final Set<Integer> RETRYABLE_5XX = Set.of(500, 502, 503, 504);

    /** Backoff waits are capped so a huge {@code Retry-After} cannot stall a call forever. */
    public static final long MAX_BACKOFF_MS = 30_000L;

    public static boolean isIdempotent(String method) {
        String m = method.toLowerCase(Locale.ROOT);
        return m.equals("get") || m.equals("delete");
    }

    /**
     * Whether a failed attempt may be retried.
     *
     * <ul>
     *   <li>429 — retried for any method (rate limited, so it never ran)
     *   <li>5xx — idempotent methods only (a write may already have taken effect)
     *   <li>transport failure — reads always; writes only when the request
     *       provably never left this process, never mid-flight
     * </ul>
     *
     * @param status HTTP status, or null when the failure was at transport level
     * @param preSend true when the failure proves the request never reached the server
     */
    public static boolean shouldRetry(String method, @Nullable Integer status, boolean transportError, boolean preSend) {
        if (status != null) {
            if (status == 429) {
                return true;
            }
            if (RETRYABLE_5XX.contains(status)) {
                return isIdempotent(method);
            }
            return false;
        }
        if (transportError) {
            return isIdempotent(method) || preSend;
        }
        return false; // parse errors, bugs — don't retry
    }

    /** Parse {@code Retry-After} (delta-seconds or HTTP-date) into milliseconds. */
    public static @Nullable Long parseRetryAfter(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.trim();
        try {
            double seconds = Double.parseDouble(raw);
            return Math.max(0L, (long) (seconds * 1000));
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
            long delta = Duration.between(ZonedDateTime.now(when.getZone()), when).toMillis();
            return Math.max(0L, delta);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Delay before the next attempt: honour {@code Retry-After} when present,
     * otherwise exponential backoff with full jitter ({@code base·2^n} plus up to
     * the same again), capped at {@link #MAX_BACKOFF_MS}.
     */
    public static long backoffDelay(int attempt, long baseMs, @Nullable Long retryAfterMs) {
        return backoffDelay(attempt, baseMs, retryAfterMs, ThreadLocalRandom.current().nextDouble());
    }

    /** Overload taking the jitter factor directly, so tests stay deterministic. */
    public static long backoffDelay(int attempt, long baseMs, @Nullable Long retryAfterMs, double jitter) {
        if (retryAfterMs != null) {
            return Math.min(retryAfterMs, MAX_BACKOFF_MS);
        }
        double exp = baseMs * Math.pow(2, attempt);
        double jittered = exp + jitter * exp;
        return (long) Math.min(jittered, MAX_BACKOFF_MS);
    }
}
