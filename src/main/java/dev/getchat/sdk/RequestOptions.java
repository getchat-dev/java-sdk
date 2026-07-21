package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Request-reliability settings. Set instance-wide on {@link GetChatConfig}, and
 * overridable per call via {@link RequestControl}.
 *
 * <p>Bounds are validated on construction so a typo fails fast rather than
 * quietly hammering the backend.
 */
public final class RequestOptions {

    /** Per-attempt timeout in milliseconds; 0 disables it. */
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

    public long timeout() {
        return timeout;
    }

    public int retries() {
        return retries;
    }

    public long retryDelay() {
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

        public Builder timeout(long millis) {
            this.timeout = millis;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        public Builder retryDelay(long millis) {
            this.retryDelay = millis;
            return this;
        }

        public RequestOptions build() {
            return new RequestOptions(timeout, retries, retryDelay);
        }
    }
}
