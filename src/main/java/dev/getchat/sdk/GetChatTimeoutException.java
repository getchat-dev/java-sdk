package dev.getchat.sdk;

import java.time.Duration;

/**
 * A request exceeded its per-attempt timeout.
 *
 * <p>As in the node SDK this counts as a transport error — hence it extends
 * {@link GetChatTransportException} — so a timed-out GET/DELETE is retried while a
 * timed-out POST/PUT is not. Total wall-clock for a hung read can therefore reach
 * roughly {@code (retries + 1) × timeout} plus backoff.
 */
public final class GetChatTimeoutException extends GetChatTransportException {

    private static final long serialVersionUID = 1L;

    private final Duration timeout;

    public GetChatTimeoutException(Duration timeout, String method, Throwable cause) {
        // Message keeps the millisecond form for parity with the node SDK.
        super("Request to '" + method + "' timed out after " + timeout.toMillis() + "ms", cause);
        this.timeout = timeout;
    }

    /** The per-attempt timeout that was exceeded. */
    public Duration timeout() {
        return timeout;
    }
}
