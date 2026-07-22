package dev.getchat.sdk;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a message returned by the REST API — the
 * {@code MessageResource} shape in the backend's {@code openapi.yml}.
 *
 * <p>Like {@link ChatDetails}, this is a <strong>lazy view</strong> over the
 * underlying {@link JsonValue}: accessors read on call and unknown fields stay
 * reachable through {@link #raw()}.
 *
 * <h2>The sender</h2>
 * <p>A {@code MessageResource} carries only the sender's external {@link #userId()},
 * not an embedded user object. When a list request asks for {@code with_users}, the
 * full user objects live in a sibling {@code users} map on the list envelope (keyed
 * by the backend's <em>internal</em> id, which does not match the external
 * {@code user_id}); reach it via {@code page.raw().get("users")}. There is no
 * {@code user()} accessor here because the schema has no such field.
 *
 * <h2>Timestamps</h2>
 * <p>Unlike {@link ChatDetails}, message timestamps are <strong>Unix epoch seconds</strong>
 * (integers), not ISO strings; {@link #createdAt()} / {@link #updatedAt()} convert
 * them to {@link Instant}. A non-numeric or absent value yields {@code null}
 * ({@code updated_at} is null until the first edit).
 *
 * <h2>Null policy</h2>
 * <ul>
 *   <li>{@link #id()} and {@link #userId()} are spec-required scalars, returned
 *       non-null (lenient empty string if the backend omits them).</li>
 *   <li>{@link #text()} is nullable (null for deleted messages) and
 *       {@link #recipientId()} is optional; both return {@code null} when absent.</li>
 *   <li>{@link #isDeleted()}, {@link #isEdited()}, {@link #seq()} and
 *       {@link #versions()} are spec-required and return primitives.</li>
 *   <li>{@link #extra()} is a chain-safe {@link JsonValue} — note the backend
 *       serialises an empty {@code extra} as a JSON array {@code []}, not
 *       {@code {}}.</li>
 * </ul>
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 */
public final class Message {

    private final JsonValue raw;

    private Message(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a message {@code JsonValue} (a {@code MessageResource} object) as a typed view. */
    public static Message of(JsonValue raw) {
        return new Message(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** Message id (spec-required; lenient empty string if the backend omits it). */
    public String id() {
        return raw.get("id").asString("");
    }

    /** Monotonic per-chat sequence number — the sort key (0 if absent). */
    public long seq() {
        return raw.get("seq").asLong(0);
    }

    /**
     * The sender's external id when known (the internal id for legacy messages
     * without one); lenient empty string if the backend omits it.
     */
    public String userId() {
        return raw.get("user_id").asString("");
    }

    /** Message text, or {@code null} for a deleted message. */
    public @Nullable String text() {
        JsonValue v = raw.get("text");
        return v.isString() ? v.asString() : null;
    }

    /** Creation time (from Unix epoch seconds), or {@code null} when absent/non-numeric. */
    public @Nullable Instant createdAt() {
        return epochSeconds(raw.get("created_at"));
    }

    /** Last-edit time (from Unix epoch seconds), or {@code null} until the first edit. */
    public @Nullable Instant updatedAt() {
        return epochSeconds(raw.get("updated_at"));
    }

    /** Whether the message is soft-deleted. */
    public boolean isDeleted() {
        return raw.get("is_deleted").asBoolean(false);
    }

    /** Whether the message has been edited. */
    public boolean isEdited() {
        return raw.get("is_edited").asBoolean(false);
    }

    /** Number of stored previous versions. */
    public int versions() {
        return raw.get("versions").asInt(0);
    }

    /**
     * The message's {@code extra} fields as a chain-safe {@link JsonValue}. Note
     * the backend serialises an empty {@code extra} as a JSON array {@code []}
     * (not {@code {}}); {@link JsonValue#toMap()} still yields an empty map either
     * way.
     */
    public JsonValue extra() {
        return raw.get("extra");
    }

    /** External recipient id, or {@code null} when unset. */
    public @Nullable String recipientId() {
        JsonValue v = raw.get("recipient_id");
        return v.isString() ? v.asString() : null;
    }

    /**
     * The message's inline buttons as raw {@link JsonValue}s, in order. Empty (never
     * {@code null}) when the message has none.
     */
    public List<JsonValue> buttons() {
        return raw.get("buttons").values();
    }

    private static @Nullable Instant epochSeconds(JsonValue value) {
        if (!value.isNumber()) {
            return null;
        }
        return Instant.ofEpochSecond(value.asLong(0));
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Message other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code Message{id=m-1, seq=7, userId=u-1, deleted=false}}. */
    @Override
    public String toString() {
        return "Message{id=" + id() + ", seq=" + seq() + ", userId=" + userId() + ", deleted=" + isDeleted() + "}";
    }
}
