package dev.getchat.sdk;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a user returned by the REST API — the
 * {@code UserResource} shape in the backend's {@code openapi.yml}. This is what
 * {@link GetChat#getUser(String)}, {@link GetChat#createUser} and
 * {@link GetChat#updateUser} hand back.
 *
 * <p>Like {@link ChatDetails}, this is a <strong>lazy view</strong>, not an eager
 * deserialisation: it wraps the underlying {@link JsonValue} and each accessor reads
 * from it on call. Fields the backend adds in the future are never lost — reach them
 * through {@link #raw()}, which returns the user's underlying {@code JsonValue}.
 *
 * <h2>Timestamps</h2>
 * <p>Unlike {@link Message} (Unix epoch seconds), user timestamps are ISO-8601
 * date-times, the same as {@link ChatDetails}; {@link #createdAt()} /
 * {@link #updatedAt()} parse them to an {@link Instant}, and an absent or
 * unparseable value yields {@code null}.
 *
 * <h2>Null policy</h2>
 * <ul>
 *   <li>{@link #id()} and {@link #name()} are spec-required scalars and are returned
 *       non-null; if the backend violates the spec and omits one, a lenient empty
 *       string is returned rather than an exception.</li>
 *   <li>{@link #email()} and {@link #link()} are optional and return {@code null}
 *       when absent.</li>
 *   <li>Dates ({@link #createdAt()}, {@link #updatedAt()}) are {@code @Nullable
 *       Instant}; an absent or unparseable value yields {@code null}, never an
 *       exception.</li>
 *   <li>{@link #picture()} (a polymorphic {@code Avatar}: a URL string <em>or</em> a
 *       generated-placeholder object) and {@link #metadata()} come back as a
 *       chain-safe {@link JsonValue} (the {@linkplain JsonValue#isMissing() missing}
 *       sentinel when absent), never {@code null}.</li>
 * </ul>
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 */
public final class UserDetails {

    private final JsonValue raw;

    private UserDetails(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a user {@code JsonValue} (a {@code UserResource} object) as a typed view. */
    public static UserDetails of(JsonValue raw) {
        return new UserDetails(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** External user id (spec-required; lenient empty string if the backend omits it). */
    public String id() {
        return raw.get("id").asString("");
    }

    /** User display name (spec-required; lenient empty string if the backend omits it). */
    public String name() {
        return raw.get("name").asString("");
    }

    /** User email, or {@code null} when unset. */
    public @Nullable String email() {
        JsonValue v = raw.get("email");
        return v.isString() ? v.asString() : null;
    }

    /** User profile link, or {@code null} when unset. */
    public @Nullable String link() {
        JsonValue v = raw.get("link");
        return v.isString() ? v.asString() : null;
    }

    /**
     * The user's avatar as a chain-safe {@link JsonValue}: a polymorphic
     * {@code Avatar} that is either a URL string (when an image is set) or an object
     * describing a generated placeholder ({@code kind}/{@code color}/{@code initials}).
     * The {@linkplain JsonValue#isMissing() missing} sentinel when absent — inspect
     * with {@link JsonValue#isString()} / {@link JsonValue#isObject()}.
     */
    public JsonValue picture() {
        return raw.get("picture");
    }

    /** Creation time, or {@code null} when absent or unparseable. */
    public @Nullable Instant createdAt() {
        return ChatDetails.parseIso(raw.get("created_at"));
    }

    /** Last-update time, or {@code null} when absent or unparseable. */
    public @Nullable Instant updatedAt() {
        return ChatDetails.parseIso(raw.get("updated_at"));
    }

    /**
     * User metadata as a chain-safe {@link JsonValue} of scalar (string) values
     * (present only when set); the {@linkplain JsonValue#isMissing() missing}
     * sentinel when absent. Use {@link JsonValue#toMap()} for a plain {@code Map}.
     */
    public JsonValue metadata() {
        return raw.get("metadata");
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof UserDetails other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code UserDetails{id=u-1, name=Alice}}. */
    @Override
    public String toString() {
        return "UserDetails{id=" + id() + ", name=" + name() + "}";
    }
}
