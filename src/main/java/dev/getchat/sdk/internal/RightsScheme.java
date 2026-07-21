package dev.getchat.sdk.internal;

import java.util.List;
import java.util.Map;

/**
 * Port of {@code src/libs/rights.scheme.ts}. Adding a right means adding it here
 * <em>and</em> to {@link dev.getchat.sdk.Rights}; anything absent from this map
 * is silently dropped by {@link UserRights#process}.
 */
public final class RightsScheme {

    private RightsScheme() {}

    /** A right is either a boolean flag or a closed set of string values. */
    public sealed interface Spec permits BooleanRight, EnumRight {}

    public record BooleanRight() implements Spec {}

    public record EnumRight(List<String> values) implements Spec {}

    private static final Spec BOOL = new BooleanRight();

    public static final Map<String, Spec> SCHEME = Map.ofEntries(
            Map.entry("send_messages", BOOL),
            Map.entry("edit_messages", new EnumRight(List.of("none", "my", "any"))),
            Map.entry("delete_messages", new EnumRight(List.of("none", "my", "any"))),
            Map.entry("react_messages", BOOL),
            Map.entry("pin_messages", new EnumRight(List.of("none", "for_me", "for_everyone"))),
            Map.entry("can_press_buttons", BOOL),
            Map.entry("send_typing", BOOL),
            Map.entry("track_presence", BOOL),
            Map.entry("send_photos", BOOL),
            Map.entry("send_voices", BOOL),
            Map.entry("send_audio", BOOL),
            Map.entry("send_documents", BOOL),
            Map.entry("send_location", BOOL),
            Map.entry("create_pool", BOOL),
            Map.entry("participate_pool", BOOL),
            Map.entry("kick_users", BOOL),
            Map.entry("track_read_state", BOOL),
            Map.entry("send_read_state", BOOL),
            Map.entry("leave_chats", BOOL));
}
