package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The result of {@link GetChat#sendMessage}. The send endpoint does not echo the
 * stored messages — even with a representation preference — so the honest return is
 * the list of created message ids, not a {@link Message}. The full envelope
 * ({@code status} plus {@code message_ids}) stays reachable through {@link #raw()}.
 *
 * <p>A lazy view over the response {@link JsonValue}, equal to another instance when
 * their underlying JSON is equal.
 */
public final class SentMessages {

    private final JsonValue raw;

    private SentMessages(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a send-message response envelope as a typed result. */
    public static SentMessages of(JsonValue raw) {
        return new SentMessages(raw);
    }

    /** The whole response envelope, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /**
     * The ids of the created messages, in send order. Unmodifiable and never
     * {@code null} — an empty list when the response carries none.
     */
    public List<String> messageIds() {
        List<JsonValue> values = raw.get("message_ids").values();
        List<String> out = new ArrayList<>(values.size());
        for (JsonValue value : values) {
            out.add(value.asString(""));
        }
        return Collections.unmodifiableList(out);
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof SentMessages other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code SentMessages{messageIds=[m-1, m-2]}}. */
    @Override
    public String toString() {
        return "SentMessages{messageIds=" + messageIds() + "}";
    }
}
