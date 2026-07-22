package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The viewing user for {@link GetChatUrlSigner#url} / {@link GetChatUrlSigner#urlByChatId}, and the
 * author for the REST methods.
 *
 * <p>Which fields survive depends on the call: the URL flows whitelist
 * {@code id, name, email, picture, rights, session} and drop everything else
 * (including {@code link} and {@code isBot}), while the REST endpoints accept
 * the wider set.
 */
public final class User {

    private final Map<String, Object> data;

    private User(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A user carrying nothing but an id. */
    public static User of(String id) {
        return builder().id(id).build();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    public @Nullable Object get(String key) {
        return data.get(key);
    }

    /**
     * Equal to another {@code User} with the same fields, by content — the order
     * they were added does not matter. Insertion order affects only the emitted
     * URL/wire form, not equality.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof User u && data.equals(u.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact field dump for logs, e.g. {@code User{id=10, name=Fred}}. */
    @Override
    public String toString() {
        return "User" + data;
    }

    /** Builder for {@link User}. */
    public static final class Builder {

        private final Map<String, Object> data = new LinkedHashMap<>();

        private Builder() {}

        public Builder id(String id) {
            data.put("id", id);
            return this;
        }

        public Builder name(String name) {
            data.put("name", name);
            return this;
        }

        public Builder email(String email) {
            data.put("email", email);
            return this;
        }

        public Builder picture(String picture) {
            data.put("picture", picture);
            return this;
        }

        public Builder link(String link) {
            data.put("link", link);
            return this;
        }

        /**
         * Anonymous-session token. Only used when no {@code id} is set — the URL
         * builders generate a random 40-character session in that case.
         */
        public Builder session(String session) {
            data.put("session", session);
            return this;
        }

        public Builder isBot(boolean isBot) {
            data.put("is_bot", isBot);
            return this;
        }

        public Builder rights(Rights rights) {
            data.put("rights", rights.asMap());
            return this;
        }

        /** Escape hatch for a field with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            data.put(key, value);
            return this;
        }

        public User build() {
            return new User(new LinkedHashMap<>(data));
        }
    }
}
