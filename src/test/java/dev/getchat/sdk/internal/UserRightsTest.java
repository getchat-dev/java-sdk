package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UserRightsTest {

    @Test
    @DisplayName("boolean rights collapse to the strings 1 and 0")
    void booleanRights() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("send_messages", true);
        input.put("react_messages", "yes");
        input.put("send_typing", false);
        input.put("kick_users", "off");

        Map<String, String> result = UserRights.process(input);

        assertAll(
                () -> assertEquals("1", result.get("send_messages")),
                () -> assertEquals("1", result.get("react_messages")),
                () -> assertEquals("0", result.get("send_typing")),
                () -> assertEquals("0", result.get("kick_users")));
    }

    @Test
    @DisplayName("enum rights pass through only when the value is in the scheme")
    void enumRights() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("edit_messages", "my");
        input.put("delete_messages", "bogus");
        input.put("pin_messages", "for_everyone");

        Map<String, String> result = UserRights.process(input);

        assertAll(
                () -> assertEquals("my", result.get("edit_messages")),
                () -> assertNull(result.get("delete_messages"), "an out-of-scheme value is dropped"),
                () -> assertEquals("for_everyone", result.get("pin_messages")));
    }

    @Test
    @DisplayName("only the first colon segment is validated, the whole value is kept")
    void colonSuffixIsPreserved() {
        Map<String, String> result = UserRights.process(Map.of("edit_messages", "my:extra"));
        assertEquals("my:extra", result.get("edit_messages"));
    }

    @Test
    void unknownRightsAreDropped() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("bogus_right", true);
        input.put("send_messages", true);

        Map<String, String> result = UserRights.process(input);

        assertEquals(1, result.size());
        assertEquals("1", result.get("send_messages"));
    }

    @Test
    @DisplayName("nothing valid yields null, so the field is dropped entirely")
    void nullWhenNothingSurvives() {
        assertNull(UserRights.process(Map.of()));
        assertNull(UserRights.process(null));
        assertNull(UserRights.process(Map.of("bogus", true)));
    }

    /**
     * A boolean right can never be dropped: whatever comes in, it leaves as the
     * string {@code "1"} or {@code "0"}. The cases that surprise people are the
     * last four — a {@code null} is an explicit DENIAL rather than an omission,
     * the colon tail a link may carry is thrown away here (unlike on an enum
     * right), and neither an untrimmed space nor an unknown word ever reads as
     * true.
     */
    static Stream<Arguments> booleanRightValues() {
        return Stream.of(
                arguments("Boolean true", true, "1"),
                arguments("Boolean false", false, "0"),
                arguments("\"yes\"", "yes", "1"),
                arguments("\"TRUE\" — booleans are case-insensitive", "TRUE", "1"),
                arguments("\"On\"", "On", "1"),
                arguments("\"no\"", "no", "0"),
                arguments("integer 1", 1, "1"),
                arguments("double 1.0 — JS renders it \"1\"", 1.0d, "1"),
                arguments("integer 0", 0, "0"),
                arguments("empty string", "", "0"),
                arguments("null is a denial, not an omission", null, "0"),
                arguments("colon tail is dropped, head decides", "true:x", "1"),
                arguments("\" true\" is not trimmed, so it is not true", " true", "0"),
                arguments("an unknown word", "maybe", "0"));
    }

    @ParameterizedTest(name = "send_messages = {0} -> \"{2}\"")
    @MethodSource("booleanRightValues")
    @DisplayName("a boolean right always lands on \"1\" or \"0\" and is never dropped")
    void booleanRightNormalisation(String label, Object value, String expected) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("send_messages", value);

        assertEquals(expected, UserRights.process(input).get("send_messages"));
    }

    /**
     * An enum right is the mirror image of a boolean one: it is either kept
     * VERBATIM or dropped entirely, never coerced. Only the segment before the
     * first colon is matched against the scheme, which is what lets a link carry
     * params — and also why a leading colon, an uppercase spelling or an
     * untrimmed space all fail, quietly, to produce a right at all.
     */
    static Stream<Arguments> enumRightValues() {
        return Stream.of(
                arguments("an in-scheme value", "my", "my"),
                arguments("one param, kept whole", "my:extra", "my:extra"),
                arguments("several params, kept whole", "any:a:b", "any:a:b"),
                arguments("a trailing colon survives — only the head is checked", "my:", "my:"),
                arguments("a leading colon makes the head empty", ":my", null),
                arguments("\"MY\" — enums are case-SENSITIVE, unlike booleans", "MY", null),
                arguments("\" my\" is not trimmed", " my", null),
                arguments("an empty string", "", null),
                arguments("an out-of-scheme word", "bogus", null),
                arguments("null", null, null),
                arguments("a number is not a string", 42, null),
                arguments("a boolean is not a string", true, null));
    }

    @ParameterizedTest(name = "edit_messages = {0} -> {2}")
    @MethodSource("enumRightValues")
    @DisplayName("an enum right is kept verbatim or dropped, never coerced")
    void enumRightNormalisation(String label, Object value, String expected) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("edit_messages", value);

        // Dropping the only right leaves nothing, and process() answers null then
        // rather than an empty map — the field disappears from the URL entirely.
        Map<String, String> result = UserRights.process(input);
        assertEquals(expected, result == null ? null : result.get("edit_messages"));
    }

    @Test
    @DisplayName("a dropped enum right does not take the surviving rights with it")
    void oneBadEnumDoesNotSinkTheRest() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("edit_messages", ":my");
        input.put("send_messages", true);
        input.put("pin_messages", "for_me:silent");

        Map<String, String> result = UserRights.process(input);

        assertAll(
                () -> assertEquals(2, result.size()),
                () -> assertNull(result.get("edit_messages")),
                () -> assertEquals("1", result.get("send_messages")),
                () -> assertEquals("for_me:silent", result.get("pin_messages")));
    }

    @Test
    @DisplayName("insertion order is preserved — it is visible in the URL")
    void preservesOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("send_typing", true);
        input.put("send_messages", true);
        input.put("edit_messages", "any");

        assertEquals(
                new ArrayList<>(input.keySet()),
                new ArrayList<>(UserRights.process(input).keySet()));
    }
}
