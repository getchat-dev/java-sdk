package dev.getchat.sdk.internal;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Port of the node SDK's {@code src/libs/helpers.ts} (imported there as {@code _}).
 *
 * <p>These are deliberately JavaScript-shaped, not idiomatic Java: the signature
 * bytes the backend verifies are produced by JS semantics, so type coercion and
 * string conversion have to behave the way V8 does. The JS value model maps to
 * Java as:
 *
 * <pre>
 *   JS string           -> String
 *   JS number           -> Number
 *   JS boolean          -> Boolean
 *   JS null / undefined -> null
 *   JS array            -> List&lt;?&gt;
 *   JS plain object      -> Map&lt;String, ?&gt;
 * </pre>
 */
public final class Helpers {

    private Helpers() {}

    /** Mirrors the {@code TYPES} enum in helpers.ts. */
    public enum Type {
        SCALAR,
        EMPTY,
        ARRAY,
        OBJECT,
        UNKNOWN
    }

    private static final List<String> TRUTHY = List.of("yes", "on", "true", "1");
    private static final List<String> BOOLEAN_WORDS = List.of("yes", "on", "true", "1", "no", "off", "false", "0");

    private static final String CHARSET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // randomString feeds the nonce and the anonymous session — both anti-replay
    // security tokens, so they must be unpredictable, not merely non-repeating.
    // SecureRandom is thread-safe, so a single shared instance is fine.
    private static final SecureRandom RANDOM = new SecureRandom();

    /** JS {@code value === undefined || value === null}. */
    public static boolean isNoValue(@Nullable Object value) {
        return value == null;
    }

    public static boolean isString(@Nullable Object value) {
        return value instanceof String;
    }

    public static boolean isNumeric(@Nullable Object value) {
        return value instanceof Number;
    }

    /**
     * JS {@code typeof value === 'boolean'}, or — when {@code smart} — any of the
     * strings the node helper accepts ({@code yes/on/true/1/no/off/false/0}).
     */
    public static boolean isBoolean(@Nullable Object value, boolean smart) {
        if (value instanceof Boolean) {
            return true;
        }
        return smart && BOOLEAN_WORDS.contains(jsString(value).toLowerCase(Locale.ROOT));
    }

    public static boolean isBoolean(@Nullable Object value) {
        return isBoolean(value, false);
    }

    /** JS {@code value === true || ['yes','on','true','1'].includes(String(value))}. */
    public static boolean isTRUE(@Nullable Object value) {
        return Boolean.TRUE.equals(value) || TRUTHY.contains(jsString(value).toLowerCase(Locale.ROOT));
    }

    /**
     * JS {@code ['object','undefined'].indexOf(typeof value) === -1}.
     *
     * <p>Note {@code typeof null === 'object'}, so null is NOT scalar.
     */
    public static boolean isScalar(@Nullable Object value) {
        return value != null && !(value instanceof Map) && !(value instanceof List);
    }

    public static boolean isList(@Nullable Object value) {
        return value instanceof List;
    }

    public static boolean isFilledList(@Nullable Object value) {
        return value instanceof List<?> list && !list.isEmpty();
    }

    public static boolean isPlainObject(@Nullable Object value) {
        return value instanceof Map;
    }

    public static boolean isFilledPlainObject(@Nullable Object value) {
        return value instanceof Map<?, ?> map && !map.isEmpty();
    }

    public static Type getType(@Nullable Object value) {
        if (isScalar(value)) {
            return Type.SCALAR;
        }
        if (isNoValue(value)) {
            return Type.EMPTY;
        }
        if (isList(value)) {
            return Type.ARRAY;
        }
        if (isPlainObject(value)) {
            return Type.OBJECT;
        }
        return Type.UNKNOWN;
    }

    /** Random alphanumeric string, same alphabet as {@code strRandom} in the node SDK. */
    public static String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARSET.charAt(RANDOM.nextInt(CHARSET.length())));
        }
        return sb.toString();
    }

    /**
     * The {@code _.sort} comparator: numeric keys first in numeric order, then
     * non-numeric keys byte-wise. Used to order the fields of a nested object
     * inside a signature, so changing it breaks signature verification.
     */
    public static final Comparator<String> KEY_ORDER = (a, b) -> {
        double aNum = jsNumber(a);
        double bNum = jsNumber(b);
        boolean aIsNum = !Double.isNaN(aNum) && !a.trim().isEmpty();
        boolean bIsNum = !Double.isNaN(bNum) && !b.trim().isEmpty();

        if (aIsNum && bIsNum) {
            return Double.compare(aNum, bNum);
        }
        if (aIsNum) {
            return -1;
        }
        if (bIsNum) {
            return 1;
        }
        return a.compareTo(b);
    };

    public static List<String> sort(Iterable<String> keys) {
        List<String> result = new ArrayList<>();
        keys.forEach(result::add);
        result.sort(KEY_ORDER);
        return result;
    }

    /**
     * JS {@code Number(value)} for the subset of forms that reach us: decimal,
     * hex, exponent, leading/trailing whitespace, and empty string (which JS
     * coerces to 0). Returns {@link Double#NaN} when JS would.
     */
    public static double jsNumber(@Nullable String value) {
        if (value == null) {
            return 0d;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return 0d;
        }
        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseLong(trimmed.substring(2), 16);
            }
            if (trimmed.equals("Infinity") || trimmed.equals("+Infinity")) {
                return Double.POSITIVE_INFINITY;
            }
            if (trimmed.equals("-Infinity")) {
                return Double.NEGATIVE_INFINITY;
            }
            // Java accepts trailing type suffixes ("1d", "2f") and leading "."
            // forms that JS rejects; screen them out so we stay JS-compatible.
            if (!trimmed.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
                return Double.NaN;
            }
            return Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * JS {@code String(value)} — the conversion applied implicitly by
     * {@code Array.prototype.join} when building signature input, and by
     * template literals in {@code packObjectForSignature}.
     */
    public static String jsString(@Nullable Object value) {
        if (value == null) {
            // Both null and undefined stringify to '' inside join()/querystring.
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Number n) {
            return jsNumberToString(n);
        }
        if (value instanceof Map) {
            return "[object Object]";
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(jsString(list.get(i)));
            }
            return sb.toString();
        }
        return value.toString();
    }

    /**
     * JS number stringification for the integral values that appear in payloads
     * (pagination, {@code 1}/{@code 0} flags). JS prints 8443, not 8443.0.
     */
    public static String jsNumberToString(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return Long.toString(n.longValue());
        }
        double d = n.doubleValue();
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
