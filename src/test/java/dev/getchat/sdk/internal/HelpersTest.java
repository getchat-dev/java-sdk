package dev.getchat.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The JS-semantics primitives the signature depends on. */
class HelpersTest {

    @Test
    @DisplayName("isScalar treats null as non-scalar, like typeof null === 'object'")
    void scalarSemantics() {
        assertTrue(Helpers.isScalar("a"));
        assertTrue(Helpers.isScalar(1));
        assertTrue(Helpers.isScalar(true));
        assertFalse(Helpers.isScalar(null));
        assertFalse(Helpers.isScalar(Map.of()));
        assertFalse(Helpers.isScalar(List.of()));
    }

    @Test
    void typeClassification() {
        assertEquals(Helpers.Type.SCALAR, Helpers.getType("a"));
        assertEquals(Helpers.Type.EMPTY, Helpers.getType(null));
        assertEquals(Helpers.Type.ARRAY, Helpers.getType(List.of()));
        assertEquals(Helpers.Type.OBJECT, Helpers.getType(Map.of()));
    }

    @Test
    @DisplayName("smart booleans accept the word forms the node helper accepts")
    void smartBooleans() {
        for (String truthy : List.of("yes", "on", "true", "1", "YES", "True")) {
            assertTrue(Helpers.isBoolean(truthy, true), truthy);
            assertTrue(Helpers.isTRUE(truthy), truthy);
        }
        for (String falsy : List.of("no", "off", "false", "0")) {
            assertTrue(Helpers.isBoolean(falsy, true), falsy);
            assertFalse(Helpers.isTRUE(falsy), falsy);
        }
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

    @Test
    @DisplayName("jsString matches JS String() for the values that reach a signature")
    void jsStringConversion() {
        assertEquals("", Helpers.jsString(null));
        assertEquals("true", Helpers.jsString(true));
        assertEquals("false", Helpers.jsString(false));
        assertEquals("8443", Helpers.jsString(8443));
        assertEquals("8443", Helpers.jsString(8443.0), "JS prints 8443, not 8443.0");
        assertEquals("1.5", Helpers.jsString(1.5));
        assertEquals("abc", Helpers.jsString("abc"));
    }

    @Test
    void jsNumberParsing() {
        assertEquals(10.0, Helpers.jsNumber("10"));
        assertEquals(0.0, Helpers.jsNumber(""), "JS Number('') is 0");
        assertEquals(1000.0, Helpers.jsNumber("1e3"));
        assertEquals(16.0, Helpers.jsNumber("0x10"));
        assertTrue(Double.isNaN(Helpers.jsNumber("abc")));
        assertTrue(Double.isNaN(Helpers.jsNumber("1d")), "Java accepts a 'd' suffix, JS does not");
    }
}
