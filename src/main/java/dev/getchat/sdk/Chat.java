package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A chat reference. Only {@code id, title, create, type, metadata} survive
 * normalisation; anything else is dropped before signing.
 */
public final class Chat {

    /** Chat kind, as accepted by {@code chat.create}. */
    public enum Type {
        PRIVATE("private"),
        GROUP("group"),
        /**
         * A group past the 255-participant mark. Behaves less like a conversation
         * and more like a comment thread.
         */
        SUPERGROUP("supergroup"),
        CHANNEL("channel");

        private final String wire;

        Type(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        /**
         * The {@code Type} for a wire string, or {@code null} when it is {@code null}
         * or an unrecognised value. Lenient by design: a chat with no type (legacy)
         * or a type a newer backend introduced maps to {@code null} rather than
         * throwing, so reading a {@link ChatDetails} never fails on an unknown type.
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

    private final Map<String, Object> data;

    private Chat(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A chat carrying nothing but an id. */
    public static Chat of(String id) {
        return builder().id(id).build();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    public @Nullable Object get(String key) {
        return data.get(key);
    }

    /**
     * Equal to another {@code Chat} with the same fields, by content — the order
     * they were added does not matter. Insertion order affects only the emitted
     * URL/wire form, not equality.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Chat c && data.equals(c.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact field dump for logs, e.g. {@code Chat{id=support-42}}. */
    @Override
    public String toString() {
        return "Chat" + data;
    }

    /** Builder for {@link Chat}. */
    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder() {}

        public Builder id(String id) {
            data.put("id", id);
            return this;
        }

        public Builder title(String title) {
            data.put("title", title);
            return this;
        }

        /** Create the chat if it does not exist yet. */
        public Builder create(boolean create) {
            data.put("create", create);
            return this;
        }

        public Builder type(Type type) {
            data.put("type", type.wire());
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            data.put("metadata", new LinkedHashMap<>(metadata));
            return this;
        }

        /** Escape hatch for a field with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            data.put(key, value);
            return this;
        }

        public Chat build() {
            return new Chat(new LinkedHashMap<>(data));
        }
    }
}
