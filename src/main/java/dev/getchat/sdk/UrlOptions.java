package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Arguments for {@link GetChatUrlSigner#url}. */
public final class UrlOptions {

    private final @Nullable Chat chat;
    private final User user;
    private final List<Recipient> participants;
    private final Map<String, Object> extra;

    private UrlOptions(@Nullable Chat chat, User user, List<Recipient> participants, Map<String, Object> extra) {
        this.chat = chat;
        this.user = user;
        this.participants = participants;
        this.extra = extra;
    }

    public static Builder builder() {
        return new Builder();
    }

    public @Nullable Chat chat() {
        return chat;
    }

    public User user() {
        return user;
    }

    public List<Recipient> participants() {
        return Collections.unmodifiableList(participants);
    }

    /**
     * Extra query parameters. They are appended after the signature and are not
     * signed, so the backend treats them as untrusted presentation hints.
     */
    public Map<String, Object> extra() {
        return Collections.unmodifiableMap(extra);
    }

    /** Equal to another {@code UrlOptions} carrying the same chat, user, participants and extra. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof UrlOptions other
                        && Objects.equals(chat, other.chat)
                        && user.equals(other.user)
                        && participants.equals(other.participants)
                        && extra.equals(other.extra));
    }

    @Override
    public int hashCode() {
        return Objects.hash(chat, user, participants, extra);
    }

    /** Compact dump for logs; carries only the user data already in the URL. */
    @Override
    public String toString() {
        return "UrlOptions{chat=" + chat
                + ", user=" + user
                + ", participants=" + participants
                + ", extra=" + extra
                + "}";
    }

    /** Builder for {@link UrlOptions}. */
    public static final class Builder {

        private @Nullable Chat chat;
        private @Nullable User user;
        private final List<Recipient> participants = new ArrayList<>();
        private final Map<String, Object> extra = new LinkedHashMap<>();

        private Builder() {}

        public Builder chat(Chat chat) {
            this.chat = chat;
            return this;
        }

        public Builder chat(String chatId) {
            this.chat = Chat.of(chatId);
            return this;
        }

        public Builder user(User user) {
            this.user = user;
            return this;
        }

        public Builder participant(Recipient recipient) {
            this.participants.add(recipient);
            return this;
        }

        public Builder participants(List<Recipient> recipients) {
            this.participants.addAll(recipients);
            return this;
        }

        public Builder extra(String key, @Nullable Object value) {
            this.extra.put(key, value);
            return this;
        }

        public Builder extra(Map<String, Object> values) {
            this.extra.putAll(values);
            return this;
        }

        public UrlOptions build() {
            if (user == null) {
                // Intentional input validation throws GetChatException so every
                // SDK misuse lands in one catchable hierarchy.
                throw new GetChatException("user parameter have to be a plain object");
            }
            return new UrlOptions(chat, user, new ArrayList<>(participants), new LinkedHashMap<>(extra));
        }
    }
}
