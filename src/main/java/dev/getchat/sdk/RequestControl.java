package dev.getchat.sdk;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Per-call overrides of the instance {@link RequestOptions}. Any field left
 * unset ({@code null}) falls back to the instance default; unlike
 * {@link RequestOptions}, a {@code null} here is legal and simply means "do not
 * override this field".
 *
 * <pre>{@code
 * sdk.listChats(
 *         ChatsQuery.builder().limit(20).build(),
 *         RequestControl.builder().timeout(Duration.ofSeconds(5)).retries(0).build());
 * }</pre>
 */
public final class RequestControl {

    private final @Nullable Duration timeout;
    private final @Nullable Integer retries;
    private final @Nullable Duration retryDelay;

    private RequestControl(@Nullable Duration timeout, @Nullable Integer retries, @Nullable Duration retryDelay) {
        // Validated against the same bounds as the instance options by building a
        // throwaway RequestOptions: only the non-null overrides are checked (the
        // rest borrow the defaults), so a per-call typo fails as loudly as an
        // instance one while an omitted field stays an omission.
        if (timeout != null || retries != null || retryDelay != null) {
            RequestOptions.builder()
                    .timeout(timeout != null ? timeout : Duration.ofMillis(RequestOptions.DEFAULT_TIMEOUT_MS))
                    .retries(retries != null ? retries : RequestOptions.DEFAULT_RETRIES)
                    .retryDelay(
                            retryDelay != null ? retryDelay : Duration.ofMillis(RequestOptions.DEFAULT_RETRY_DELAY_MS))
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

        private @Nullable Duration timeout;
        private @Nullable Integer retries;
        private @Nullable Duration retryDelay;

        private Builder() {}

        /** Override the per-attempt timeout; {@code null} leaves it unset (no override). */
        public Builder timeout(@Nullable Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retries(int retries) {
            this.retries = retries;
            return this;
        }

        /** Override the backoff base delay; {@code null} leaves it unset (no override). */
        public Builder retryDelay(@Nullable Duration retryDelay) {
            this.retryDelay = retryDelay;
            return this;
        }

        public RequestControl build() {
            return new RequestControl(timeout, retries, retryDelay);
        }
    }
}
