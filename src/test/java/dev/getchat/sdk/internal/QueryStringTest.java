package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/** Expected values captured from Node's {@code querystring} module. */
class QueryStringTest {

    @Test
    @DisplayName("escape matches Node's encodeURIComponent character set")
    void escapeMatchesNode() {
        // Node leaves exactly these ASCII characters unescaped.
        String unreserved = "!'()*-._~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        assertEquals(unreserved, QueryString.escape(unreserved));

        assertEquals("%20", QueryString.escape(" "), "space is %20, not +");
        assertEquals("%2B", QueryString.escape("+"));
        assertEquals("%5B", QueryString.escape("["));
        assertEquals("%5D", QueryString.escape("]"));
        assertEquals("%26", QueryString.escape("&"));
        assertEquals("%3D", QueryString.escape("="));
        assertEquals("%2F", QueryString.escape("/"));
        assertEquals("%40", QueryString.escape("@"));
        assertEquals("%3A", QueryString.escape(":"));
    }

    @Test
    @DisplayName("escape encodes non-ASCII as UTF-8 bytes")
    void escapeUtf8() {
        assertEquals("%D0%90", QueryString.escape("А"));
        assertEquals("%F0%9F%98%80", QueryString.escape("😀"));
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
