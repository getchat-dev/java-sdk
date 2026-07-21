package dev.getchat.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An immutable, read-only view over a JSON value returned by the GetChat API.
 *
 * <p>The SDK parses responses with Jackson, but that is an implementation
 * detail: this wrapper is the public surface, so callers never depend on
 * {@code com.fasterxml.jackson} types and the SDK is free to change its JSON
 * library without breaking anyone. Every REST method on {@link GetChat} and
 * {@link GetChatApiException#body()} hands back a {@code JsonValue}.
 *
 * <p>Navigation is null-safe and chainable: a missing field yields the
 * {@linkplain #isMissing() missing} sentinel rather than {@code null} or an
 * exception, so a deep lookup never throws on missing data. The one exception is
 * {@link #at(String)}: a syntactically invalid JSON Pointer is a programming
 * error and throws {@link GetChatException}.
 *
 * <pre>{@code
 * JsonValue chat = sdk.getChatInfo("chat-1");
 * String title = chat.get("data").get("title").asString("(untitled)");
 *
 * // A path that does not exist collapses to a default, never an NPE:
 * String missing = chat.get("nope").get("still nope").asString("fallback");
 *
 * for (JsonValue m : sdk.getMessagesFromChat("chat-1").get("data").values()) {
 *     System.out.println(m.get("text").asString(""));
 * }
 * }</pre>
 *
 * <p>Extraction accessors come in two flavours. The {@code as*(default)} forms
 * are lenient — a {@linkplain #isMissing() missing} or {@linkplain #isNull()
 * null} value (and a value that cannot be coerced) yields the supplied default,
 * and they never throw. The no-argument {@link #asString()} is strict and
 * throws {@link GetChatException} when the value is not a present JSON string.
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class JsonValue {

    /**
     * Shared sentinel for an absent value. Package-private so {@link GetChat} and
     * {@link GetChatApiException} can hand it back directly. Backed by Jackson's
     * {@link MissingNode}, so every predicate and accessor treats it uniformly.
     */
    static final JsonValue MISSING = new JsonValue(MissingNode.getInstance());

    // Never null: wrap() folds a null or missing node into the MISSING sentinel,
    // so navigation and the predicates below can dereference node unconditionally.
    private final JsonNode node;

    private JsonValue(JsonNode node) {
        this.node = node;
    }

    /**
     * Wrap a Jackson node as a {@code JsonValue}. Package-private on purpose:
     * Jackson types are an implementation detail and must not leak into the
     * public API, so only same-package code (the client and its exception) may
     * cross the boundary. A {@code null} or missing node folds into
     * {@link #MISSING}.
     */
    static JsonValue wrap(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return MISSING;
        }
        return new JsonValue(node);
    }

    // ── Navigation (chain-safe: an absent step yields MISSING, never null) ─────

    /** The value of an object field, or {@link #MISSING} if absent or not an object. */
    public JsonValue get(String field) {
        return wrap(node.get(field));
    }

    /** The element at an array index, or {@link #MISSING} if out of range or not an array. */
    public JsonValue get(int index) {
        return wrap(node.get(index));
    }

    /**
     * Resolve a JSON Pointer (RFC 6901), e.g. {@code "/data/0/title"}. A valid
     * pointer that does not resolve against this value yields {@link #MISSING},
     * so navigation never throws on missing data.
     *
     * <p>A syntactically invalid pointer (a non-empty string without a leading
     * {@code /}, e.g. {@code "data"}) is a programming error, not absent data,
     * and throws {@link GetChatException}.
     *
     * @throws GetChatException if {@code jsonPointer} is not a valid JSON Pointer
     */
    public JsonValue at(String jsonPointer) {
        try {
            return wrap(node.at(jsonPointer));
        } catch (IllegalArgumentException e) {
            // Jackson rejects a syntactically invalid pointer with a raw
            // IllegalArgumentException; translate it into the SDK's exception type
            // so navigation only ever throws through GetChatException.
            throw new GetChatException("invalid JSON Pointer: " + e.getMessage(), e);
        }
    }

    // ── Predicates ────────────────────────────────────────────────────────────

    /** True for the {@link #MISSING} sentinel — an absent field or index. */
    public boolean isMissing() {
        return node.isMissingNode();
    }

    /** True for an explicit JSON {@code null} (distinct from {@link #isMissing()}). */
    public boolean isNull() {
        return node.isNull();
    }

    /** True for a JSON object. */
    public boolean isObject() {
        return node.isObject();
    }

    /** True for a JSON array. */
    public boolean isArray() {
        return node.isArray();
    }

    /** True for a JSON string. */
    public boolean isString() {
        return node.isTextual();
    }

    /** True for any JSON number (integer or floating point). */
    public boolean isNumber() {
        return node.isNumber();
    }

    /** True for a JSON boolean. */
    public boolean isBoolean() {
        return node.isBoolean();
    }

    /** True if this is an object with the given field present. */
    public boolean has(String field) {
        return node.has(field);
    }

    /** Field count for an object, element count for an array, otherwise 0. */
    public int size() {
        return node.size();
    }

    // ── Extraction ────────────────────────────────────────────────────────────

    /**
     * The string value, strictly. This is the one accessor with no default: it
     * requires a value that is present and is a JSON string. A missing value, a
     * JSON {@code null}, or any non-string (number, boolean, object, array) is a
     * type mismatch and throws.
     *
     * <p>Use {@link #asString(String)} for a lenient variant that returns a
     * default and coerces scalars to text.
     *
     * @return the underlying string
     * @throws GetChatException if the value is missing, null, or not a JSON string
     */
    public String asString() {
        if (!node.isTextual()) {
            throw new GetChatException("expected a JSON string but was " + describe());
        }
        return node.textValue();
    }

    /**
     * The string value, or {@code def} when missing or null. A non-string scalar
     * (number, boolean) is coerced to its text form; an object or array yields
     * an empty string, matching Jackson's {@code asText()}.
     */
    public String asString(String def) {
        if (node.isMissingNode() || node.isNull()) {
            return def;
        }
        return node.asText();
    }

    /** The value as an {@code int}, or {@code def} when missing, null, or not coercible. */
    public int asInt(int def) {
        if (node.isMissingNode() || node.isNull()) {
            return def;
        }
        return node.asInt(def);
    }

    /** The value as a {@code long}, or {@code def} when missing, null, or not coercible. */
    public long asLong(long def) {
        if (node.isMissingNode() || node.isNull()) {
            return def;
        }
        return node.asLong(def);
    }

    /** The value as a {@code double}, or {@code def} when missing, null, or not coercible. */
    public double asDouble(double def) {
        if (node.isMissingNode() || node.isNull()) {
            return def;
        }
        return node.asDouble(def);
    }

    /** The value as a {@code boolean}, or {@code def} when missing, null, or not coercible. */
    public boolean asBoolean(boolean def) {
        if (node.isMissingNode() || node.isNull()) {
            return def;
        }
        return node.asBoolean(def);
    }

    // ── Traversal ─────────────────────────────────────────────────────────────

    /**
     * The elements of an array, each wrapped. Returns an empty list for anything
     * that is not an array (including a missing value), so it is always safe to
     * iterate.
     */
    public List<JsonValue> values() {
        if (!node.isArray()) {
            return List.of();
        }
        List<JsonValue> out = new ArrayList<>(node.size());
        for (JsonNode child : node) {
            out.add(wrap(child));
        }
        return out;
    }

    /**
     * The field names of an object, in document order. Returns an empty set for
     * anything that is not an object.
     */
    public Set<String> fieldNames() {
        if (!node.isObject()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * This object converted to a plain JDK {@code Map}, recursively — values are
     * {@code Map}, {@code List}, {@code String}, {@code Number}, {@code Boolean}
     * or {@code null}, with no Jackson types. Returns an empty map for anything
     * that is not an object.
     */
    public Map<String, Object> toMap() {
        if (!node.isObject()) {
            return new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) toPlain(node);
        return map;
    }

    /**
     * This array converted to a plain JDK {@code List}, recursively — elements
     * are {@code Map}, {@code List}, {@code String}, {@code Number},
     * {@code Boolean} or {@code null}, with no Jackson types. Returns an empty
     * list for anything that is not an array.
     */
    public List<Object> toList() {
        if (!node.isArray()) {
            return new ArrayList<>();
        }
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) toPlain(node);
        return list;
    }

    // ── Serialisation / identity ──────────────────────────────────────────────

    /** Compact JSON for this value; an empty string for the {@link #MISSING} sentinel. */
    public String toJson() {
        return node.toString();
    }

    /** Same as {@link #toJson()}: compact JSON. */
    @Override
    public String toString() {
        return node.toString();
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof JsonValue other && node.equals(other.node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Recursively lower a Jackson node to plain JDK types (no Jackson leaks out). */
    private static Object toPlain(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        if (n.isTextual()) {
            return n.textValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNumber()) {
            return n.numberValue();
        }
        if (n.isArray()) {
            List<Object> list = new ArrayList<>(n.size());
            for (JsonNode child : n) {
                list.add(toPlain(child));
            }
            return list;
        }
        if (n.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> map.put(e.getKey(), toPlain(e.getValue())));
            return map;
        }
        // Binary / POJO and other exotic nodes have no natural JDK shape; fall
        // back to their text form rather than leaking a Jackson type.
        return n.asText();
    }

    /** Human-readable node kind for the strict-accessor error message. */
    private String describe() {
        if (node.isMissingNode()) {
            return "missing";
        }
        return node.getNodeType().toString().toLowerCase(Locale.ROOT);
    }
}
