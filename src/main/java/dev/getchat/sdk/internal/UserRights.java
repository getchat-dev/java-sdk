package dev.getchat.sdk.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Port of {@code src/libs/processUserRights.ts}. */
public final class UserRights {

    private UserRights() {}

    private static final List<String> TRUTHY = List.of("1", "on", "true", "yes");

    /**
     * Validate and normalise a user rights map.
     *
     * <p>Boolean rights collapse to the strings {@code "1"} / {@code "0"} — they
     * stay strings because the URL signature was defined that way. Enum rights
     * pass through only if the first colon-separated segment is in the scheme
     * (so {@code "my:extra"} is accepted on the strength of {@code "my"}, and the
     * full original string is preserved).
     *
     * @return the normalised rights, or null if nothing survived validation
     */
    public static @Nullable Map<String, String> process(@Nullable Map<String, Object> rights) {
        if (rights == null || rights.isEmpty()) {
            return null;
        }

        Map<String, String> data = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : rights.entrySet()) {
            Object value = entry.getValue();
            RightsScheme.Spec spec = RightsScheme.SCHEME.get(entry.getKey());
            if (spec == null) {
                continue;
            }

            // JS: `typeof value === 'string' ? value.split(':') : [value]`
            Object head = value instanceof String s ? s.split(":", -1)[0] : value;

            if (spec instanceof RightsScheme.BooleanRight) {
                String normalized = Helpers.jsString(head).toLowerCase(Locale.ROOT);
                data.put(entry.getKey(), TRUTHY.contains(normalized) ? "1" : "0");
            } else if (spec instanceof RightsScheme.EnumRight en
                    && head instanceof String s
                    && en.values().contains(s)) {
                data.put(entry.getKey(), Helpers.jsString(value));
            }
        }

        return data.isEmpty() ? null : data;
    }
}
