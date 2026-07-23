package dev.getchat.sdk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * User rights, as accepted by the URL-signing flows and the REST participant
 * endpoints.
 *
 * <p>Insertion order is preserved and is visible in the generated URL, so two
 * {@code Rights} objects with the same entries added in a different order
 * produce different (both valid) query strings.
 *
 * <pre>{@code
 * Rights rights = Rights.builder()
 *         .sendMessages(true)
 *         .editMessages(Rights.Scope.MY)
 *         .pinMessages(Rights.Pin.FOR_EVERYONE)
 *         .build();
 * }</pre>
 *
 * <p>An enum right can also carry colon-separated params — see
 * {@link Builder#editMessages(Scope, String...)}.
 */
public final class Rights {

    /** Value set for {@code edit_messages} and {@code delete_messages}. */
    public enum Scope {
        NONE("none"),
        MY("my"),
        ANY("any");

        private final String wire;

        Scope(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** Value set for {@code pin_messages}. */
    public enum Pin {
        NONE("none"),
        FOR_ME("for_me"),
        FOR_EVERYONE("for_everyone");

        private final String wire;

        Pin(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private final Map<String, Object> values;

    private Rights(Map<String, Object> values) {
        this.values = values;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Equal to another {@code Rights} carrying the same entries in the same order. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Rights r && values.equals(r.values));
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /** Compact dump for logs, e.g. {@code Rights{send_messages=true}}. */
    @Override
    public String toString() {
        return "Rights" + values;
    }

    /** Builder for {@link Rights}; every setter appends to the insertion order. */
    public static final class Builder {

        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Set any right by its wire name. Use this for a right that is not yet
         * covered by a typed setter — note that anything missing from
         * {@code RightsScheme} is dropped during normalisation.
         */
        public Builder set(String key, @Nullable Object value) {
            values.put(key, value);
            return this;
        }

        public Builder editMessages(Scope scope) {
            return set("edit_messages", scope.wire());
        }

        /**
         * {@code edit_messages} with colon-separated params, e.g.
         * {@code editMessages(Scope.MY, "extra")} → {@code "my:extra"}, which lets
         * the person edit a message's {@code extra} payload as well as its text.
         *
         * <p>Params are a <strong>signed-link</strong> concept: the backend
         * validates only the part before the first colon there. The REST
         * participant endpoints validate the whole string against a closed enum,
         * so a params form sent as a per-chat override is rejected.
         *
         * @throws GetChatException if a param is empty, or carries a {@code ':'} or
         *     whitespace of its own
         */
        public Builder editMessages(Scope scope, String... params) {
            return set("edit_messages", withParams(scope.wire(), params));
        }

        public Builder deleteMessages(Scope scope) {
            return set("delete_messages", scope.wire());
        }

        /**
         * {@code delete_messages} with colon-separated params — see
         * {@link #editMessages(Scope, String...)} for what they mean and where
         * they are accepted.
         *
         * @throws GetChatException if a param is empty, or carries a {@code ':'} or
         *     whitespace of its own
         */
        public Builder deleteMessages(Scope scope, String... params) {
            return set("delete_messages", withParams(scope.wire(), params));
        }

        public Builder pinMessages(Pin pin) {
            return set("pin_messages", pin.wire());
        }

        /**
         * {@code pin_messages} with colon-separated params — see
         * {@link #editMessages(Scope, String...)} for what they mean and where
         * they are accepted.
         *
         * @throws GetChatException if a param is empty, or carries a {@code ':'} or
         *     whitespace of its own
         */
        public Builder pinMessages(Pin pin, String... params) {
            return set("pin_messages", withParams(pin.wire(), params));
        }

        /**
         * Join an enum right's base value with its params the way the wire format
         * wants: {@code value:param:param}. Only the head is validated downstream
         * (by this SDK, the node SDK and the backend alike), so a malformed param
         * would travel all the way to the chat UI and simply never match.
         */
        private static String withParams(String value, String... params) {
            if (params.length == 0) {
                return value;
            }
            StringBuilder out = new StringBuilder(value);
            for (String param : params) {
                if (param == null || param.isEmpty() || !isBareParam(param)) {
                    // Intentional input validation shares the one GetChatException hierarchy.
                    throw new GetChatException("rights params have to be non-empty strings without ':' or whitespace");
                }
                out.append(':').append(param);
            }
            return out.toString();
        }

        /**
         * A param has to survive the trip to the chat UI, which splits the value on
         * {@code ':'} and compares each piece for exact equality. A {@code ':'} would
         * split one param into two; a stray space or control character would make that
         * comparison miss. Both are rejected rather than trimmed away, so a typo fails
         * at the call site instead of producing a right that silently never applies.
         *
         * <p>The space test is deliberately wider than {@link Character#isWhitespace}:
         * that one answers {@code false} for the non-breaking spaces (U+00A0, U+2007,
         * …) a copy-paste out of a document or a browser drags in, and those break the
         * UI's comparison exactly like an ordinary space does.
         */
        private static boolean isBareParam(String param) {
            for (int i = 0; i < param.length(); i++) {
                char c = param.charAt(i);
                if (c == ':' || Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                    return false;
                }
            }
            return true;
        }

        public Builder sendMessages(boolean value) {
            return set("send_messages", value);
        }

        public Builder reactMessages(boolean value) {
            return set("react_messages", value);
        }

        public Builder canPressButtons(boolean value) {
            return set("can_press_buttons", value);
        }

        public Builder sendTyping(boolean value) {
            return set("send_typing", value);
        }

        public Builder trackPresence(boolean value) {
            return set("track_presence", value);
        }

        public Builder sendPhotos(boolean value) {
            return set("send_photos", value);
        }

        public Builder sendVoices(boolean value) {
            return set("send_voices", value);
        }

        public Builder sendAudio(boolean value) {
            return set("send_audio", value);
        }

        public Builder sendDocuments(boolean value) {
            return set("send_documents", value);
        }

        public Builder sendLocation(boolean value) {
            return set("send_location", value);
        }

        public Builder createPool(boolean value) {
            return set("create_pool", value);
        }

        public Builder participatePool(boolean value) {
            return set("participate_pool", value);
        }

        public Builder kickUsers(boolean value) {
            return set("kick_users", value);
        }

        public Builder trackReadState(boolean value) {
            return set("track_read_state", value);
        }

        public Builder sendReadState(boolean value) {
            return set("send_read_state", value);
        }

        public Builder leaveChats(boolean value) {
            return set("leave_chats", value);
        }

        public Rights build() {
            return new Rights(new LinkedHashMap<>(values));
        }
    }
}
