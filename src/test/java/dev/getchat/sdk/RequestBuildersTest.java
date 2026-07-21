package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/** Unit tests for the stage-3 typed request builders. */
class RequestBuildersTest {

    @Nested
    @DisplayName("MessagesQuery")
    class MessagesQueryTest {

        @Test
        @DisplayName("an empty builder produces an empty map")
        void emptyIsEmpty() {
            assertTrue(MessagesQuery.builder().build().asMap().isEmpty());
        }

        @Test
        @DisplayName("only set fields appear; unset booleans are omitted")
        void onlySetFieldsAppear() {
            Map<String, Object> map = MessagesQuery.builder().deleted(false).build().asMap();

            assertEquals(Boolean.FALSE, map.get("isDeleted"));
            assertFalse(map.containsKey("isEdited"));
            assertFalse(map.containsKey("with_users"));
        }

        @Test
        @DisplayName("withUsers maps to the with_users key")
        void withUsersKey() {
            assertEquals(Boolean.TRUE, MessagesQuery.builder().withUsers(true).build().asMap().get("with_users"));
        }

        @Test
        @DisplayName("extra(Map) is defensively copied")
        void extraDefensiveCopy() {
            Map<String, Object> source = new HashMap<>();
            source.put("a", "1");
            MessagesQuery query = MessagesQuery.builder().extra(source).build();

            source.put("b", "2");

            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) query.asMap().get("extra");
            assertEquals(Map.of("a", "1"), extra);
        }

        @Test
        @DisplayName("extra(key, value) accumulates single entries")
        void extraSingleEntries() {
            MessagesQuery query = MessagesQuery.builder().extra("a", 1).extra("b", 2).build();

            @SuppressWarnings("unchecked")
            Map<String, Object> extra = (Map<String, Object>) query.asMap().get("extra");
            assertEquals(2, extra.size());
        }

        @Test
        @DisplayName("set() escapes to the query map")
        void setEscapeHatch() {
            assertEquals("desc", MessagesQuery.builder().set("order", "desc").build().asMap().get("order"));
        }

        @Test
        @DisplayName("asMap() is unmodifiable")
        void asMapUnmodifiable() {
            Map<String, Object> map = MessagesQuery.builder().deleted(true).build().asMap();
            assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
        }
    }

    @Nested
    @DisplayName("ChatsQuery")
    class ChatsQueryTest {

        @Test
        @DisplayName("limit is omitted when not set (preserving the default-1 wart)")
        void limitOmittedByDefault() {
            assertFalse(ChatsQuery.builder().page(2).build().asMap().containsKey("limit"));
        }

        @Test
        @DisplayName("typed fields land under their wire keys")
        void typedFields() {
            Map<String, Object> map = ChatsQuery.builder()
                    .page(2)
                    .limit(10)
                    .type(Chat.Type.GROUP)
                    .owner("o1")
                    .createdFrom("2026-01-01T00:00:00")
                    .withOwners(true)
                    .build()
                    .asMap();

            assertEquals(2, map.get("page"));
            assertEquals(10, map.get("limit"));
            assertEquals("group", map.get("type"));
            assertEquals("o1", map.get("owner"));
            assertEquals("2026-01-01T00:00:00", map.get("created_from"));
            assertEquals(Boolean.TRUE, map.get("with_owners"));
        }

        @Test
        @DisplayName("metadata is defensively copied")
        void metadataDefensiveCopy() {
            Map<String, Object> source = new HashMap<>();
            source.put("plan", "pro");
            ChatsQuery query = ChatsQuery.builder().metadata(source).build();

            source.put("plan", "free");

            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) query.asMap().get("metadata");
            assertEquals("pro", metadata.get("plan"));
        }

        @Test
        @DisplayName("set() escapes to the query map")
        void setEscapeHatch() {
            assertEquals(1, ChatsQuery.builder().set("with_owner", 1).build().asMap().get("with_owner"));
        }
    }

    @Nested
    @DisplayName("UpdateMessageOptions")
    class UpdateMessageOptionsTest {

        @Test
        @DisplayName("defaults: merge mode, no representation, no extra/buttons/fields")
        void defaults() {
            UpdateMessageOptions options = UpdateMessageOptions.builder().build();

            assertEquals(UpdateMessageOptions.ExtraMode.MERGE, options.extraMode());
            assertFalse(options.returnMessage());
            assertTrue(options.extra().isEmpty());
            assertTrue(options.buttons().isEmpty());
            assertTrue(options.messageFields().isEmpty());
        }

        @Test
        @DisplayName("extra(Map) is defensively copied")
        void extraDefensiveCopy() {
            Map<String, Object> source = new HashMap<>();
            source.put("a", "1");
            UpdateMessageOptions options = UpdateMessageOptions.builder().extra(source).build();

            source.put("b", "2");
            assertEquals(Map.of("a", "1"), options.extra());
        }

        @Test
        @DisplayName("buttons(List) is defensively copied — later map mutation does not leak")
        void buttonsListDefensiveCopy() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("type", "url");
            raw.put("label", "Open");
            List<Map<String, Object>> source = new ArrayList<>();
            source.add(raw);

            UpdateMessageOptions options = UpdateMessageOptions.builder().buttons(source).build();

            raw.put("label", "Changed");
            source.clear();

            assertEquals(1, options.buttons().size());
            assertEquals("Open", options.buttons().get(0).get("label"));
        }

        @Test
        @DisplayName("buttons(Button...) converts typed buttons to maps")
        void buttonsTyped() {
            UpdateMessageOptions options = UpdateMessageOptions.builder()
                    .buttons(Button.of(Button.Type.CALL, "Ring"))
                    .build();

            assertEquals(1, options.buttons().size());
            assertEquals("call", options.buttons().get(0).get("type"));
            assertEquals("Ring", options.buttons().get(0).get("label"));
        }

        @Test
        @DisplayName("set() collects arbitrary message-object fields")
        void setEscapeHatch() {
            UpdateMessageOptions options = UpdateMessageOptions.builder().set("is_deleted", true).build();
            assertEquals(Boolean.TRUE, options.messageFields().get("is_deleted"));
        }
    }

    @Nested
    @DisplayName("Button")
    class ButtonTest {

        @Test
        @DisplayName("enum setters emit the documented wire values")
        void wireValues() {
            Map<String, Object> map = Button.builder()
                    .type(Button.Type.REMOTE)
                    .label("Go")
                    .action("do")
                    .state(Button.State.LOADING)
                    .style(Button.Style.POSITIVE)
                    .build()
                    .asMap();

            assertEquals("remote", map.get("type"));
            assertEquals("Go", map.get("label"));
            assertEquals("do", map.get("action"));
            assertEquals("loading", map.get("state"));
            assertEquals("positive", map.get("style"));
        }

        @Test
        @DisplayName("set() escapes to the button map and asMap() is unmodifiable")
        void setEscapeHatchAndUnmodifiable() {
            Map<String, Object> map = Button.builder().set("custom", 7).build().asMap();
            assertEquals(7, map.get("custom"));
            assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
        }
    }
}
