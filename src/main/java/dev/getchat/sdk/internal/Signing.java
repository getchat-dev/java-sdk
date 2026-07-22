package dev.getchat.sdk.internal;

import dev.getchat.sdk.GetChatException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Port of {@code src/libs/signing.ts}.
 *
 * <p>Everything here feeds bytes into a signature the GetChat backend
 * independently recomputes. Field order, sort comparators and the flattening
 * format are part of the wire contract — changing any of them silently breaks
 * verification. See CLAUDE.md ("Two URL builders, two signature schemes").
 */
public final class Signing {

    private Signing() {}

    /**
     * Chat fields kept by {@code normalizeChat}.
     */
    public static final NormalizeFilter CHAT_FILTER =
            NormalizeFilter.create().fields("id", "title", "create", "type", "metadata");

    /** Recipient fields kept by the REST-facing {@code normalizeParticipant}. */
    public static final NormalizeFilter PARTICIPANT_FILTER = NormalizeFilter.create()
            .fields("id", "name", "email", "link", "picture")
            .withDefault("is_bot", Boolean.FALSE)
            // Per-chat right overrides (REST only; the URL flows use their own
            // narrower whitelists and never pick this up).
            .field("rights");

    /**
     * Whitelist + per-field default/process, then trim strings.
     *
     * @throws GetChatException if {@code data} is not a map
     */
    public static Map<String, Object> normalizeData(Object data, @Nullable NormalizeFilter filter) {
        if (!Helpers.isPlainObject(data)) {
            // Intentional input validation shares the one GetChatException hierarchy.
            throw new GetChatException("first parameter have to be a plain object type");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> source = new LinkedHashMap<>((Map<String, Object>) data);

        if (filter == null || filter.isEmpty()) {
            return source;
        }

        Map<String, Object> result = new LinkedHashMap<>();

        for (Map.Entry<String, NormalizeFilter.Spec> entry : filter.specs().entrySet()) {
            String key = entry.getKey();
            NormalizeFilter.Spec spec = entry.getValue();
            Object value = source.get(key);

            if (spec.process() != null) {
                @Nullable Object processed = spec.process().apply(value);
                if (processed != null) {
                    result.put(key, trim(processed));
                }
            } else if (Helpers.isNoValue(value)) {
                if (spec.hasDefault() && spec.defaultValue() != null) {
                    result.put(key, trim(spec.defaultValue()));
                }
            } else {
                result.put(key, trim(value));
            }
        }

        return result;
    }

    private static @Nullable Object trim(@Nullable Object value) {
        return value instanceof String s ? s.trim() : value;
    }

    /**
     * {@code normalizeChat} — whitelist, then coerce a boolean {@code create} to
     * integer 1/0 <em>before</em> signing.
     *
     * <p>The backend's {@code $beforeSanitizers} do not run prior to
     * {@code authorize()}, so PHP sees {@code chat.create = "1"} (a string from
     * the query) while verifying. Sending integer 1/0 on the wire and stringifying
     * it the same way is what makes both sides agree.
     */
    public static Map<String, Object> normalizeChat(Object chat) {
        Map<String, Object> result = normalizeData(chat, CHAT_FILTER);
        Object create = result.get("create");
        if (result.containsKey("create") && Helpers.isBoolean(create)) {
            result.put("create", Boolean.TRUE.equals(create) ? 1 : 0);
        }
        return result;
    }

    public static Map<String, Object> normalizeParticipant(Object participant) {
        return normalizeData(participant, PARTICIPANT_FILTER);
    }

    /**
     * Signature fragment for the legacy MD5 scheme, matching the backend's
     * {@code appendInfoLegacy} used by {@code Signature::verifyLegacyMd5}:
     *
     * <ul>
     *   <li>pick fields from the whitelist
     *   <li>sort keys alphabetically (PHP {@code ksort}, plain string compare)
     *   <li>skip null values
     *   <li>booleans become the literal strings {@code true} / {@code false}
     * </ul>
     *
     * <p>Contrast {@link #addToSignature}, which follows the given field ORDER
     * and is used by the newer HMAC-SHA256 path.
     */
    public static List<Object> appendLegacy(List<Object> signature, Map<String, Object> data, List<String> fields) {
        List<Object> result = new ArrayList<>(signature);

        // PHP ksort is a plain string sort, NOT Helpers.KEY_ORDER.
        Map<String, Object> filtered = new TreeMap<>();
        for (String field : fields) {
            if (data.containsKey(field) && !Helpers.isNoValue(data.get(field))) {
                filtered.put(field, data.get(field));
            }
        }

        for (Object value : filtered.values()) {
            result.add(value instanceof Boolean b ? (b ? "true" : "false") : value);
        }

        return result;
    }

    /**
     * Flatten a nested object into {@code key.subkey=value} strings, ordered by
     * {@link Helpers#KEY_ORDER} (numeric keys first, then byte-wise).
     */
    public static List<String> packObjectForSignature(Map<String, Object> obj, @Nullable String key) {
        List<String> packed = new ArrayList<>();
        for (String k : Helpers.sort(obj.keySet())) {
            String prefix = (key == null || key.isEmpty()) ? "" : key + ".";
            packed.add(prefix + k + "=" + Helpers.jsString(obj.get(k)));
        }
        return packed;
    }

    /**
     * Append fields to the HMAC-SHA256 signature input in the order given by
     * {@code filterKeys} (falling back to {@link Helpers#KEY_ORDER} when absent).
     * Scalars go in as-is; nested objects are packed; arrays and nulls are skipped.
     */
    // getType(value) drives the switch: the OBJECT branch has proven value is a
    // non-null Map, but NullAway can't follow that JS-shaped type guard through
    // Helpers, so passing the cast value to packObjectForSignature is suppressed for
    // this method. Deliberately un-idiomatic signing layer; no behaviour change.
    @SuppressWarnings("NullAway")
    public static List<Object> addToSignature(
            List<Object> signature, Map<String, Object> data, @Nullable List<String> filterKeys) {
        List<Object> result = new ArrayList<>(signature);

        if (data.isEmpty()) {
            return result;
        }

        List<String> keys;
        if (filterKeys != null && !filterKeys.isEmpty()) {
            keys = new ArrayList<>();
            for (String key : filterKeys) {
                if (data.containsKey(key)) {
                    keys.add(key);
                }
            }
        } else {
            keys = Helpers.sort(data.keySet());
        }

        for (String key : keys) {
            Object value = data.get(key);
            switch (Helpers.getType(value)) {
                case SCALAR -> result.add(value);
                case OBJECT -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) value;
                    result.addAll(packObjectForSignature(nested, key));
                }
                default -> {
                    // ARRAY / EMPTY / UNKNOWN contribute nothing, as in the node SDK.
                }
            }
        }

        return result;
    }

    /**
     * Recursively turn booleans into 1/0 right before the URL is emitted.
     *
     * <p>Laravel's {@code boolean} validator rejects {@code true}/{@code false}
     * on the wire. This runs AFTER the signature is computed, because the
     * signature keeps booleans to match PHP's string form.
     */
    public static @Nullable Object coerceBooleansForWire(@Nullable Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(coerceBooleansForWire(item));
            }
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), coerceBooleansForWire(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return value;
    }

    /** JS {@code Array.prototype.join(',')} over the assembled signature input. */
    public static String joinSignature(List<Object> signature) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < signature.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Helpers.jsString(signature.get(i)));
        }
        return sb.toString();
    }
}
