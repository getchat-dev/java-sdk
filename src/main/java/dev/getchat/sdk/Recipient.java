package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A chat participant.
 *
 * <p>The two URL builders whitelist different subsets: {@link GetChatUrlSigner#url} keeps
 * only {@code id, name, is_bot}, while {@link GetChatUrlSigner#urlByChatId} also keeps
 * {@code email, link, picture}. The REST methods additionally accept
 * {@code rights}.
 */
public final class Recipient {

    private final Map<String, Object> data;

    private Recipient(Map<String, Object> data) {
        this.data = data;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Recipient of(String id, String name) {
        return builder().id(id).name(name).build();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    /**
     * Equal to another {@code Recipient} with the same fields, by content — the
     * order they were added does not matter. Insertion order affects only the
     * emitted URL/wire form, not equality.
     */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Recipient r && data.equals(r.data));
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    /** Compact field dump for logs, e.g. {@code Recipient{id=p1, name=Bob}}. */
    @Override
    public String toString() {
        return "Recipient" + data;
    }

    /** Builder for {@link Recipient}. */
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

        public Builder link(String link) {
            data.put("link", link);
            return this;
        }

        public Builder picture(String picture) {
            data.put("picture", picture);
            return this;
        }

        public Builder isBot(boolean isBot) {
            data.put("is_bot", isBot);
            return this;
        }

        /** Per-chat right overrides. REST only — the URL flows drop this field. */
        public Builder rights(Rights rights) {
            data.put("rights", rights.asMap());
            return this;
        }

        /** Escape hatch for a field with no typed setter. */
        public Builder set(String key, @Nullable Object value) {
            data.put(key, value);
            return this;
        }

        public Recipient build() {
            return new Recipient(new LinkedHashMap<>(data));
        }
    }
}
