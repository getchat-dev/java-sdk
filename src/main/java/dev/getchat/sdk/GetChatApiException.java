package dev.getchat.sdk;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * A non-2xx/3xx response from the GetChat API.
 *
 * <p>{@code getMessage()} is the raw response body, matching the node SDK where
 * the error message is the stringified body.
 *
 * <p>Unlike a {@link GetChatTransportException}, the server did answer — inspect
 * {@link #status()} to decide what to do. The request that produced it is
 * described by {@link #method()} and {@link #uri()}, and {@link #requestId()}
 * carries the server's request id when it sent one, useful when reporting a
 * failure to support.
 */
public class GetChatApiException extends GetChatException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient JsonValue body;
    private final String rawBody;
    private final String method;
    private final URI uri;
    private final @Nullable String requestId;

    public GetChatApiException(
            int status, String rawBody, @Nullable JsonValue body, String method, URI uri, @Nullable String requestId) {
        super(rawBody);
        this.status = status;
        this.rawBody = rawBody;
        // Never null: an unparsable or non-JSON body is the MISSING sentinel, so
        // body() stays chain-safe like every other JsonValue the SDK returns.
        this.body = body == null ? JsonValue.MISSING : body;
        this.method = method;
        this.uri = uri;
        this.requestId = requestId;
    }

    /** HTTP status code. */
    public int status() {
        return status;
    }

    /**
     * Parsed response body. {@linkplain JsonValue#isMissing() Missing} when the
     * response was not JSON or could not be parsed.
     */
    public JsonValue body() {
        return body;
    }

    /** Response body exactly as received. */
    public String rawBody() {
        return rawBody;
    }

    /** HTTP method of the request that failed, e.g. {@code "GET"}. */
    public String method() {
        return method;
    }

    /** URI the request was sent to. */
    public URI uri() {
        return uri;
    }

    /**
     * Server-supplied request id (the {@code X-Request-Id} response header), or
     * {@code null} when the server did not send one.
     */
    public @Nullable String requestId() {
        return requestId;
    }
}
