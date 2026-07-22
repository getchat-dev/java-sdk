package dev.getchat.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over an inline button carried by a message — the
 * {@code Button} schema in the backend's {@code openapi.yml}, as returned inside a
 * {@code MessageResource}. This is the reading counterpart of the {@link Button}
 * input builder and reuses its {@link Button.Type}, {@link Button.State} and
 * {@link Button.Style} enums.
 *
 * <p>Returned by {@link Message#buttons()}. Like the other read models this is a
 * <strong>lazy view</strong> over the underlying {@link JsonValue}: accessors read on
 * call and unknown/future fields stay reachable through {@link #raw()}.
 *
 * <h2>Null policy</h2>
 * <ul>
 *   <li>{@link #label()} is a spec-required scalar and is returned non-null (lenient
 *       empty string if the backend omits it, matching the other read models).</li>
 *   <li>{@link #action()} is optional and returns {@code null} when absent.</li>
 *   <li>{@link #type()}, {@link #state()} and {@link #style()} are enums and are
 *       lenient: an absent value <em>or</em> a wire string this SDK version does not
 *       recognise maps to {@code null} rather than throwing.</li>
 * </ul>
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 */
public final class ButtonDetails {

    private final JsonValue raw;

    private ButtonDetails(JsonValue raw) {
        this.raw = raw;
    }

    /** Wrap a button {@code JsonValue} (a {@code Button} object) as a typed view. */
    public static ButtonDetails of(JsonValue raw) {
        return new ButtonDetails(raw);
    }

    /** The underlying JSON, for fields without a typed accessor. */
    public JsonValue raw() {
        return raw;
    }

    /**
     * Button behaviour, or {@code null} — for an absent value or one this SDK's
     * {@link Button.Type} enum does not recognise (lenient by design).
     */
    public Button.@Nullable Type type() {
        return Button.Type.fromWire(stringOrNull("type"));
    }

    /** Button label (spec-required; lenient empty string if the backend omits it). */
    public String label() {
        return raw.get("label").asString("");
    }

    /** Button action payload, or {@code null} when unset. */
    public @Nullable String action() {
        return stringOrNull("action");
    }

    /**
     * Button interaction state, or {@code null} — for an absent value or one this
     * SDK's {@link Button.State} enum does not recognise (lenient by design).
     */
    public Button.@Nullable State state() {
        return Button.State.fromWire(stringOrNull("state"));
    }

    /**
     * Button colour treatment, or {@code null} — for an absent value or one this
     * SDK's {@link Button.Style} enum does not recognise (lenient by design).
     */
    public Button.@Nullable Style style() {
        return Button.Style.fromWire(stringOrNull("style"));
    }

    private @Nullable String stringOrNull(String field) {
        JsonValue v = raw.get(field);
        return v.isString() ? v.asString() : null;
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ButtonDetails other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code ButtonDetails{type=URL, label=Open}}. */
    @Override
    public String toString() {
        return "ButtonDetails{type=" + type() + ", label=" + label() + "}";
    }
}
