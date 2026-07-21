package dev.getchat.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A non-2xx/3xx response from the GetChat API.
 *
 * <p>{@code getMessage()} is the raw response body, matching the node SDK where
 * the error message is the stringified body.
 */
public class GetChatApiException extends GetChatException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final transient JsonValue body;
    private final String rawBody;

    public GetChatApiException(int status, String rawBody, @Nullable JsonValue body) {
        super(rawBody);
        this.status = status;
        this.rawBody = rawBody;
        // Never null: an unparsable or non-JSON body is the MISSING sentinel, so
        // body() stays chain-safe like every other JsonValue the SDK returns.
        this.body = body == null ? JsonValue.MISSING : body;
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
}
