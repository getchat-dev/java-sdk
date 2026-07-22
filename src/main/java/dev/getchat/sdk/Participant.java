package dev.getchat.sdk;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a chat participant returned by
 * {@link GetChat#listParticipants} — the {@code ParticipantResource} shape in the
 * backend's {@code openapi.yml}.
 *
 * <p>{@code ParticipantResource} is a <strong>distinct schema</strong> from
 * {@code UserResource}: it carries the same identity fields but has <em>no</em>
 * {@code metadata}, so this is a separate model rather than a reuse of
 * {@link UserDetails}. Note also that the participant <em>list</em> endpoint does
 * not embed per-chat rights or roles in the element — those live behind the
 * dedicated participant-rights endpoints (reach them via
 * {@link GetChat#requestApi}).
 *
 * <p>Like the other read models this is a <strong>lazy view</strong> over the
 * underlying {@link JsonValue}: accessors read on call and unknown/future fields
 * stay reachable through {@link #raw()}.
 *
 * <h2>Null policy</h2>
 * <ul>
 *   <li>{@link #id()} and {@link #name()} are spec-required scalars, returned
 *       non-null (lenient empty string if the backend omits them).</li>
 *   <li>{@link #email()} and {@link #link()} are optional and return {@code null}
 *       when absent.</li>
 *   <li>Dates ({@link #createdAt()}, {@link #updatedAt()}) are ISO-8601 date-times
 *       parsed to a {@code @Nullable Instant}; an absent or unparseable value yields
 *       {@code null}.</li>
 *   <li>{@link #picture()} is a typed {@link Avatar} (a URL string <em>or</em> a
 *       generated-placeholder object), or {@code null} when absent.</li>
 * </ul>
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 */
public final class Participant {

    private final JsonValue raw;

    private Participant(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a participant {@code JsonValue} (a {@code ParticipantResource} object) as a typed view. */
    public static Participant of(JsonValue raw) {
        return new Participant(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** External user id (spec-required; lenient empty string if the backend omits it). */
    public String id() {
        return raw.get("id").asString("");
    }

    /** Participant display name (spec-required; lenient empty string if the backend omits it). */
    public String name() {
        return raw.get("name").asString("");
    }

    /** Participant email, or {@code null} when unset. */
    public @Nullable String email() {
        JsonValue v = raw.get("email");
        return v.isString() ? v.asString() : null;
    }

    /** Participant profile link, or {@code null} when unset. */
    public @Nullable String link() {
        JsonValue v = raw.get("link");
        return v.isString() ? v.asString() : null;
    }

    /**
     * The participant's avatar as a typed {@link Avatar} — either a URL string or a
     * generated placeholder ({@code kind}/{@code color}/{@code initials}),
     * distinguished with {@link Avatar#isUrl()}. {@code null} when the field is absent.
     */
    public @Nullable Avatar picture() {
        JsonValue p = raw.get("picture");
        return (p.isString() || p.isObject()) ? Avatar.of(p) : null;
    }

    /** Creation time, or {@code null} when absent or unparseable. */
    public @Nullable Instant createdAt() {
        return ChatDetails.parseIso(raw.get("created_at"));
    }

    /** Last-update time, or {@code null} when absent or unparseable. */
    public @Nullable Instant updatedAt() {
        return ChatDetails.parseIso(raw.get("updated_at"));
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Participant other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code Participant{id=u-1, name=Alice}}. */
    @Override
    public String toString() {
        return "Participant{id=" + id() + ", name=" + name() + "}";
    }
}
