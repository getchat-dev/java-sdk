package dev.getchat.sdk.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The whitelist passed to {@link Signing#normalizeData}. Field order is
 * significant: it decides the order of the resulting map, which in turn decides
 * both the query-string order and (for the array-filter form) the order fields
 * enter the signature.
 */
public final class NormalizeFilter {

    /**
     * Per-field rule. {@code process} wins if present; otherwise {@code default}
     * fills in for a null input. A {@code process} function returning null means
     * "drop this field" (JS {@code undefined}).
     */
    public record Spec(
            boolean hasDefault, @Nullable Object defaultValue, @Nullable UnaryOperator<@Nullable Object> process) {

        static Spec passthrough() {
            return new Spec(false, null, null);
        }

        static Spec withDefault(@Nullable Object value) {
            return new Spec(true, value, null);
        }

        static Spec processed(UnaryOperator<@Nullable Object> fn) {
            return new Spec(false, null, fn);
        }
    }

    private final Map<String, Spec> fields = new LinkedHashMap<>();

    private NormalizeFilter() {}

    public static NormalizeFilter create() {
        return new NormalizeFilter();
    }

    /** Whitelist a field, passing its value through untouched (beyond trimming). */
    public NormalizeFilter field(String name) {
        fields.put(name, Spec.passthrough());
        return this;
    }

    /** Whitelist several fields at once. */
    public NormalizeFilter fields(String... names) {
        for (String name : names) {
            field(name);
        }
        return this;
    }

    /** Whitelist a field, substituting {@code value} when the input is null. */
    public NormalizeFilter withDefault(String name, @Nullable Object value) {
        fields.put(name, Spec.withDefault(value));
        return this;
    }

    /** Whitelist a field, running it through {@code fn} (return null to drop it). */
    public NormalizeFilter processed(String name, UnaryOperator<@Nullable Object> fn) {
        fields.put(name, Spec.processed(fn));
        return this;
    }

    Map<String, Spec> specs() {
        return fields;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }
}
