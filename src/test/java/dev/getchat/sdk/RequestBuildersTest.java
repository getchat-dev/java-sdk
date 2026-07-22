package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        @DisplayName("page/limit land under their wire keys and are omitted when unset")
        void pageAndLimit() {
            Map<String, Object> set = MessagesQuery.builder().page(2).limit(20).build().asMap();
            assertEquals(2, set.get("page"));
            assertEquals(20, set.get("limit"));

            Map<String, Object> unset = MessagesQuery.builder().deleted(true).build().asMap();
            assertFalse(unset.containsKey("page"));
            assertFalse(unset.containsKey("limit"));
        }

        @Test
        @DisplayName("asMap() is unmodifiable")
        void asMapUnmodifiable() {
            Map<String, Object> map = MessagesQuery.builder().deleted(true).build().asMap();
            assertThrows(UnsupportedOperationException.class, () -> map.put("x", 1));
        }
    }

    @Nested
    @DisplayName("PageQuery")
    class PageQueryTest {

        @Test
        @DisplayName("an empty builder leaves both fields unset (null)")
        void emptyIsUnset() {
            PageQuery query = PageQuery.builder().build();
            assertNull(query.page());
            assertNull(query.limit());
        }

        @Test
        @DisplayName("page and limit round-trip through the getters")
        void fieldsRoundTrip() {
            PageQuery query = PageQuery.builder().page(3).limit(25).build();
            assertEquals(3, query.page().intValue());
            assertEquals(25, query.limit().intValue());
        }

        @Test
        @DisplayName("one field set leaves the other unset (the method default then applies)")
        void partialSet() {
            PageQuery query = PageQuery.builder().limit(10).build();
            assertNull(query.page());
            assertEquals(10, query.limit().intValue());
        }

        @Test
        @DisplayName("equals/hashCode follow the carried page/limit")
        void valueSemantics() {
            PageQuery a = PageQuery.builder().page(2).limit(20).build();
            PageQuery b = PageQuery.builder().page(2).limit(20).build();
            PageQuery different = PageQuery.builder().page(2).limit(50).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, different);
        }

        @Test
        @DisplayName("toString shows both fields, null for an unset one")
        void toStringShowsFields() {
            assertEquals("PageQuery{page=2, limit=null}", PageQuery.builder().page(2).build().toString());
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
    @DisplayName("SendMessageOptions")
    class SendMessageOptionsTest {

        @Test
        @DisplayName("defaults: no participants, extra or buttons")
        void defaults() {
            SendMessageOptions options = SendMessageOptions.builder().build();

            assertTrue(options.participants().isEmpty());
            assertTrue(options.extra().isEmpty());
            assertTrue(options.buttons().isEmpty());
        }

        @Test
        @DisplayName("extra(Map) is defensively copied")
        void extraDefensiveCopy() {
            Map<String, Object> source = new HashMap<>();
            source.put("a", "1");
            SendMessageOptions options = SendMessageOptions.builder().extra(source).build();

            source.put("b", "2");
            assertEquals(Map.of("a", "1"), options.extra());
        }

        @Test
        @DisplayName("participants(List) is defensively copied")
        void participantsDefensiveCopy() {
            List<Recipient> source = new ArrayList<>();
            source.add(Recipient.of("p1", "Bob"));
            SendMessageOptions options = SendMessageOptions.builder().participants(source).build();

            source.add(Recipient.of("p2", "Carol"));

            assertEquals(1, options.participants().size());
            assertEquals(Recipient.of("p1", "Bob"), options.participants().get(0));
        }

        @Test
        @DisplayName("buttons(List) is defensively copied — later map mutation does not leak")
        void buttonsListDefensiveCopy() {
            Map<String, Object> raw = new HashMap<>();
            raw.put("type", "url");
            raw.put("label", "Open");
            List<Map<String, Object>> source = new ArrayList<>();
            source.add(raw);

            SendMessageOptions options = SendMessageOptions.builder().buttons(source).build();

            raw.put("label", "Changed");
            source.clear();

            assertEquals(1, options.buttons().size());
            assertEquals("Open", options.buttons().get(0).get("label"));
        }

        @Test
        @DisplayName("buttons(Button...) converts typed buttons to maps")
        void buttonsTyped() {
            SendMessageOptions options = SendMessageOptions.builder()
                    .buttons(Button.of(Button.Type.CALL, "Ring"))
                    .build();

            assertEquals(1, options.buttons().size());
            assertEquals("call", options.buttons().get(0).get("type"));
            assertEquals("Ring", options.buttons().get(0).get("label"));
        }

        @Test
        @DisplayName("null extra and buttons reset to empty defaults")
        void nullFieldsResetToEmpty() {
            SendMessageOptions options = SendMessageOptions.builder()
                    .extra(Map.of("a", "1"))
                    .extra((Map<String, Object>) null)
                    .buttons(Button.of(Button.Type.URL, "X"))
                    .buttons((List<Map<String, Object>>) null)
                    .participants(null)
                    .build();

            assertTrue(options.extra().isEmpty());
            assertTrue(options.buttons().isEmpty());
            assertTrue(options.participants().isEmpty());
        }

        @Test
        @DisplayName("equals/hashCode follow the carried fields")
        void valueSemantics() {
            SendMessageOptions a = SendMessageOptions.builder()
                    .participant(Recipient.of("p1", "Bob"))
                    .extra(Map.of("k", "v"))
                    .buttons(Button.of(Button.Type.URL, "Open"))
                    .build();
            SendMessageOptions b = SendMessageOptions.builder()
                    .participant(Recipient.of("p1", "Bob"))
                    .extra(Map.of("k", "v"))
                    .buttons(Button.of(Button.Type.URL, "Open"))
                    .build();
            SendMessageOptions different =
                    SendMessageOptions.builder().extra(Map.of("k", "v")).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, different);
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

    @Nested
    @DisplayName("ApiRequest")
    class ApiRequestTest {

        @Test
        @DisplayName("defaults: GET verb, v1 version, empty query/headers, no body/control")
        void defaults() {
            ApiRequest request = ApiRequest.get("chats").build();

            assertEquals(GetChat.HttpMethod.GET, request.method());
            assertEquals("chats", request.path());
            assertEquals("v1", request.version());
            assertNull(request.body());
            assertTrue(request.query().isEmpty());
            assertTrue(request.headers().isEmpty());
            assertNull(request.control());
        }

        @Test
        @DisplayName("each factory fixes its verb")
        void factoriesFixVerb() {
            assertEquals(GetChat.HttpMethod.POST, ApiRequest.post("x").build().method());
            assertEquals(GetChat.HttpMethod.PUT, ApiRequest.put("x").build().method());
            assertEquals(GetChat.HttpMethod.DELETE, ApiRequest.delete("x").build().method());
        }

        @Test
        @DisplayName("a body on GET or DELETE is rejected in build()")
        void bodyOnReadRejected() {
            assertThrows(GetChatException.class, () -> ApiRequest.get("x").body(Map.of("a", 1)).build());
            assertThrows(GetChatException.class, () -> ApiRequest.delete("x").body(Map.of("a", 1)).build());
        }

        @Test
        @DisplayName("an empty path is rejected in build()")
        void emptyPathRejected() {
            assertThrows(GetChatException.class, () -> ApiRequest.get("").build());
        }

        @Test
        @DisplayName("body, query and headers are defensively copied")
        void defensiveCopies() {
            Map<String, Object> body = new HashMap<>();
            body.put("a", "1");
            Map<String, Object> query = new HashMap<>();
            query.put("q", "1");
            Map<String, String> headers = new HashMap<>();
            headers.put("H", "v");

            ApiRequest request =
                    ApiRequest.post("x").body(body).query(query).headers(headers).build();

            body.put("a", "2");
            query.put("q", "2");
            headers.put("H", "changed");

            assertEquals("1", request.body().get("a"));
            assertEquals("1", request.query().get("q"));
            assertEquals("v", request.headers().get("H"));
        }

        @Test
        @DisplayName("the exposed maps are unmodifiable")
        void unmodifiableViews() {
            ApiRequest request = ApiRequest.post("x")
                    .body(Map.of("a", 1))
                    .query("q", 1)
                    .header("H", "v")
                    .build();

            assertThrows(UnsupportedOperationException.class, () -> request.body().put("b", 2));
            assertThrows(UnsupportedOperationException.class, () -> request.query().put("b", 2));
            assertThrows(UnsupportedOperationException.class, () -> request.headers().put("b", "2"));
        }

        @Test
        @DisplayName("toString masks header values but shows their names")
        void toStringMasksHeaderValues() {
            String text = ApiRequest.put("chats/c1")
                    .header("Authorization", "Bearer super-secret")
                    .body(Map.of("a", 1))
                    .build()
                    .toString();

            assertTrue(text.contains("Authorization=***"), text);
            assertFalse(text.contains("super-secret"), text);
            assertTrue(text.contains("path=chats/c1"), text);
        }

        @Test
        @DisplayName("equals/hashCode follow the described call")
        void valueSemantics() {
            ApiRequest a = ApiRequest.post("x").body(Map.of("k", 1)).query("q", 2).build();
            ApiRequest b = ApiRequest.post("x").body(Map.of("k", 1)).query("q", 2).build();
            ApiRequest differentPath =
                    ApiRequest.post("y").body(Map.of("k", 1)).query("q", 2).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, differentPath);
        }
    }
}
