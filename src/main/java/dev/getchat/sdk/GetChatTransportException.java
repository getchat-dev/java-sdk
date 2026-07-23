package dev.getchat.sdk;

/**
 * The request never completed at the transport level — a connection could not be
 * established (connect refused, DNS failure) or the exchange broke down mid-flight
 * (a socket I/O error). No HTTP status was ever received.
 *
 * <p>A transport failure is the retryable case: the SDK replays a read on its own,
 * and a caller catching this can safely try again. {@link GetChatTimeoutException}
 * is a transport failure too and extends this type. Contrast
 * {@link GetChatApiException} (the server answered, with an error status) and
 * {@link GetChatSerializationException} (the exchange completed but the JSON body
 * could not be handled).
 */
public sealed class GetChatTransportException extends GetChatException permits GetChatTimeoutException {

    private static final long serialVersionUID = 1L;

    public GetChatTransportException(String message) {
        super(message);
    }

    public GetChatTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
