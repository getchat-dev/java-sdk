package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChat#updateMessage(String, String, String, UpdateMessageOptions)}.
 *
 * <p>Replaces the old trailing-boolean signature
 * ({@code updateMessage(chatId, messageId, text, extra, buttons, replaceExtra,
 * returnResource)}) with a single readable object. Every field is optional; the
 * text is passed to {@code updateMessage} directly.
 *
 * <pre>{@code
 * sdk.updateMessage("chat-1", "m-9", "edited text", UpdateMessageOptions.builder()
 *         .extra(Map.of("edited_by", "bot"))
 *         .extraMode(UpdateMessageOptions.ExtraMode.REPLACE)
 *         .returnResource(true)
 *         .build());
 * }</pre>
 */
public final class UpdateMessageOptions {

    /**
     * How {@code extra} is applied. {@link #MERGE} (the default) merges the given
     * keys into the message's existing {@code extra}; {@link #REPLACE} overwrites
     * it wholesale. Maps to the backend's {@code update_extra_mode}. {@code MERGE}
     * is the behaviour the old {@code replaceExtra = false} gave.
     */
    public enum ExtraMode {
        MERGE,
        REPLACE
    }

    private final Map<String, Object> extra;
    private final List<Map<String, Object>> buttons;
    private final Map<String, Object> messageFields;
    private final ExtraMode extraMode;
    private final boolean returnResource;

    private UpdateMessageOptions(
            Map<String, Object> extra,
            List<Map<String, Object>> buttons,
            Map<String, Object> messageFields,
            ExtraMode extraMode,
            boolean returnResource) {
        this.extra = extra;
        this.buttons = buttons;
        this.messageFields = messageFields;
        this.extraMode = extraMode;
        this.returnResource = returnResource;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessors used by GetChat.updateMessage ───────────────

    Map<String, Object> extra() {
        return extra;
    }

    List<Map<String, Object>> buttons() {
        return buttons;
    }

    /** Arbitrary extra fields for the {@code message} object (the {@code set} bag). */
    Map<String, Object> messageFields() {
        return messageFields;
    }

    ExtraMode extraMode() {
        return extraMode;
    }

    boolean returnResource() {
        return returnResource;
    }

    /** Equal to another instance carrying the same extra, buttons, fields, mode and flag. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof UpdateMessageOptions other
                        && returnResource == other.returnResource
                        && extraMode == other.extraMode
                        && extra.equals(other.extra)
                        && buttons.equals(other.buttons)
                        && messageFields.equals(other.messageFields));
    }

    @Override
    public int hashCode() {
        return Objects.hash(extra, buttons, messageFields, extraMode, returnResource);
    }

    /** Compact dump for logs; carries no secrets, only message-update fields. */
    @Override
    public String toString() {
        return "UpdateMessageOptions{extra=" + extra
                + ", buttons=" + buttons
                + ", messageFields=" + messageFields
                + ", extraMode=" + extraMode
                + ", returnResource=" + returnResource
                + "}";
    }

    /** Builder for {@link UpdateMessageOptions}. */
    public static final class Builder {

        private final Map<String, Object> extra = new LinkedHashMap<>();
        private final List<Map<String, Object>> buttons = new ArrayList<>();
        private final Map<String, Object> messageFields = new LinkedHashMap<>();
        private ExtraMode extraMode = ExtraMode.MERGE;
        private boolean returnResource;

        private Builder() {}

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

        /** Choose merge vs replace semantics for {@code extra} (default merge). */
        public Builder extraMode(@Nullable ExtraMode extraMode) {
            this.extraMode = extraMode == null ? ExtraMode.MERGE : extraMode;
            return this;
        }

        /**
         * Ask the backend to echo the updated message back by sending
         * {@code Prefer: return=representation}; the echoed message is then
         * exposed via {@link UpdatedMessage#message()}. Named {@code returnResource}
         * uniformly across every options type in this SDK.
         */
        public Builder returnResource(boolean returnResource) {
            this.returnResource = returnResource;
            return this;
        }

        /**
         * Escape hatch for a field on the {@code message} object with no typed
         * setter — for example {@code set("is_deleted", true)}. Applied after the
         * typed fields, so it can override them.
         */
        public Builder set(String key, @Nullable Object value) {
            this.messageFields.put(key, value);
            return this;
        }

        public UpdateMessageOptions build() {
            List<Map<String, Object>> buttonsCopy = new ArrayList<>(buttons.size());
            for (Map<String, Object> button : buttons) {
                buttonsCopy.add(new LinkedHashMap<>(button));
            }
            return new UpdateMessageOptions(
                    new LinkedHashMap<>(extra),
                    Collections.unmodifiableList(buttonsCopy),
                    new LinkedHashMap<>(messageFields),
                    extraMode,
                    returnResource);
        }
    }
}
