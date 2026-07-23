package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

/** The JS-semantics primitives the signature depends on. */
class HelpersTest {

    // A JS scalar is a string, number or boolean; null (typeof null === 'object'),
    // an object and an array are all non-scalar.
    static Stream<Arguments> scalarCases() {
        return Stream.of(
                arguments("string", "a", true),
                arguments("number", 1, true),
                arguments("boolean", true, true),
                arguments("null", null, false),
                arguments("object", Map.of(), false),
                arguments("array", List.of(), false));
    }

    @ParameterizedTest(name = "isScalar({0}) = {2}")
    @MethodSource("scalarCases")
    @DisplayName("isScalar treats null as non-scalar, like typeof null === 'object'")
    void scalarSemantics(String label, @Nullable Object value, boolean expected) {
        assertEquals(expected, Helpers.isScalar(value));
    }

    static Stream<Arguments> typeCases() {
        return Stream.of(
                arguments("scalar", "a", Helpers.Type.SCALAR),
                arguments("empty (null)", null, Helpers.Type.EMPTY),
                arguments("array", List.of(), Helpers.Type.ARRAY),
                arguments("object", Map.of(), Helpers.Type.OBJECT));
    }

    @ParameterizedTest(name = "getType({0}) = {2}")
    @MethodSource("typeCases")
    void typeClassification(String label, @Nullable Object value, Helpers.Type expected) {
        assertEquals(expected, Helpers.getType(value));
    }

    // The word forms the node helper accepts in smart mode, and whether isTRUE
    // treats each as true.
    @ParameterizedTest(name = "{0} -> isTRUE={1}")
    @MethodSource("smartBooleanWords")
    @DisplayName("smart booleans accept the word forms the node helper accepts")
    void smartBooleans(String word, boolean expectedTrue) {
        assertTrue(Helpers.isBoolean(word, true), word);
        assertEquals(expectedTrue, Helpers.isTRUE(word), word);
    }

    static Stream<Arguments> smartBooleanWords() {
        return Stream.of(
                arguments("yes", true), arguments("on", true), arguments("true", true),
                arguments("1", true), arguments("YES", true), arguments("True", true),
                arguments("no", false), arguments("off", false), arguments("false", false), arguments("0", false));
    }

    @Test
    @DisplayName("without smart mode only real booleans count, and unknown words are not booleans")
    void nonSmartBooleans() {
        assertFalse(Helpers.isBoolean("maybe", true));
        assertFalse(Helpers.isBoolean("1", false), "without smart mode only real booleans count");
    }

    @Test
    @DisplayName("sort puts numeric keys first, then byte-wise strings")
    void sortOrder() {
        assertEquals(
                List.of("2", "10", "Zebra", "apple", "banana"),
                Helpers.sort(List.of("banana", "10", "apple", "2", "Zebra")));
    }

    // jsString matches JS String() for the values that reach a signature — notably
    // null -> "" (Array.join semantics) and 8443.0 -> "8443" (no trailing ".0").
    static Stream<Arguments> jsStringCases() {
        return Stream.of(
                arguments(null, ""),
                arguments(true, "true"),
                arguments(false, "false"),
                arguments(8443, "8443"),
                arguments(8443.0, "8443"),
                arguments(1.5, "1.5"),
                arguments("abc", "abc"));
    }

    @ParameterizedTest(name = "jsString({0}) = \"{1}\"")
    @MethodSource("jsStringCases")
    @DisplayName("jsString matches JS String() for the values that reach a signature")
    void jsStringConversion(@Nullable Object value, String expected) {
        assertEquals(expected, Helpers.jsString(value));
    }

    @ParameterizedTest(name = "jsNumber(\"{0}\") = {1}")
    @CsvSource({"10,10.0", "'',0.0", "1e3,1000.0", "0x10,16.0"})
    @DisplayName("jsNumber parses JS-style numeric literals")
    void jsNumberParsing(String input, double expected) {
        assertEquals(expected, Helpers.jsNumber(input));
    }

    @ParameterizedTest(name = "jsNumber(\"{0}\") is NaN")
    @ValueSource(strings = {"abc", "1d"})
    @DisplayName("jsNumber yields NaN for non-JS-numeric input (Java accepts a 'd' suffix, JS does not)")
    void jsNumberNaN(String input) {
        assertTrue(Double.isNaN(Helpers.jsNumber(input)));
    }
}
