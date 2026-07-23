package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Expected values captured from Node's {@code querystring} module. */
class QueryStringTest {

    @Test
    @DisplayName("escape leaves Node's unreserved character set untouched")
    void escapeLeavesUnreservedCharsAlone() {
        // Node leaves exactly these ASCII characters unescaped.
        String unreserved = "!'()*-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        assertEquals(unreserved, QueryString.escape(unreserved));
    }

    // The reserved ASCII characters and their Node encodeURIComponent output —
    // notably a space is %20, never +. Plus non-ASCII, which is encoded as its
    // UTF-8 bytes.
    static Stream<Arguments> escapeCases() {
        return Stream.of(
                arguments("space", " ", "%20"),
                arguments("plus", "+", "%2B"),
                arguments("open bracket", "[", "%5B"),
                arguments("close bracket", "]", "%5D"),
                arguments("ampersand", "&", "%26"),
                arguments("equals", "=", "%3D"),
                arguments("slash", "/", "%2F"),
                arguments("at", "@", "%40"),
                arguments("colon", ":", "%3A"),
                arguments("Cyrillic A", "А", "%D0%90"),
                arguments("emoji", "😀", "%F0%9F%98%80"));
    }

    @ParameterizedTest(name = "escape({0}) = {2}")
    @MethodSource("escapeCases")
    @DisplayName("escape matches Node's encodeURIComponent, UTF-8 encoding non-ASCII")
    void escapeMatchesNode(String label, String input, String expected) {
        assertEquals(expected, QueryString.escape(input));
    }

    @Test
    @DisplayName("null values render as an empty value, matching Node")
    void stringifyNulls() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("a", null);
        flat.put("b", "");
        flat.put("c", 0);
        assertEquals("a=&b=&c=0", QueryString.stringify(flat));
    }

    @Test
    @DisplayName("flatten nests objects and arrays PHP-style")
    void flattenNests() {
        Map<String, Object> rights = new LinkedHashMap<>();
        rights.put("send_messages", "1");

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", "u1");
        user.put("rights", rights);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("user", user);
        input.put("recipients", List.of(Map.of("id", "p1")));

        Map<String, Object> flat = QueryString.flatten(input);

        assertEquals(
                List.of("user[id]", "user[rights][send_messages]", "recipients[0][id]"),
                new ArrayList<>(flat.keySet()));
    }

    @Test
    @DisplayName("a container that flattens to nothing contributes no key")
    void flattenDropsEmptyContainers() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("nonce", "abc");
        input.put("recipients", List.of());
        input.put("meta", Map.of());

        Map<String, Object> flat = QueryString.flatten(input);

        assertEquals(List.of("nonce"), new ArrayList<>(flat.keySet()));
        assertTrue(QueryString.stringify(flat).equals("nonce=abc"));
    }

    @Test
    @DisplayName("flatten preserves insertion order")
    void flattenPreservesOrder() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("z", 1);
        input.put("a", 2);
        input.put("m", 3);

        assertEquals("z=1&a=2&m=3", QueryString.stringify(QueryString.flatten(input)));
    }
}
