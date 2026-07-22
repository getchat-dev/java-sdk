package dev.getchat.sdk;

/**
 * Base type for everything this SDK throws at runtime, and a closed hierarchy: the
 * only subtypes are the ones listed in {@code permits}, so a {@code switch} or a
 * chain of {@code catch} clauses over them can be exhaustive.
 *
 * <p>{@code GetChatException} is also thrown on its own — every deliberate input or
 * configuration check ({@code chat id isn't passed}, an empty message text, a
 * builder missing a required field) surfaces as the bare base type. So catching the
 * base type alone still covers the whole SDK, and each subtype narrows the cause:
 *
 * <ul>
 *   <li><strong>the bare {@code GetChatException}</strong> — a mistake in the
 *       calling code: bad input, bad configuration, or misuse of a {@link JsonValue}.
 *       Fix the call; retrying will not help.</li>
 *   <li>{@link GetChatTransportException} (including {@link GetChatTimeoutException})
 *       — the request never got a reply. Worth retrying.</li>
 *   <li>{@link GetChatApiException} — the server answered with an error status;
 *       inspect {@link GetChatApiException#status() status()} to decide what to do.</li>
 *   <li>{@link GetChatSerializationException} — a JSON body could not be written or
 *       read, so for a response the server broke the agreed contract.</li>
 *   <li>{@link GetChatInterruptedException} — the calling thread was interrupted;
 *       stop, do not retry.</li>
 * </ul>
 */
public sealed class GetChatException extends RuntimeException
        permits GetChatApiException,
                GetChatTransportException,
                GetChatSerializationException,
                GetChatInterruptedException {

    private static final long serialVersionUID = 1L;

    public GetChatException(String message) {
        super(message);
    }

    public GetChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
