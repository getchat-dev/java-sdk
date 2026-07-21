package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Per-call overrides of the instance {@link RequestOptions}. Any field left
 * unset falls back to the instance default.
 *
 * <pre>{@code
 * sdk.getChats(Map.of(), RequestControl.builder().timeout(5_000).retries(0).build());
 * }</pre>
 */
public final class RequestControl {

    private final @Nullable Long timeout;
    private final @Nullable Integer retries;
    private final @Nullable Long retryDelay;

    private RequestControl(@Nullable Long timeout, @Nullable Integer retries, @Nullable Long retryDelay) {
        // Validated against the same bounds as the instance options, so a per-call
        // typo fails as loudly as an instance one.
        if (timeout != null || retries != null || retryDelay != null) {
            RequestOptions.builder()
                    .timeout(timeout != null ? timeout : RequestOptions.DEFAULT_TIMEOUT_MS)
                    .retries(retries != null ? retries : RequestOptions.DEFAULT_RETRIES)
                    .retryDelay(retryDelay != null ? retryDelay : RequestOptions.DEFAULT_RETRY_DELAY_MS)
                    .build();
        }
        this.timeout = timeout;
        this.retries = retries;
        this.retryDelay = retryDelay;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Resolve these overrides against the instance defaults. */
    public RequestOptions applyTo(RequestOptions defaults) {
        return RequestOptions.builder()
                .timeout(timeout != null ? timeout : defaults.timeout())
                .retries(retries != null ? retries : defaults.retries())
                .retryDelay(retryDelay != null ? retryDelay : defaults.retryDelay())
                .build();
    }

    /** Equal to another {@code RequestControl} carrying the same overrides (nulls included). */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof RequestControl other
                        && Objects.equals(timeout, other.timeout)
                        && Objects.equals(retries, other.retries)
                        && Objects.equals(retryDelay, other.retryDelay));
    }

    @Override
    public int hashCode() {
        return Objects.hash(timeout, retries, retryDelay);
    }

    /** Compact dump for logs; an unset override shows as {@code null}. */
    @Override
    public String toString() {
        return "RequestControl{timeout=" + timeout + ", retries=" + retries + ", retryDelay=" + retryDelay + "}";
    }

    /** Builder for {@link RequestControl}. */
    public static final class Builder {

        private @Nullable Long timeout;
        private @Nullable Integer retries;
        private @Nullable Long retryDelay;

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

        public RequestControl build() {
            return new RequestControl(timeout, retries, retryDelay);
        }
    }
}
