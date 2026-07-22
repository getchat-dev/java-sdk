package dev.getchat.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The result of {@link GetChat#updateMessage}. The backend always reports whether
 * the edit changed anything ({@link #isUpdated()}), but only echoes the stored
 * message when the caller asked for it via
 * {@link UpdateMessageOptions.Builder#returnResource(boolean)} (which sends
 * {@code Prefer: return=representation}); otherwise {@link #message()} is
 * {@code null}. Both signals — plus the raw envelope — are kept so the return type
 * is honest about what came back.
 *
 * <p>A lazy view over the response {@link JsonValue}, equal to another instance when
 * their underlying JSON is equal.
 */
public final class UpdatedMessage {

    private final JsonValue raw;

    private UpdatedMessage(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap an update-message response envelope as a typed result. */
    public static UpdatedMessage of(JsonValue raw) {
        return new UpdatedMessage(raw);
    }

    /** The whole response envelope, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** Whether the edit actually changed the message. */
    public boolean isUpdated() {
        return raw.get("is_updated").asBoolean(false);
    }

    /**
     * The updated message, or {@code null} when it was not requested (no
     * {@code returnResource(true)} / {@code Prefer: return=representation}).
     */
    public @Nullable Message message() {
        JsonValue m = raw.get("message");
        return m.isObject() ? Message.of(m) : null;
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof UpdatedMessage other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code UpdatedMessage{isUpdated=true, hasMessage=false}}. */
    @Override
    public String toString() {
        return "UpdatedMessage{isUpdated=" + isUpdated() + ", hasMessage=" + (message() != null) + "}";
    }
}
