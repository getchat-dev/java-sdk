package dev.getchat.sdk;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Request-reliability settings. Set instance-wide on {@link GetChatClient}, and
 * overridable per call via {@link RequestControl}.
 *
 * <p>Bounds are validated on construction so a typo fails fast rather than
 * quietly hammering the backend. Durations must be non-negative;
 * {@link Duration#ZERO} is the documented way to disable the per-attempt
 * timeout (and to skip the backoff wait).
 */
public final class RequestOptions {

    /** Per-attempt timeout, stored as milliseconds; 0 disables it. */
    private final long timeout;

    /** Retry attempts after the first failure; 0 disables retrying. */
    private final int retries;

    /** Base backoff delay in milliseconds (exponential with jitter). */
    private final long retryDelay;

    public static final long DEFAULT_TIMEOUT_MS = 30_000L;
    public static final int DEFAULT_RETRIES = 2;
    public static final long DEFAULT_RETRY_DELAY_MS = 200L;

    /** Same cap as the node SDK, so a {@code retries: 10000} typo is rejected. */
    public static final int MAX_RETRIES = 10;

    private RequestOptions(long timeout, int retries, long retryDelay) {
        this.timeout = validateNonNegative(timeout, "timeout");
        this.retryDelay = validateNonNegative(retryDelay, "retryDelay");
        if (retries < 0 || retries > MAX_RETRIES) {
            throw new GetChatException("retries must be between 0 and " + MAX_RETRIES + ", got " + retries);
        }
        this.retries = retries;
    }

    private static long validateNonNegative(long value, String name) {
        if (value < 0) {
            throw new GetChatException(name + " must be >= 0, got " + value);
        }
        return value;
    }

    public static RequestOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Per-attempt timeout; {@link Duration#ZERO} means no per-attempt deadline. */
    public Duration timeout() {
        return Duration.ofMillis(timeout);
    }

    public int retries() {
        return retries;
    }

    /** Base backoff delay (exponential with jitter); {@link Duration#ZERO} skips the wait. */
    public Duration retryDelay() {
        return Duration.ofMillis(retryDelay);
    }

    /**
     * Base backoff delay in milliseconds, for the internal retry machinery which
     * works in millis. Package-private: the public surface speaks {@link Duration}.
     */
    long retryDelayMillis() {
        return retryDelay;
    }

    /** Equal to another {@code RequestOptions} with the same timeout, retries and delay. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof RequestOptions other
                        && timeout == other.timeout
                        && retries == other.retries
                        && retryDelay == other.retryDelay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeout, retries, retryDelay);
    }

    @Override
    public String toString() {
        return "RequestOptions{timeout=" + timeout + ", retries=" + retries + ", retryDelay=" + retryDelay + "}";
    }

    /** Builder for {@link RequestOptions}. */
    public static final class Builder {

        private long timeout = DEFAULT_TIMEOUT_MS;
        private int retries = DEFAULT_RETRIES;
        private long retryDelay = DEFAULT_RETRY_DELAY_MS;

        private Builder() {}

        /**
         * Per-attempt timeout. {@link Duration#ZERO} disables the deadline.
         *
         * @throws NullPointerException if {@code timeout} is null — it is a
         *     required, non-null setting (this package is {@code @NullMarked})
         */
        public Builder timeout(Duration timeout) {
            this.timeout =
                    Objects.requireNonNull(timeout, "timeout is required").toMillis();
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        /**
         * Base backoff delay. {@link Duration#ZERO} skips the wait between retries.
         *
         * @throws NullPointerException if {@code retryDelay} is null — it is a
         *     required, non-null setting (this package is {@code @NullMarked})
         */
        public Builder retryDelay(Duration retryDelay) {
            this.retryDelay =
                    Objects.requireNonNull(retryDelay, "retryDelay is required").toMillis();
            return this;
        }

        public RequestOptions build() {
            return new RequestOptions(timeout, retries, retryDelay);
        }
    }
}
