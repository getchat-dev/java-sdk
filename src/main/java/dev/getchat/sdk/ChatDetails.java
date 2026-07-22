package dev.getchat.sdk;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a chat returned by the REST API — the
 * {@code ChatResource} shape in the backend's {@code openapi.yml}.
 *
 * <p>This is a <strong>lazy view</strong>, not an eager deserialisation: it wraps
 * the underlying {@link JsonValue} and each accessor reads from it on call. Fields
 * the backend adds in the future are never lost — reach them through
 * {@link #raw()}, which returns the chat's underlying {@code JsonValue}.
 *
 * <h2>Null policy</h2>
 * <ul>
 *   <li>{@link #id()} is a spec-required scalar and is returned non-null; if the
 *       backend violates the spec and omits it, a lenient empty string is
 *       returned rather than an exception (matching {@link JsonValue}'s lenient
 *       accessors).</li>
 *   <li>{@link #type()}, {@link #title()} and {@link #ownerId()} are
 *       nullable/optional in the spec and return {@code null} when absent.</li>
 *   <li>Dates ({@link #createdAt()}, {@link #updatedAt()}, {@link #lastMessageAt()})
 *       are ISO-8601 date-times; an absent or unparseable value yields
 *       {@code null}, never an exception.</li>
 *   <li>{@link #type()} is lenient: an unknown future enum value the SDK does not
 *       recognise (as well as {@code null} for legacy chats created without a
 *       type) maps to {@code null}.</li>
 *   <li>Sub-objects not yet typed ({@link #owner()}, {@link #metadata()}) come
 *       back as a chain-safe {@link JsonValue} (the {@linkplain JsonValue#isMissing()
 *       missing} sentinel when absent), never {@code null}.</li>
 * </ul>
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 */
public final class ChatDetails {

    private final JsonValue raw;

    private ChatDetails(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a chat {@code JsonValue} (a {@code ChatResource} object) as a typed view. */
    public static ChatDetails of(JsonValue raw) {
        return new ChatDetails(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** External chat id (spec-required; lenient empty string if the backend omits it). */
    public String id() {
        return raw.get("id").asString("");
    }

    /**
     * Chat type, or {@code null} — for a legacy chat created without one, or for a
     * value the SDK's {@link Chat.Type} enum does not recognise (lenient by design
     * so a new backend type never throws).
     */
    public Chat.@Nullable Type type() {
        return Chat.Type.fromWire(stringOrNull("type"));
    }

    /** Chat title, or {@code null} when unset (the spec marks it nullable). */
    public @Nullable String title() {
        return stringOrNull("title");
    }

    /** Creation time, or {@code null} when absent or unparseable. */
    public @Nullable Instant createdAt() {
        return parseIso(raw.get("created_at"));
    }

    /** Last-update time, or {@code null} when absent or unparseable. */
    public @Nullable Instant updatedAt() {
        return parseIso(raw.get("updated_at"));
    }

    /** Time of the chat's most recent message, or {@code null} when the chat has none. */
    public @Nullable Instant lastMessageAt() {
        return parseIso(raw.get("last_message_at"));
    }

    /**
     * The chat's most recent message, or {@code null} when it was not requested
     * (via {@code with_last_message}) or the chat has none.
     */
    public @Nullable Message lastMessage() {
        JsonValue lm = raw.get("last_message");
        return lm.isObject() ? Message.of(lm) : null;
    }

    /** External owner id, or {@code null} when the chat has no owner. */
    public @Nullable String ownerId() {
        return stringOrNull("owner_id");
    }

    /**
     * The chat owner as a raw {@link JsonValue} (present only with
     * {@code with_owner}); the {@linkplain JsonValue#isMissing() missing} sentinel
     * when absent.
     *
     * <p>TODO(stage 2): return a typed {@code UserDetails} once the user models
     * land; users stay on the raw channel for now.
     */
    public JsonValue owner() {
        return raw.get("owner");
    }

    /**
     * Chat metadata as a raw {@link JsonValue} of scalar values (present only when
     * non-empty); the {@linkplain JsonValue#isMissing() missing} sentinel when
     * absent. Use {@link JsonValue#toMap()} for a plain {@code Map}.
     */
    public JsonValue metadata() {
        return raw.get("metadata");
    }

    private @Nullable String stringOrNull(String field) {
        JsonValue v = raw.get(field);
        return v.isString() ? v.asString() : null;
    }

    /**
     * Parse an ISO-8601 date-time (with or without an offset) to an {@link Instant},
     * or {@code null} when the value is absent or does not parse. Lenient on
     * purpose: a malformed date must never crash a response read.
     */
    static @Nullable Instant parseIso(JsonValue value) {
        if (!value.isString()) {
            return null;
        }
        String s = value.asString();
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException withOffset) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException asInstant) {
                return null;
            }
        }
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChatDetails other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code ChatDetails{id=support-42, type=GROUP, title=Support}}. */
    @Override
    public String toString() {
        return "ChatDetails{id=" + id() + ", type=" + type() + ", title=" + title() + "}";
    }
}
