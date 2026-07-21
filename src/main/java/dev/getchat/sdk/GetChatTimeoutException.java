package dev.getchat.sdk;

/**
 * A request exceeded its per-attempt timeout.
 *
 * <p>As in the node SDK this counts as a transport error, so a timed-out
 * GET/DELETE is retried while a timed-out POST/PUT is not. Total wall-clock for
 * a hung read can therefore reach roughly {@code (retries + 1) × timeout} plus
 * backoff.
 */
public class GetChatTimeoutException extends GetChatException {

    private static final long serialVersionUID = 1L;

    private final long timeoutMs;

    public GetChatTimeoutException(long timeoutMs, String method, Throwable cause) {
        super("Request to '" + method + "' timed out after " + timeoutMs + "ms", cause);
        this.timeoutMs = timeoutMs;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
