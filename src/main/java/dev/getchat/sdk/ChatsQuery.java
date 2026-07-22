package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed filters for {@link GetChat#listChats(ChatsQuery)}.
 *
 * <p>The typed input to {@code listChats}: {@link #asMap()} produces exactly the
 * keys the endpoint reads. The accepted filters
 * mirror {@code chat.list} in the backend's {@code openapi.yml}: {@code type},
 * {@code owner}, the four date-range strings ({@code created_from},
 * {@code created_to}, {@code last_message_from}, {@code last_message_to}),
 * {@code with_owner}, {@code with_owners}, {@code metadata}, plus {@code page}
 * and {@code limit}.
 *
 * <p><strong>The {@code limit} wart is preserved:</strong> when {@link
 * Builder#limit(int)} is never called the key is omitted and the endpoint
 * defaults {@code limit} to <em>1</em> (not a page size), matching the node SDK.
 * Pass a limit explicitly.
 */
public final class ChatsQuery {

    private final Map<String, Object> data;

    private ChatsQuery(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The filters as the {@code Map} {@code listChats} consumes. */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    /** Equal to another {@code ChatsQuery} producing the same map. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof ChatsQuery q && data.equals(q.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact dump for logs, e.g. {@code ChatsQuery{page=2, limit=10}}. */
    @Override
    public String toString() {
        return "ChatsQuery" + data;
    }

    /** Builder for {@link ChatsQuery}. */
    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();
        private final Map<String, Object> raw = new LinkedHashMap<>();

        private Builder() {}

        /** Page number (clamped to at least 1 by the endpoint; defaults to 1). */
        public Builder page(int page) {
            data.put("page", page);
            return this;
        }

        /**
         * Items per page (clamped to at most 1000 by the endpoint). Omit and the
         * endpoint defaults to 1 — see the class note.
         */
        public Builder limit(int limit) {
            data.put("limit", limit);
            return this;
        }

        /** Filter by chat type, given as a string. */
        public Builder type(String type) {
            data.put("type", type);
            return this;
        }

        /** Filter by chat type, given as a {@link Chat.Type}. */
        public Builder type(Chat.Type type) {
            data.put("type", type.wire());
            return this;
        }

        /** Filter by owner id. */
        public Builder owner(String owner) {
            data.put("owner", owner);
            return this;
        }

        /** Chats created at or after this {@code Y-m-d\TH:i:s} date-time. */
        public Builder createdFrom(String createdFrom) {
            data.put("created_from", createdFrom);
            return this;
        }

        /** Chats created before this {@code Y-m-d\TH:i:s} date-time. */
        public Builder createdTo(String createdTo) {
            data.put("created_to", createdTo);
            return this;
        }

        /** Chats whose last message is at or after this {@code Y-m-d\TH:i:s} date-time. */
        public Builder lastMessageFrom(String lastMessageFrom) {
            data.put("last_message_from", lastMessageFrom);
            return this;
        }

        /** Chats whose last message is before this {@code Y-m-d\TH:i:s} date-time. */
        public Builder lastMessageTo(String lastMessageTo) {
            data.put("last_message_to", lastMessageTo);
            return this;
        }

        /**
         * Embed each returned chat's owner as an {@code owner} object, which
         * populates {@link ChatDetails#owner()} (sent as 0/1). This is distinct
         * from {@link #withOwners(boolean)}: the plural flag instead attaches the
         * owners to a separate top-level {@code users} map on the raw response and
         * does <em>not</em> fill {@code owner()}. The backend force-disables this
         * flag when the {@link #owner(String)} filter is also set.
         */
        public Builder withOwner(boolean withOwner) {
            data.put("with_owner", withOwner);
            return this;
        }

        /** Add each chat's owner to a top-level {@code users} map (sent as 0/1). */
        public Builder withOwners(boolean withOwners) {
            data.put("with_owners", withOwners);
            return this;
        }

        /** Filter by metadata key/value pairs. A defensive copy is taken. */
        public Builder metadata(Map<String, Object> metadata) {
            data.put("metadata", new LinkedHashMap<>(metadata));
            return this;
        }

        /** Escape hatch for a query key with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            this.raw.put(key, value);
            return this;
        }

        public ChatsQuery build() {
            Map<String, Object> merged = new LinkedHashMap<>(data);
            // The escape hatch is applied last so it can override a typed field.
            merged.putAll(raw);
            return new ChatsQuery(merged);
        }
    }
}
