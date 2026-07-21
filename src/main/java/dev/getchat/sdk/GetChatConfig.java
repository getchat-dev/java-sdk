package dev.getchat.sdk;

import java.net.http.HttpClient;
import java.util.Objects;
import java.util.function.IntFunction;
import org.jspecify.annotations.Nullable;

/**
 * Construction-time configuration for {@link GetChat}.
 *
 * <p>URL signing needs {@code id} + {@code secret}; the REST methods need
 * {@code apiToken} + {@code apiUrl} (which falls back to {@code baseUrl}). An
 * instance used for only one of the two jobs can leave the other pair unset.
 */
public final class GetChatConfig {

    private final @Nullable String id;
    private final @Nullable String secret;
    private final @Nullable String apiToken;
    private final @Nullable String baseUrl;
    private final @Nullable String apiUrl;
    private final RequestOptions options;
    private final @Nullable HttpClient httpClient;
    private final @Nullable IntFunction<String> randomStringSupplier;

    private GetChatConfig(Builder b) {
        this.id = b.id;
        this.secret = b.secret;
        this.apiToken = b.apiToken;
        // Trailing slashes are stripped so `baseUrl + "?" + query` is well-formed.
        this.baseUrl = b.baseUrl == null ? null : b.baseUrl.replaceAll("/+$", "");
        this.apiUrl = (b.apiUrl != null ? b.apiUrl : this.baseUrl);
        this.options = b.options != null ? b.options : RequestOptions.defaults();
        this.httpClient = b.httpClient;
        this.randomStringSupplier = b.randomStringSupplier;
    }

    public static Builder builder() {
        return new Builder();
    }

    public @Nullable String id() {
        return id;
    }

    public @Nullable String secret() {
        return secret;
    }

    public @Nullable String apiToken() {
        return apiToken;
    }

    public @Nullable String baseUrl() {
        return baseUrl;
    }

    public @Nullable String apiUrl() {
        return apiUrl;
    }

    public RequestOptions options() {
        return options;
    }

    @Nullable HttpClient httpClient() {
        return httpClient;
    }

    @Nullable IntFunction<String> randomStringSupplier() {
        return randomStringSupplier;
    }

    /**
     * Equal to another {@code GetChatConfig} with the same credentials, URLs and
     * options; the {@code httpClient} and {@code randomStringSupplier} compare by
     * reference identity, as they carry no value semantics of their own.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof GetChatConfig other
                        && Objects.equals(id, other.id)
                        && Objects.equals(secret, other.secret)
                        && Objects.equals(apiToken, other.apiToken)
                        && Objects.equals(baseUrl, other.baseUrl)
                        && Objects.equals(apiUrl, other.apiUrl)
                        && options.equals(other.options)
                        && Objects.equals(httpClient, other.httpClient)
                        && Objects.equals(randomStringSupplier, other.randomStringSupplier));
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, secret, apiToken, baseUrl, apiUrl, options, httpClient, randomStringSupplier);
    }

    /**
     * Diagnostic dump for logs. The {@code secret} and {@code apiToken} are
     * <strong>redacted</strong> — a set value renders as {@code ***} and never
     * appears in the clear, so a config is safe to log. The {@code httpClient}
     * and {@code randomStringSupplier} render only as {@code set}/{@code unset}.
     */
    @Override
    public String toString() {
        return "GetChatConfig{id=" + id
                + ", secret=" + redact(secret)
                + ", apiToken=" + redact(apiToken)
                + ", baseUrl=" + baseUrl
                + ", apiUrl=" + apiUrl
                + ", options=" + options
                + ", httpClient=" + (httpClient != null ? "set" : "unset")
                + ", randomStringSupplier=" + (randomStringSupplier != null ? "set" : "unset")
                + "}";
    }

    /** Mask a secret: {@code ***} when present, {@code null} when unset. */
    private static String redact(@Nullable String secret) {
        return secret == null ? "null" : "***";
    }

    /** Builder for {@link GetChatConfig}. */
    public static final class Builder {

        private @Nullable String id;
        private @Nullable String secret;
        private @Nullable String apiToken;
        private @Nullable String baseUrl;
        private @Nullable String apiUrl;
        private @Nullable RequestOptions options;
        private @Nullable HttpClient httpClient;
        private @Nullable IntFunction<String> randomStringSupplier;

        private Builder() {}

        /** Client id, used by {@link GetChat#url} signing. */
        public Builder id(@Nullable String id) {
            this.id = id;
            return this;
        }

        /** Client secret, the key for both URL signature schemes. */
        public Builder secret(@Nullable String secret) {
            this.secret = secret;
            return this;
        }

        /** Bearer token for the REST API. */
        public Builder apiToken(@Nullable String apiToken) {
            this.apiToken = apiToken;
            return this;
        }

        /** Base URL the embed URLs point at. */
        public Builder baseUrl(@Nullable String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** REST API base; defaults to {@link #baseUrl}. */
        public Builder apiUrl(@Nullable String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        public Builder options(@Nullable RequestOptions options) {
            this.options = options;
            return this;
        }

        /** Supply your own {@link HttpClient} (proxy, custom SSL, executor). */
        public Builder httpClient(@Nullable HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Override the nonce/session generator. Exists so tests can replay the
         * golden signature vectors; production code should leave it alone.
         */
        public Builder randomStringSupplier(@Nullable IntFunction<String> supplier) {
            this.randomStringSupplier = supplier;
            return this;
        }

        public GetChatConfig build() {
            return new GetChatConfig(this);
        }
    }
}
