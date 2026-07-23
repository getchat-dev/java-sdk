package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
