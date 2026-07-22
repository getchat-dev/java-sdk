package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A single REST call described as an immutable value, and the escape hatch for
 * endpoints the SDK does not wrap yet. Pass one to
 * {@link GetChatClient#requestApi(ApiRequest)}.
 *
 * <p>Start from a factory that fixes the verb and path — {@link #get},
 * {@link #post}, {@link #put}, {@link #delete} — then fill in the rest on the
 * {@link Builder}:
 *
 * <pre>{@code
 * // GET an endpoint the SDK does not wrap yet:
 * JsonValue hooks = sdk.requestApi(ApiRequest.get("chats/support-42/webhooks")
 *         .query("with_disabled", 1)
 *         .build());
 *
 * // PUT with a JSON body, a URL query param and a custom header:
 * sdk.requestApi(ApiRequest.put("chats/support-42/webhook")
 *         .body(Map.of("url", "https://example.com/hook"))
 *         .query("dry_run", 1)
 *         .header("Prefer", "return=representation")
 *         .build());
 * }</pre>
 *
 * <p>The {@code path} is what follows {@code /api/{version}/}, with any path
 * params already percent-encoded by the caller.
 *
 * <p><strong>Query mapping.</strong> For {@code GET}/{@code DELETE} the
 * {@code query} becomes the URL query string; for {@code POST}/{@code PUT} the
 * {@code body} is the JSON body and {@code query} the URL query string. Supplying
 * a {@code body} on a {@code GET}/{@code DELETE} is rejected in {@link
 * Builder#build()} rather than silently dropped. This deliberately drops the
 * former transport's two-layer {@code params}-plus-{@code query} merge for reads:
 * a read now has exactly one place for its URL parameters.
 */
public final class ApiRequest {

    private final GetChatClient.HttpMethod method;
    private final String path;
    private final @Nullable Map<String, Object> body;
    private final Map<String, Object> query;
    private final Map<String, String> headers;
    private final String version;
    private final @Nullable RequestControl control;

    private ApiRequest(Builder b) {
        this.method = b.method;
        // Validated non-null/non-empty in build(); requireNonNull satisfies the
        // nullness contract on the field as well.
        this.path = Objects.requireNonNull(b.path);
        this.body = b.body == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(b.body));
        this.query = Collections.unmodifiableMap(new LinkedHashMap<>(b.query));
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.version = b.version;
        this.control = b.control;
    }

    /** A {@code GET} request to {@code path}. */
    public static Builder get(String path) {
        return new Builder(GetChatClient.HttpMethod.GET, path);
    }

    /** A {@code POST} request to {@code path}. */
    public static Builder post(String path) {
        return new Builder(GetChatClient.HttpMethod.POST, path);
    }

    /** A {@code PUT} request to {@code path}. */
    public static Builder put(String path) {
        return new Builder(GetChatClient.HttpMethod.PUT, path);
    }

    /** A {@code DELETE} request to {@code path}. */
    public static Builder delete(String path) {
        return new Builder(GetChatClient.HttpMethod.DELETE, path);
    }

    /** The HTTP verb. */
    public GetChatClient.HttpMethod method() {
        return method;
    }

    /** The path after {@code /api/{version}/}, with path params already encoded. */
    public String path() {
        return path;
    }

    /** The JSON body ({@code POST}/{@code PUT} only), or {@code null} when unset. */
    public @Nullable Map<String, Object> body() {
        return body;
    }

    /** The URL query params (possibly empty). */
    public Map<String, Object> query() {
        return query;
    }

    /** The extra request headers (possibly empty). */
    public Map<String, String> headers() {
        return headers;
    }

    /** The API version segment, {@code v1} by default. */
    public String version() {
        return version;
    }

    /** Per-call timeout/retry overrides, or {@code null} for the instance defaults. */
    public @Nullable RequestControl control() {
        return control;
    }

    /** Equal to another {@code ApiRequest} describing the same call. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof ApiRequest other
                        && method == other.method
                        && path.equals(other.path)
                        && version.equals(other.version)
                        && Objects.equals(body, other.body)
                        && query.equals(other.query)
                        && headers.equals(other.headers)
                        && Objects.equals(control, other.control));
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, path, version, body, query, headers, control);
    }

    /**
     * Compact dump for logs. Header <strong>values</strong> are masked
     * ({@code Prefer=***}) because a caller-supplied header can carry credentials
     * (a token, a cookie); the header <em>names</em> are shown. The body and query
     * render in the clear.
     */
    @Override
    public String toString() {
        return "ApiRequest{method=" + method
                + ", path=" + path
                + ", version=" + version
                + ", query=" + query
                + ", headers=" + maskedHeaders()
                + ", body=" + body
                + ", control=" + control
                + "}";
    }

    /** Render the headers as {@code {name=***, …}} so no value leaks into a log. */
    private String maskedHeaders() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String name : headers.keySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(name).append("=***");
            first = false;
        }
        return sb.append("}").toString();
    }

    /** Builder for {@link ApiRequest}; obtained from a verb factory. */
    public static final class Builder {

        private final GetChatClient.HttpMethod method;
        private final @Nullable String path;
        private @Nullable Map<String, Object> body;
        private final Map<String, Object> query = new LinkedHashMap<>();
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String version = GetChatClient.DEFAULT_VERSION;
        private @Nullable RequestControl control;

        private Builder(GetChatClient.HttpMethod method, @Nullable String path) {
            this.method = method;
            this.path = path;
        }

        /**
         * The JSON request body. Meaningful only for {@code POST}/{@code PUT};
         * setting it on a {@code GET}/{@code DELETE} makes {@link #build()} throw.
         * A defensive copy is taken; passing {@code null} clears it.
         */
        public Builder body(@Nullable Map<String, Object> body) {
            this.body = body == null ? null : new LinkedHashMap<>(body);
            return this;
        }

        /** Merge URL query params on top of those set so far. A defensive copy is taken. */
        public Builder query(@Nullable Map<String, Object> query) {
            if (query != null) {
                this.query.putAll(query);
            }
            return this;
        }

        /** Add a single URL query param. */
        public Builder query(String key, @Nullable Object value) {
            this.query.put(key, value);
            return this;
        }

        /** Add a single request header. */
        public Builder header(String name, String value) {
            this.headers.put(name, value);
            return this;
        }

        /** Merge request headers on top of those set so far. A defensive copy is taken. */
        public Builder headers(@Nullable Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        /** Override the API version segment (default {@code v1}). {@code null} keeps the default. */
        public Builder version(@Nullable String version) {
            this.version = version == null ? GetChatClient.DEFAULT_VERSION : version;
            return this;
        }

        /** Attach per-call timeout/retry overrides. */
        public Builder control(@Nullable RequestControl control) {
            this.control = control;
            return this;
        }

        /**
         * @throws GetChatException if the path is missing/empty, or a body was set
         *     on a {@code GET}/{@code DELETE} request
         */
        public ApiRequest build() {
            if (path == null || path.isEmpty()) {
                throw new GetChatException("request path is required");
            }
            if (body != null && !method.hasBody()) {
                throw new GetChatException(
                        method + " request cannot carry a body; put URL parameters in query(...) instead");
            }
            return new ApiRequest(this);
        }
    }
}
