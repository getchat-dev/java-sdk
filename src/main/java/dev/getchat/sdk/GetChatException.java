package dev.getchat.sdk;

/** Base type for everything this SDK throws at runtime. */
public class GetChatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GetChatException(String message) {
        super(message);
    }

    public GetChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
