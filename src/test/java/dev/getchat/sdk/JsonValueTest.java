package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The public {@link JsonValue} wrapper. These tests live in the same package so
 * they can reach the package-private {@link JsonValue#wrap} factory and the
 * {@link JsonValue#MISSING} sentinel — the same seam {@link GetChatClient} uses.
 */
class JsonValueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Parsed (not compared byte-for-byte), so the text-block reformatting is purely
    // for readability — the resulting JsonNode is identical to the old one-line form.
    private static final String DOC =
            """
            {
              "status": "ok",
              "count": 3,
              "ratio": 1.5,
              "big": 9999999999,
              "active": true,
              "nickname": null,
              "tags": ["a", "b", "c"],
              "user": {"id": "u1", "name": "Alice"},
              "nested": {"deep": {"value": "found"}}
            }""";

    private static JsonValue parse(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return JsonValue.wrap(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonValue root() {
        return parse(DOC);
    }

    @Test
    @DisplayName("navigates objects and nested objects")
    void navigatesObjects() {
        JsonValue root = root();
        assertAll(
                () -> assertEquals("ok", root.get("status").asString()),
                () -> assertEquals("Alice", root.get("user").get("name").asString()),
                () -> assertEquals(
                        "found", root.get("nested").get("deep").get("value").asString()));
    }

    @Test
    @DisplayName("navigates arrays by index")
    void navigatesArrays() {
        JsonValue tags = root().get("tags");
        assertTrue(tags.isArray());
        assertEquals(3, tags.size());
        assertEquals("a", tags.get(0).asString());
        assertEquals("c", tags.get(2).asString());
        // Out of range is MISSING, not an exception.
        assertTrue(tags.get(9).isMissing());
    }

    @Test
    @DisplayName("a missing chain collapses to a default instead of throwing")
    void missingChain() {
        JsonValue root = root();
        assertEquals("def", root.get("nope").get("also nope").asString("def"));
        assertTrue(root.get("nope").isMissing());
        // Every absent lookup is the shared sentinel.
        assertSame(JsonValue.MISSING, root.get("nope"));
        assertSame(JsonValue.MISSING, root.get("tags").get(99));
    }

    @Test
    @DisplayName("null and missing are distinct")
    void nullVersusMissing() {
        JsonValue root = root();

        JsonValue nick = root.get("nickname");
        assertTrue(nick.isNull());
        assertFalse(nick.isMissing());

        JsonValue absent = root.get("no_such_field");
        assertTrue(absent.isMissing());
        assertFalse(absent.isNull());
    }

    @Test
    @DisplayName("as*(default) return the value when present, the default when missing or null")
    void defaultAccessors() {
        JsonValue root = root();

        assertAll(
                () -> assertEquals(3, root.get("count").asInt(-1)),
                () -> assertEquals(9999999999L, root.get("big").asLong(-1)),
                () -> assertEquals(1.5, root.get("ratio").asDouble(-1), 0.0),
                () -> assertTrue(root.get("active").asBoolean(false)),
                () -> assertEquals("ok", root.get("status").asString("def")),

                // Missing → default.
                () -> assertEquals(-1, root.get("nope").asInt(-1)),
                () -> assertEquals(-1L, root.get("nope").asLong(-1)),
                () -> assertEquals(-1.0, root.get("nope").asDouble(-1), 0.0),
                () -> assertTrue(root.get("nope").asBoolean(true)),
                () -> assertEquals("def", root.get("nope").asString("def")),

                // Explicit null → default.
                () -> assertEquals(-1, root.get("nickname").asInt(-1)),
                () -> assertTrue(root.get("nickname").asBoolean(true)),
                () -> assertEquals("def", root.get("nickname").asString("def")),

                // Non-coercible text → default for numeric accessors.
                () -> assertEquals(-1, root.get("status").asInt(-1)));
    }

    @ParameterizedTest(name = "{0} coerces to \"{1}\"")
    @CsvSource({"count,3", "active,true", "ratio,1.5"})
    @DisplayName("asString(default) coerces scalars but never throws")
    void lenientStringCoercion(String field, String expected) {
        assertEquals(expected, root().get(field).asString("x"));
    }

    @Test
    @DisplayName("strict asString() returns a present JSON string")
    void strictStringReturnsPresentString() {
        assertEquals("ok", root().get("status").asString());
    }

    // Missing (nope), null (nickname), number (count), boolean (active), object
    // (user) and array (tags) are all type mismatches for the strict accessor.
    @ParameterizedTest(name = "asString() throws on field ''{0}''")
    @ValueSource(strings = {"nope", "nickname", "count", "active", "user", "tags"})
    @DisplayName("strict asString() throws when the value is not a present JSON string")
    void strictStringThrowsOnMismatch(String field) {
        assertThrows(GetChatException.class, () -> root().get(field).asString());
    }

    @Test
    @DisplayName("predicates classify each node kind")
    void predicates() {
        JsonValue root = root();
        assertAll(
                () -> assertTrue(root.isObject()),
                () -> assertTrue(root.get("tags").isArray()),
                () -> assertTrue(root.get("status").isString()),
                () -> assertTrue(root.get("count").isNumber()),
                () -> assertTrue(root.get("active").isBoolean()),
                () -> assertTrue(root.get("nickname").isNull()),
                () -> assertTrue(root.has("status")),
                () -> assertFalse(root.has("nope")),
                () -> assertEquals(9, root.size()));
    }

    @Test
    @DisplayName("values() yields wrapped elements, empty for non-arrays")
    void values() {
        List<JsonValue> tags = root().get("tags").values();
        assertEquals(3, tags.size());
        assertEquals(
                List.of("a", "b", "c"), tags.stream().map(JsonValue::asString).toList());

        // Non-array → empty, so iteration is always safe.
        assertTrue(root().get("status").values().isEmpty());
        assertTrue(root().get("nope").values().isEmpty());
    }

    @Test
    @DisplayName("fieldNames() preserves order, empty for non-objects")
    void fieldNames() {
        Set<String> names = root().get("user").fieldNames();
        assertEquals(List.of("id", "name"), List.copyOf(names));
        assertTrue(root().get("tags").fieldNames().isEmpty());
        assertTrue(root().get("status").fieldNames().isEmpty());
    }

    @Test
    @DisplayName("at() resolves a JSON Pointer, MISSING for an unresolved path")
    void jsonPointer() {
        JsonValue root = root();
        assertEquals("found", root.at("/nested/deep/value").asString());
        assertEquals("b", root.at("/tags/1").asString());
        assertTrue(root.at("/nope/x").isMissing());
        // A valid pointer that simply does not resolve is missing, not an error.
        assertTrue(root.at("/nonexistent/path").isMissing());
        assertSame(JsonValue.MISSING, root.at("/nonexistent/path"));
    }

    @Test
    @DisplayName("at() throws GetChatException on a syntactically invalid pointer")
    void jsonPointerInvalid() {
        JsonValue root = root();
        // A non-empty string without a leading '/' is not a valid pointer.
        GetChatException ex = assertThrows(GetChatException.class, () -> root.at("data"));
        assertTrue(
                ex.getMessage().contains("invalid JSON Pointer"),
                "message should identify the invalid pointer, was: " + ex.getMessage());
        assertThrows(GetChatException.class, () -> root.at("no/slash"));
    }

    @Test
    @DisplayName("toMap() lowers an object to plain JDK types")
    void toMap() {
        Map<String, Object> user = root().get("user").toMap();
        assertEquals(Map.of("id", "u1", "name", "Alice"), user);
        assertInstanceOf(String.class, user.get("id"));

        Map<String, Object> whole = root().toMap();
        assertAll(
                () -> assertInstanceOf(Number.class, whole.get("count")),
                () -> assertInstanceOf(Boolean.class, whole.get("active")),
                () -> assertInstanceOf(List.class, whole.get("tags")),
                () -> assertInstanceOf(Map.class, whole.get("user")),
                () -> assertTrue(whole.containsKey("nickname")),
                () -> assertNull(whole.get("nickname")),
                // Non-object → empty map.
                () -> assertTrue(root().get("tags").toMap().isEmpty()));
    }

    @Test
    @DisplayName("toList() lowers an array to plain JDK types")
    void toList() {
        List<Object> tags = root().get("tags").toList();
        assertEquals(List.of("a", "b", "c"), tags);
        assertInstanceOf(String.class, tags.get(0));

        // Non-array → empty list.
        assertTrue(root().get("user").toList().isEmpty());
    }

    @Test
    @DisplayName("toJson() and toString() emit compact JSON")
    void toJson() {
        JsonValue user = root().get("user");
        assertEquals("{\"id\":\"u1\",\"name\":\"Alice\"}", user.toJson());
        assertEquals(user.toJson(), user.toString());
        // The missing sentinel serialises to an empty string.
        assertTrue(JsonValue.MISSING.toJson().isEmpty());
    }

    @Test
    @DisplayName("equals/hashCode compare the underlying JSON")
    void equalityByNode() {
        JsonValue a = parse(DOC).get("user");
        JsonValue b = parse(DOC).get("user");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        assertNotEquals(a, root().get("nested"));
        assertNotEquals(a, "not a JsonValue");
    }
}
