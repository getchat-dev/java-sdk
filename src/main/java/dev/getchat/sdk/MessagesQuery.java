package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Typed filters and pagination for {@link GetChatClient#listMessages(String, MessagesQuery)}.
 *
 * <p>The typed input to {@code listMessages}: {@link #asMap()} produces exactly
 * the keys the endpoint reads ({@code extra}, {@code isDeleted}, {@code isEdited},
 * {@code with_users}) plus {@code page} and {@code limit}. Only the fields you set
 * appear — an unset boolean is left off entirely, which the backend reads as
 * "don't filter on this", and an unset {@code page}/{@code limit} falls back to
 * the method default (page 1, 50 per page).
 *
 * <p>The typed form removes two footguns: the camelCase/underscore confusion
 * between {@code with_users} and {@code withUsers}, and untyped flag values. For
 * a key without a typed setter, use {@link Builder#set(String, Object)}.
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
     * The filters as the {@code Map} {@code listMessages} consumes. Keys present
     * only for fields that were set; unset booleans and unset {@code page}/{@code
     * limit} are omitted.
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
        private @Nullable Integer page;
        private @Nullable Integer limit;
        private @Nullable Boolean deleted;
        private @Nullable Boolean edited;
        private @Nullable Boolean withUsers;

        private Builder() {}

        /** Page number; must be at least 1, checked at {@link #build()}. Unset defaults to 1. */
        public Builder page(int page) {
            this.page = page;
            return this;
        }

        /** Items per page; must be in {@code 1..1000}, checked at {@link #build()}. Unset defaults to 50. */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

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
            // Validate the typed page/limit; the raw set() channel stays unchecked
            // (the escape hatch), and GetChatClient clamps as a second defence.
            if (page != null && page < 1) {
                throw new GetChatException("page must be at least 1");
            }
            if (limit != null && (limit < 1 || limit > 1000)) {
                throw new GetChatException("limit must be between 1 and 1000");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (page != null) {
                data.put("page", page);
            }
            if (limit != null) {
                data.put("limit", limit);
            }
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
