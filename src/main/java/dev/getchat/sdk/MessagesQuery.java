package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed filters for {@link GetChat#getMessagesFromChat(String, MessagesQuery, int, int)}.
 *
 * <p>A convenience front end over the {@code Map} the endpoint already accepts:
 * {@link #asMap()} produces exactly the keys {@code getMessagesFromChat(String,
 * Map, int, int)} reads ({@code extra}, {@code isDeleted}, {@code isEdited},
 * {@code with_users}), so the typed and raw-map paths put identical bytes on the
 * wire. Only the fields you set appear — an unset boolean is left off entirely,
 * which the backend reads as "don't filter on this".
 *
 * <p>This replaces two footguns of the raw-map form: the camelCase/underscore
 * confusion between {@code with_users} and {@code withUsers}, and untyped flag
 * values.
 */
public final class MessagesQuery {

    private final Map<String, Object> data;

    private MessagesQuery(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The filters as the {@code Map} {@code getMessagesFromChat} consumes. Keys
     * present only for fields that were set; unset booleans are omitted.
     */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    /** Equal to another {@code MessagesQuery} producing the same map. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof MessagesQuery q && data.equals(q.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact dump for logs, e.g. {@code MessagesQuery{isDeleted=false}}. */
    @Override
    public String toString() {
        return "MessagesQuery" + data;
    }

    /** Builder for {@link MessagesQuery}. */
    public static final class Builder {

        private final Map<String, Object> extra = new LinkedHashMap<>();
        private final Map<String, Object> raw = new LinkedHashMap<>();
        private @Nullable Boolean deleted;
        private @Nullable Boolean edited;
        private @Nullable Boolean withUsers;

        private Builder() {}

        /**
         * Filter by {@code extra} fields (scalar values). Replaces any extra
         * entries accumulated so far; non-scalar values are dropped downstream.
         */
        public Builder extra(@Nullable Map<String, Object> extra) {
            this.extra.clear();
            if (extra != null) {
                this.extra.putAll(extra);
            }
            return this;
        }

        /** Add a single {@code extra} filter entry. */
        public Builder extra(String key, @Nullable Object value) {
            this.extra.put(key, value);
            return this;
        }

        /** {@code true} — only deleted messages; {@code false} — only live ones. */
        public Builder deleted(boolean deleted) {
            this.deleted = deleted;
            return this;
        }

        /** {@code true} — only edited messages; {@code false} — only never-edited ones. */
        public Builder edited(boolean edited) {
            this.edited = edited;
            return this;
        }

        /** Include the {@code users} map alongside the messages. */
        public Builder withUsers(boolean withUsers) {
            this.withUsers = withUsers;
            return this;
        }

        /** Escape hatch for a query key with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            this.raw.put(key, value);
            return this;
        }

        public MessagesQuery build() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (!extra.isEmpty()) {
                data.put("extra", new LinkedHashMap<>(extra));
            }
            if (deleted != null) {
                data.put("isDeleted", deleted);
            }
            if (edited != null) {
                data.put("isEdited", edited);
            }
            if (withUsers != null) {
                data.put("with_users", withUsers);
            }
            // The escape hatch is applied last so it can override a typed field.
            data.putAll(raw);
            return new MessagesQuery(data);
        }
    }
}
