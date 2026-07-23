package dev.getchat.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a user's avatar — the {@code Avatar} schema in the
 * backend's {@code openapi.yml}. It is a {@code oneOf}: either a URL string (when an
 * image is set) or an object describing an auto-generated placeholder (initials on a
 * coloured background). This type unifies both forms behind one accessor set so a
 * caller does not have to branch on the JSON shape.
 *
 * <p>Used by {@link UserDetails#picture()} and {@link Participant#picture()}.
 *
 * <h2>Reading it</h2>
 * <p>{@link #isUrl()} tells the two forms apart. For the URL form {@link #url()} is
 * non-null and the object accessors ({@link #kind()}, {@link #color()},
 * {@link #initials()}) are all {@code null}; for the placeholder form it is the
 * other way round. {@code kind} is the only field the placeholder object requires;
 * {@code color} and {@code initials} may be absent and then come back {@code null}.
 * Unknown/future fields stay reachable through {@link #raw()}.
 *
 * <p>Like the other read models this is a <strong>lazy view</strong> over the
 * underlying {@link JsonValue}: accessors read on call. Two instances are equal when
 * their underlying JSON is equal.
 */
public final class Avatar {

    private final JsonValue raw;

    private Avatar(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap an avatar {@code JsonValue} (a URL string or a placeholder object) as a typed view. */
    public static Avatar of(JsonValue raw) {
        return new Avatar(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /** True when this avatar is a plain URL string; false when it is a generated placeholder. */
    public boolean isUrl() {
        return raw.isString();
    }

    /** The image URL, or {@code null} when this is a generated placeholder rather than a URL. */
    public @Nullable String url() {
        return raw.isString() ? raw.asString() : null;
    }

    /**
     * The placeholder kind (e.g. {@code "auto"}), or {@code null} when this avatar is a
     * URL. Required on the placeholder object, so non-null for a well-formed placeholder.
     */
    public @Nullable String kind() {
        return stringOrNull("kind");
    }

    /** The placeholder background colour, or {@code null} when this is a URL or the field is unset. */
    public @Nullable String color() {
        return stringOrNull("color");
    }

    /** The placeholder initials, or {@code null} when this is a URL or the field is unset. */
    public @Nullable String initials() {
        return stringOrNull("initials");
    }

    private @Nullable String stringOrNull(String field) {
        JsonValue v = raw.get(field);
        return v.isString() ? v.asString() : null;
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Avatar other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code Avatar{url=https://cdn/a.png}} or {@code Avatar{kind=auto, initials=BB}}. */
    @Override
    public String toString() {
        return isUrl() ? "Avatar{url=" + url() + "}" : "Avatar{kind=" + kind() + ", initials=" + initials() + "}";
    }
}
