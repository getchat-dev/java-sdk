package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * An inline action button attached to a message.
 *
 * <p>Mirrors the {@code Button} schema in the backend's {@code openapi.yml}:
 * {@code type} and {@code label} are required, {@code action}, {@code state} and
 * {@code style} are optional. As with the other input builders nothing is
 * validated here — the backend is the source of truth and rejects a malformed
 * button with a 422; the typed enums simply keep the accepted values in reach.
 *
 * <p>Use it wherever a message takes buttons — {@link UpdateMessageOptions#buttons}
 * and {@link SendMessageOptions#buttons} — instead of hand-writing the equivalent
 * {@code Map}.
 */
public final class Button {

    /** Button behaviour, as accepted by {@code button.type}. */
    public enum Type {
        URL("url"),
        CALL("call"),
        LOCAL("local"),
        REMOTE("remote");

        private final String wire;

        Type(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /**
         * The {@code Type} for a wire string, or {@code null} when it is {@code null}
         * or an unrecognised value. Lenient by design so reading a {@link ButtonDetails}
         * never throws on a value a newer backend introduced.
         */
        public static @Nullable Type fromWire(@Nullable String wire) {
            if (wire == null) {
                return null;
            }
            for (Type type : values()) {
                if (type.wire.equals(wire)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** Button interaction state. */
    public enum State {
        DEFAULT("default"),
        LOADING("loading"),
        DISABLED("disabled");

        private final String wire;

        State(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The {@code State} for a wire string, or {@code null} when absent or unrecognised (see {@link Type#fromWire}). */
        public static @Nullable State fromWire(@Nullable String wire) {
            if (wire == null) {
                return null;
            }
            for (State state : values()) {
                if (state.wire.equals(wire)) {
                    return state;
                }
            }
            return null;
        }
    }

    /** Button colour treatment. */
    public enum Style {
        PRIMARY("primary"),
        POSITIVE("positive"),
        NEGATIVE("negative"),
        NEUTRAL("neutral");

        private final String wire;

        Style(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /** The {@code Style} for a wire string, or {@code null} when absent or unrecognised (see {@link Type#fromWire}). */
        public static @Nullable Style fromWire(@Nullable String wire) {
            if (wire == null) {
                return null;
            }
            for (Style style : values()) {
                if (style.wire.equals(wire)) {
                    return style;
                }
            }
            return null;
        }
    }

    private final Map<String, Object> data;

    private Button(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A button carrying only the two required fields. */
    public static Button of(Type type, String label) {
        return builder().type(type).label(label).build();
    }

    /** The button as the {@code Map} that goes on the wire. */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    /**
     * Equal to another {@code Button} with the same fields, by content — the order
     * they were added does not matter. Insertion order affects only the emitted
     * URL/wire form, not equality.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Button b && data.equals(b.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact field dump for logs, e.g. {@code Button{type=url, label=Open}}. */
    @Override
    public String toString() {
        return "Button" + data;
    }

    /** Builder for {@link Button}. */
    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder() {}

        public Builder type(Type type) {
            data.put("type", type.wire());
            return this;
        }

        public Builder label(String label) {
            data.put("label", label);
            return this;
        }

        public Builder action(String action) {
            data.put("action", action);
            return this;
        }

        public Builder state(State state) {
            data.put("state", state.wire());
            return this;
        }

        public Builder style(Style style) {
            data.put("style", style.wire());
            return this;
        }

        /** Escape hatch for a field with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            data.put(key, value);
            return this;
        }

        public Button build() {
            return new Button(new LinkedHashMap<>(data));
        }
    }
}
