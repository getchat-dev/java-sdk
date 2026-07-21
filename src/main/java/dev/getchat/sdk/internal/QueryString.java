package dev.getchat.sdk.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Node-compatible {@code querystring} behaviour plus the PHP-style
 * {@code flatten()} from {@code src/libs/signing.ts}.
 *
 * <p>{@link java.net.URLEncoder} is deliberately not used: it encodes a space as
 * {@code +} and leaves {@code *} unescaped, while Node's
 * {@code querystring.escape} produces {@code %20} and escapes {@code *} is left
 * alone in both. The embed URL has to match what the other SDKs emit, so the
 * escape table below is copied from Node exactly.
 */
public final class QueryString {

    private QueryString() {}

    /**
     * Characters Node's {@code querystring.escape} leaves untouched — the
     * {@code encodeURIComponent} set: {@code A-Z a-z 0-9 - . _ ~ ! ' ( ) *}.
     */
    private static final boolean[] UNESCAPED = new boolean[128];

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            UNESCAPED[c] = true;
        }
        for (char c = 'a'; c <= 'z'; c++) {
            UNESCAPED[c] = true;
        }
        for (char c = '0'; c <= '9'; c++) {
            UNESCAPED[c] = true;
        }
        for (char c : "!'()*-._~".toCharArray()) {
            UNESCAPED[c] = true;
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** One flattened {@code key=value} pair; unlike {@code Map.entry} it allows a null value. */
    private record Field(String key, @Nullable Object value) {}

    /** Node's {@code querystring.escape}. */
    public static String escape(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c < 128 && UNESCAPED[c]) {
                sb.append((char) c);
            } else {
                sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return sb.toString();
    }

    /**
     * Node's {@code querystring.stringify} over an already-flattened map.
     * Null values render as an empty value ({@code a=}), matching Node.
     */
    public static String stringify(Map<String, Object> flat) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(escape(entry.getKey()))
                    .append('=')
                    .append(escape(Helpers.jsString(entry.getValue())));
        }
        return sb.toString();
    }

    /**
     * PHP-style nesting: {@code user[id]=10}, {@code user[rights][send_messages]=1},
     * {@code recipients[0][id]=p1}.
     *
     * <p>A nested object or array that flattens to nothing contributes no key at
     * all — that is why an empty {@code recipients} list never appears in a URL.
     */
    public static Map<String, Object> flatten(@Nullable Object value) {
        return flatten(value, "", "");
    }

    private static Map<String, Object> flatten(@Nullable Object value, String prefix, String postfix) {
        Map<String, Object> data = new LinkedHashMap<>();

        for (Field field : fields(value)) {
            Object item = field.value();
            // JS: `typeof value === 'object' && value !== null` — true for arrays too.
            if (item instanceof Map || item instanceof List) {
                Map<String, Object> nested = flatten(item, "[", "]");
                for (Map.Entry<String, Object> sub : nested.entrySet()) {
                    data.put(prefix + field.key() + postfix + sub.getKey(), sub.getValue());
                }
            } else {
                data.put(prefix + field.key() + postfix, item);
            }
        }

        return data;
    }

    /** JS {@code Object.keys()} order: insertion order for objects, indices for arrays. */
    private static List<Field> fields(@Nullable Object value) {
        List<Field> out = new ArrayList<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.add(new Field(String.valueOf(e.getKey()), e.getValue()));
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                out.add(new Field(String.valueOf(i), list.get(i)));
            }
        }
        return out;
    }
}
