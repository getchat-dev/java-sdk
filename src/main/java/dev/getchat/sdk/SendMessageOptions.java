package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChatClient#sendMessage(Chat, User, String, SendMessageOptions)}.
 *
 * <p>Replaces the old six-argument signatures
 * ({@code sendMessage(chat, user, participants, text, extra, buttons)}) with a
 * single readable object. Every field is optional; the chat, user and text are
 * passed to {@code sendMessage} directly.
 *
 * <pre>{@code
 * sdk.sendMessage(Chat.of("support-42"),
 *         User.builder().id("u-1").name("Alice").build(),
 *         "Pick one",
 *         SendMessageOptions.builder()
 *                 .participant(Recipient.of("u-2", "Bob"))
 *                 .extra(Map.of("source", "bot"))
 *                 .buttons(Button.of(Button.Type.URL, "Open"))
 *                 .build());
 * }</pre>
 */
public final class SendMessageOptions {

    private final List<Recipient> participants;
    private final Map<String, Object> extra;
    private final List<Map<String, Object>> buttons;

    private SendMessageOptions(
            List<Recipient> participants, Map<String, Object> extra, List<Map<String, Object>> buttons) {
        this.participants = participants;
        this.extra = extra;
        this.buttons = buttons;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessors used by GetChatClient.sendMessage ──────────────────

    /** Participants to seed a chat being created; empty when none were set. */
    List<Recipient> participants() {
        return participants;
    }

    Map<String, Object> extra() {
        return extra;
    }

    List<Map<String, Object>> buttons() {
        return buttons;
    }

    /** Equal to another instance carrying the same participants, extra and buttons. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof SendMessageOptions other
                        && participants.equals(other.participants)
                        && extra.equals(other.extra)
                        && buttons.equals(other.buttons));
    }

    @Override
    public int hashCode() {
        return Objects.hash(participants, extra, buttons);
    }

    /** Compact dump for logs; carries no secrets, only the outgoing message fields. */
    @Override
    public String toString() {
        return "SendMessageOptions{participants=" + participants
                + ", extra=" + extra
                + ", buttons=" + buttons
                + "}";
    }

    /** Builder for {@link SendMessageOptions}. */
    public static final class Builder {

        private final List<Recipient> participants = new ArrayList<>();
        private final Map<String, Object> extra = new LinkedHashMap<>();
        private final List<Map<String, Object>> buttons = new ArrayList<>();

        private Builder() {}

        /** Add a single participant to seed a chat being created. */
        public Builder participant(Recipient recipient) {
            this.participants.add(recipient);
            return this;
        }

        /** Set the participants that seed a chat being created, replacing any set so far. */
        public Builder participants(@Nullable List<Recipient> participants) {
            this.participants.clear();
            if (participants != null) {
                this.participants.addAll(participants);
            }
            return this;
        }

        /** Set the message's {@code extra} metadata, replacing any set so far. */
        public Builder extra(@Nullable Map<String, Object> extra) {
            this.extra.clear();
            if (extra != null) {
                this.extra.putAll(extra);
            }
            return this;
        }

        /** Add a single {@code extra} entry. */
        public Builder extra(String key, @Nullable Object value) {
            this.extra.put(key, value);
            return this;
        }

        /** Set the message's buttons from raw maps, replacing any set so far. */
        public Builder buttons(@Nullable List<Map<String, Object>> buttons) {
            this.buttons.clear();
            if (buttons != null) {
                for (Map<String, Object> button : buttons) {
                    this.buttons.add(new LinkedHashMap<>(button));
                }
            }
            return this;
        }

        /** Set the message's buttons from typed {@link Button}s, replacing any set so far. */
        public Builder buttons(Button @Nullable ... buttons) {
            this.buttons.clear();
            if (buttons != null) {
                for (Button button : buttons) {
                    this.buttons.add(new LinkedHashMap<>(button.asMap()));
                }
            }
            return this;
        }

        public SendMessageOptions build() {
            List<Map<String, Object>> buttonsCopy = new ArrayList<>(buttons.size());
            for (Map<String, Object> button : buttons) {
                buttonsCopy.add(new LinkedHashMap<>(button));
            }
            return new SendMessageOptions(
                    Collections.unmodifiableList(new ArrayList<>(participants)),
                    new LinkedHashMap<>(extra),
                    Collections.unmodifiableList(buttonsCopy));
        }
    }
}
