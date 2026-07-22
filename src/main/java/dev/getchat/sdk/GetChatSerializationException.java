package dev.getchat.sdk;

/**
 * A JSON body could not be written or parsed: the request payload failed to
 * serialise, or a response the server said was JSON did not parse.
 *
 * <p>Unlike {@link GetChatTransportException}, retrying will not help — the bytes
 * on the wire are the problem, which for a response means the server contract is
 * broken. Contrast {@link GetChatApiException}, which is a well-formed error
 * response the server chose to send.
 */
public final class GetChatSerializationException extends GetChatException {

    private static final long serialVersionUID = 1L;

    public GetChatSerializationException(String message) {
        super(message);
    }

    public GetChatSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
