package dev.getchat.sdk;

/**
 * The calling thread was interrupted while a request was in flight or while the
 * SDK was waiting between retries.
 *
 * <p>This is deliberately <em>not</em> a {@link GetChatTransportException}:
 * retrying an interruption is wrong, because interruption is a request to stop,
 * not a network fault. The SDK re-asserts the thread's interrupt flag
 * ({@link Thread#interrupt()}) before throwing, so the interruption is not
 * swallowed.
 */
public final class GetChatInterruptedException extends GetChatException {

    private static final long serialVersionUID = 1L;

    public GetChatInterruptedException(String message) {
        super(message);
    }

    public GetChatInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
