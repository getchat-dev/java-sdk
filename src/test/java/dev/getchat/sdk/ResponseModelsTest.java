package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The typed response models ({@link ChatDetails}, {@link Message}, {@link Page},
 * {@link SentMessages}, {@link UpdatedMessage}). These live in-package so they can
 * lower a JSON fixture to a {@link JsonValue} through the package-private
 * {@link JsonValue#wrap} seam and drive the models the same way the client does.
 *
 * <p>The fixtures follow the {@code ChatResource} / {@code MessageResource} shapes
 * in the backend's {@code openapi.yml}.
 */
class ResponseModelsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lower a JSON string to a {@link JsonValue}, the way a parsed response arrives. */
    private static JsonValue jv(String json) {
        try {
            return JsonValue.wrap(MAPPER.readTree(json));
        } catch (JsonProcessingException e) {
            throw new AssertionError("bad test fixture: " + e.getMessage(), e);
        }
    }

    // ── ChatDetails ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("ChatDetails reads a full ChatResource through typed accessors")
    void chatDetailsFull() {
        ChatDetails chat = ChatDetails.of(jv("""
                {"id":"c-1","type":"supergroup","title":"Support",
                 "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00Z",
                 "last_message_at":"2026-07-16T12:29:00+00:00","owner_id":"o-1",
                 "metadata":{"plan":"pro"},"future_field":"forward-compatible"}"""));

        assertEquals("c-1", chat.id());
        assertEquals(Chat.Type.SUPERGROUP, chat.type());
        assertEquals("Support", chat.title());
        assertEquals(Instant.parse("2026-07-16T12:00:00Z"), chat.createdAt());
        assertEquals(Instant.parse("2026-07-16T12:30:00Z"), chat.updatedAt());
        assertEquals(Instant.parse("2026-07-16T12:29:00Z"), chat.lastMessageAt());
        assertEquals("o-1", chat.ownerId());
        assertEquals("pro", chat.metadata().get("plan"));

        // Unknown future fields survive through raw().
        assertEquals("forward-compatible", chat.raw().get("future_field").asString(""));
    }

    @Test
    @DisplayName("ChatDetails.metadata(): mixed scalars survive as typed values, non-scalars are dropped")
    void chatDetailsMetadataScalars() {
        ChatDetails chat = ChatDetails.of(jv("""
                {"id":"c-1","metadata":{"plan":"pro","seats":5,"trial":true,
                 "empty":null,"nested":{"a":1},"tags":["x"]}}"""));

        var metadata = chat.metadata();
        // String, Number and Boolean scalars are kept, each as its Java type.
        assertEquals("pro", metadata.get("plan"));
        assertEquals(5, ((Number) metadata.get("seats")).intValue());
        assertEquals(Boolean.TRUE, metadata.get("trial"));
        // null and non-scalar (object / array) values are dropped, not surfaced.
        assertFalse(metadata.containsKey("empty"), "a null value is dropped");
        assertFalse(metadata.containsKey("nested"), "a nested object is dropped");
        assertFalse(metadata.containsKey("tags"), "a nested array is dropped");
        assertEquals(3, metadata.size());
    }

    @Test
    @DisplayName("ChatDetails.owner(): an embedded owner object is a typed UserDetails")
    void chatDetailsOwner() {
        ChatDetails chat = ChatDetails.of(jv("""
                {"id":"c-1","owner_id":"o-1",
                 "owner":{"id":"o-1","name":"Olivia","email":"olivia@example.com"}}"""));

        assertNotNull(chat.owner());
        assertEquals("o-1", chat.owner().id());
        assertEquals("Olivia", chat.owner().name());
        assertEquals("olivia@example.com", chat.owner().email());
    }

    @Test
    @DisplayName("ChatDetails: absent optionals are null, required id is a lenient empty string")
    void chatDetailsMinimal() {
        ChatDetails chat = ChatDetails.of(jv("{\"id\":\"c-2\"}"));

        assertEquals("c-2", chat.id());
        assertNull(chat.type());
        assertNull(chat.title());
        assertNull(chat.createdAt());
        assertNull(chat.updatedAt());
        assertNull(chat.lastMessageAt());
        assertNull(chat.lastMessage());
        assertNull(chat.ownerId());
        assertNull(chat.owner());
        assertTrue(chat.metadata().isEmpty());

        // Spec-required id, absent (spec violation): lenient empty string, not a throw.
        assertEquals("", ChatDetails.of(jv("{}")).id());
    }

    @Test
    @DisplayName("ChatDetails: explicit null type and an unknown future type both map to null")
    void chatDetailsLenientType() {
        assertNull(ChatDetails.of(jv("{\"id\":\"c\",\"type\":null}")).type(), "legacy null type");
        assertNull(ChatDetails.of(jv("{\"id\":\"c\",\"type\":\"megagroup\"}")).type(), "unknown future type");
        assertEquals(Chat.Type.PRIVATE, ChatDetails.of(jv("{\"id\":\"c\",\"type\":\"private\"}")).type());
    }

    @Test
    @DisplayName("ChatDetails: an unparseable date yields null, never an exception")
    void chatDetailsBrokenDate() {
        ChatDetails chat = ChatDetails.of(jv("{\"id\":\"c\",\"created_at\":\"not-a-date\",\"updated_at\":12345}"));
        assertNull(chat.createdAt());
        assertNull(chat.updatedAt(), "a non-string date is not coerced");
    }

    @Test
    @DisplayName("ChatDetails: embedded last_message is a typed Message")
    void chatDetailsLastMessage() {
        ChatDetails chat = ChatDetails.of(jv("""
                {"id":"c-1","last_message":{"id":"m-1","seq":9,"user_id":"u-1","text":"hi",
                 "created_at":1752664800,"is_deleted":false,"is_edited":false,"versions":0,"extra":[]}}"""));

        assertNotNull(chat.lastMessage());
        assertEquals("m-1", chat.lastMessage().id());
        assertEquals("hi", chat.lastMessage().text());
    }

    @Test
    @DisplayName("ChatDetails value semantics: equal by raw JSON, compact toString")
    void chatDetailsValueSemantics() {
        String json = "{\"id\":\"c-1\",\"type\":\"group\",\"title\":\"T\"}";
        ChatDetails a = ChatDetails.of(jv(json));
        ChatDetails b = ChatDetails.of(jv(json));
        ChatDetails c = ChatDetails.of(jv("{\"id\":\"c-2\"}"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertTrue(a.toString().startsWith("ChatDetails{id=c-1"), a.toString());
    }

    // ── Message ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Message reads a full MessageResource; timestamps are epoch seconds")
    void messageFull() {
        Message message = Message.of(jv("""
                {"id":"m-1","seq":7,"user_id":"u-1","text":"hello","created_at":1752664800,
                 "updated_at":1752665000,"is_deleted":false,"is_edited":true,"versions":2,
                 "extra":{"is_service":true},"recipient_id":"r-1",
                 "buttons":[{"type":"url","label":"Open"}]}"""));

        assertEquals("m-1", message.id());
        assertEquals(7, message.seq());
        assertEquals("u-1", message.userId());
        assertEquals("hello", message.text());
        assertEquals(Instant.ofEpochSecond(1752664800), message.createdAt());
        assertEquals(Instant.ofEpochSecond(1752665000), message.updatedAt());
        assertFalse(message.isDeleted());
        assertTrue(message.isEdited());
        assertEquals(2, message.versions());
        assertEquals("r-1", message.recipientId());
        assertEquals(1, message.buttons().size());
        assertEquals("Open", message.buttons().get(0).label());
        assertEquals(Button.Type.URL, message.buttons().get(0).type());
    }

    // ── ButtonDetails ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("ButtonDetails: a full button maps every field to its typed form")
    void buttonDetailsFull() {
        Message message = Message.of(jv("""
                {"id":"m-1","buttons":[
                  {"type":"local","label":"Dismiss","action":"noop",
                   "state":"disabled","style":"negative"}]}"""));

        assertEquals(1, message.buttons().size());
        ButtonDetails button = message.buttons().get(0);
        assertEquals(Button.Type.LOCAL, button.type());
        assertEquals("Dismiss", button.label());
        assertEquals("noop", button.action());
        assertEquals(Button.State.DISABLED, button.state());
        assertEquals(Button.Style.NEGATIVE, button.style());
    }

    @Test
    @DisplayName("ButtonDetails: a minimal button leaves the optional fields null")
    void buttonDetailsMinimal() {
        Message message = Message.of(jv("""
                {"id":"m-1","buttons":[{"type":"url","label":"Open"}]}"""));

        ButtonDetails button = message.buttons().get(0);
        assertEquals(Button.Type.URL, button.type());
        assertEquals("Open", button.label());
        assertNull(button.action());
        assertNull(button.state());
        assertNull(button.style());
    }

    @Test
    @DisplayName("ButtonDetails: unrecognised enum wire values map to null, never throw")
    void buttonDetailsUnknownEnums() {
        Message message = Message.of(jv("""
                {"id":"m-1","buttons":[
                  {"type":"teleport","label":"Go","state":"glowing","style":"rainbow"}]}"""));

        ButtonDetails button = message.buttons().get(0);
        assertNull(button.type(), "unknown type -> null");
        assertNull(button.state(), "unknown state -> null");
        assertNull(button.style(), "unknown style -> null");
        // The label (a required scalar) is still read; unknown values stay reachable via raw().
        assertEquals("Go", button.label());
        assertEquals("teleport", button.raw().get("type").asString(""));
    }

    @Test
    @DisplayName("ButtonDetails value semantics: equal by raw JSON, compact toString")
    void buttonDetailsValueSemantics() {
        ButtonDetails a = ButtonDetails.of(jv("{\"type\":\"url\",\"label\":\"Open\"}"));
        ButtonDetails b = ButtonDetails.of(jv("{\"type\":\"url\",\"label\":\"Open\"}"));
        ButtonDetails c = ButtonDetails.of(jv("{\"type\":\"call\",\"label\":\"Call\"}"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("ButtonDetails{type=URL, label=Open}", a.toString());
    }

    @Test
    @DisplayName("Message: deleted message has null text; empty extra is a JSON array")
    void messageDeletedAndEmptyExtra() {
        Message message = Message.of(jv("""
                {"id":"m-2","seq":8,"user_id":"u-2","text":null,"created_at":1752664900,
                 "updated_at":null,"is_deleted":true,"is_edited":false,"versions":0,"extra":[]}"""));

        assertNull(message.text());
        assertNull(message.updatedAt(), "updated_at null until first edit");
        assertTrue(message.isDeleted());
        // extra arrives as [] when empty; it is still chain-safe and toMap() is empty.
        assertTrue(message.extra().isArray());
        assertTrue(message.extra().toMap().isEmpty());
        assertNull(message.recipientId());
        assertTrue(message.buttons().isEmpty());
    }

    @Test
    @DisplayName("Message: an empty object yields lenient defaults, never a throw")
    void messageMinimal() {
        Message message = Message.of(jv("{}"));
        assertEquals("", message.id());
        assertEquals(0, message.seq());
        assertEquals("", message.userId());
        assertNull(message.text());
        assertNull(message.createdAt());
        assertFalse(message.isDeleted());
        assertEquals(0, message.versions());
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Page orders items by the sort array and exposes the spec pagination fields")
    void pageOrdersAndPaginates() {
        Page<ChatDetails> page = Page.of(jv("""
                {"chats":{"c-1":{"id":"c-1"},"c-2":{"id":"c-2"},"c-3":{"id":"c-3"}},
                 "chats_sort":["c-3","c-1","c-2"],
                 "meta":{"total":3,"output":3},
                 "pagination":{"items_per_page":50,"current":2,"total":5,
                               "next_page_url":"http://x/next","prev_page_url":null}}"""),
                "chats_sort", "chats", ChatDetails::of);

        List<ChatDetails> items = page.items();
        assertEquals(List.of("c-3", "c-1", "c-2"), items.stream().map(ChatDetails::id).toList());

        assertEquals(50, page.itemsPerPage());
        assertEquals(2, page.currentPage());
        assertEquals(5, page.pageCount());
        assertEquals(3, page.totalCount());
        assertEquals(3, page.outputCount());
        assertEquals("http://x/next", page.nextPageUrl());
        assertNull(page.prevPageUrl());
    }

    @Test
    @DisplayName("Page over an empty list (map serialized as []) is safe and empty")
    void pageEmpty() {
        Page<Message> page = Page.of(jv("""
                {"messages":[],"messages_sort":[],"meta":{"total":0,"output":0},
                 "pagination":{"items_per_page":50,"current":0,"total":0}}"""),
                "messages_sort", "messages", Message::of);

        assertTrue(page.items().isEmpty());
        assertEquals(0, page.currentPage());
        assertEquals(0, page.totalCount());
    }

    @Test
    @DisplayName("Page over a non-list body degrades gracefully to empty items")
    void pageNonList() {
        Page<ChatDetails> page = Page.of(jv("{}"), "chats_sort", "chats", ChatDetails::of);
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.itemsPerPage());
    }

    // ── SentMessages / UpdatedMessage ─────────────────────────────────────────

    @Test
    @DisplayName("SentMessages exposes the created ids; raw() keeps the full envelope")
    void sentMessages() {
        SentMessages sent = SentMessages.of(jv("{\"status\":true,\"message_ids\":[\"m-1\",\"m-2\"]}"));
        assertEquals(List.of("m-1", "m-2"), sent.messageIds());
        assertTrue(sent.raw().get("status").asBoolean(false));

        assertTrue(SentMessages.of(jv("{\"status\":true}")).messageIds().isEmpty());
    }

    @Test
    @DisplayName("UpdatedMessage: message present only when the backend echoed it")
    void updatedMessage() {
        UpdatedMessage withMessage = UpdatedMessage.of(jv("""
                {"status":true,"is_updated":true,
                 "message":{"id":"m-9","seq":7,"user_id":"u-1","text":"edited","created_at":1752664800,
                            "is_deleted":false,"is_edited":true,"versions":1,"extra":[]}}"""));
        assertTrue(withMessage.isUpdated());
        assertNotNull(withMessage.message());
        assertEquals("m-9", withMessage.message().id());

        UpdatedMessage minimal = UpdatedMessage.of(jv("{\"status\":true,\"is_updated\":false}"));
        assertFalse(minimal.isUpdated());
        assertNull(minimal.message());
    }

    // ── UserDetails ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("UserDetails reads a full UserResource; timestamps are ISO date-times")
    void userDetailsFull() {
        UserDetails user = UserDetails.of(jv("""
                {"id":"u-1","name":"Alice","email":"alice@example.com","link":"https://x/alice",
                 "picture":"https://cdn/alice.png",
                 "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00Z",
                 "metadata":{"plan":"pro"},"future_field":"forward-compatible"}"""));

        assertEquals("u-1", user.id());
        assertEquals("Alice", user.name());
        assertEquals("alice@example.com", user.email());
        assertEquals("https://x/alice", user.link());
        assertEquals(Instant.parse("2026-07-16T12:00:00Z"), user.createdAt());
        assertEquals(Instant.parse("2026-07-16T12:30:00Z"), user.updatedAt());
        assertEquals("pro", user.metadata().get("plan"));

        // A string picture is the URL form of Avatar.
        assertNotNull(user.picture());
        assertTrue(user.picture().isUrl());
        assertEquals("https://cdn/alice.png", user.picture().url());
        assertNull(user.picture().kind());

        // Unknown future fields survive through raw().
        assertEquals("forward-compatible", user.raw().get("future_field").asString(""));
    }

    @Test
    @DisplayName("UserDetails.metadata(): a numeric/boolean scalar is kept as-is (reader is lenient)")
    void userDetailsMetadataScalars() {
        UserDetails user = UserDetails.of(jv("""
                {"id":"u-1","name":"Alice","metadata":{"plan":"pro","seats":5,"trial":true,"nested":{"a":1}}}"""));

        assertEquals("pro", user.metadata().get("plan"));
        assertEquals(5, ((Number) user.metadata().get("seats")).intValue());
        assertEquals(Boolean.TRUE, user.metadata().get("trial"));
        assertFalse(user.metadata().containsKey("nested"));
    }

    @Test
    @DisplayName("UserDetails: a generated-avatar picture is the object form of Avatar")
    void userDetailsGeneratedAvatar() {
        UserDetails user = UserDetails.of(jv("""
                {"id":"u-2","name":"Bob","picture":{"kind":"auto","color":"#f00","initials":"BB"}}"""));

        Avatar picture = user.picture();
        assertNotNull(picture);
        assertFalse(picture.isUrl());
        assertNull(picture.url());
        assertEquals("auto", picture.kind());
        assertEquals("#f00", picture.color());
        assertEquals("BB", picture.initials());
    }

    @Test
    @DisplayName("Avatar value semantics: equal by raw JSON, compact toString for both forms")
    void avatarValueSemantics() {
        Avatar url = Avatar.of(jv("\"https://cdn/a.png\""));
        Avatar sameUrl = Avatar.of(jv("\"https://cdn/a.png\""));
        Avatar placeholder = Avatar.of(jv("{\"kind\":\"auto\",\"initials\":\"AB\"}"));

        assertEquals(url, sameUrl);
        assertEquals(url.hashCode(), sameUrl.hashCode());
        assertNotEquals(url, placeholder);
        assertEquals("Avatar{url=https://cdn/a.png}", url.toString());
        assertEquals("Avatar{kind=auto, initials=AB}", placeholder.toString());
    }

    @Test
    @DisplayName("UserDetails: absent optionals are null, required id/name are lenient empty strings")
    void userDetailsMinimal() {
        UserDetails user = UserDetails.of(jv("{\"id\":\"u-3\"}"));

        assertEquals("u-3", user.id());
        assertEquals("", user.name(), "required name absent (spec violation): lenient empty string");
        assertNull(user.email());
        assertNull(user.link());
        assertNull(user.createdAt());
        assertNull(user.updatedAt());
        assertNull(user.picture());
        assertTrue(user.metadata().isEmpty());

        assertEquals("", UserDetails.of(jv("{}")).id());
    }

    @Test
    @DisplayName("UserDetails: an unparseable date yields null, never an exception")
    void userDetailsBrokenDate() {
        UserDetails user = UserDetails.of(jv("{\"id\":\"u\",\"created_at\":\"not-a-date\",\"updated_at\":12345}"));
        assertNull(user.createdAt());
        assertNull(user.updatedAt(), "a non-string date is not coerced");
    }

    @Test
    @DisplayName("UserDetails value semantics: equal by raw JSON, compact toString")
    void userDetailsValueSemantics() {
        String json = "{\"id\":\"u-1\",\"name\":\"Alice\"}";
        UserDetails a = UserDetails.of(jv(json));
        UserDetails b = UserDetails.of(jv(json));
        UserDetails c = UserDetails.of(jv("{\"id\":\"u-2\",\"name\":\"Bob\"}"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("UserDetails{id=u-1, name=Alice}", a.toString());
    }

    // ── Participant ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Participant reads a full ParticipantResource (no metadata field in the schema)")
    void participantFull() {
        Participant participant = Participant.of(jv("""
                {"id":"u-1","name":"Alice","email":"alice@example.com","link":"https://x/alice",
                 "picture":"https://cdn/alice.png",
                 "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00Z",
                 "future_field":"forward-compatible"}"""));

        assertEquals("u-1", participant.id());
        assertEquals("Alice", participant.name());
        assertEquals("alice@example.com", participant.email());
        assertEquals("https://x/alice", participant.link());
        assertEquals(Instant.parse("2026-07-16T12:00:00Z"), participant.createdAt());
        assertEquals(Instant.parse("2026-07-16T12:30:00Z"), participant.updatedAt());
        assertNotNull(participant.picture());
        assertTrue(participant.picture().isUrl());
        assertEquals("https://cdn/alice.png", participant.picture().url());
        // Unknown future fields survive through raw() (ParticipantResource has no metadata).
        assertEquals("forward-compatible", participant.raw().get("future_field").asString(""));
    }

    @Test
    @DisplayName("Participant: absent optionals are null, required id/name are lenient empty strings")
    void participantMinimal() {
        Participant participant = Participant.of(jv("{\"id\":\"u-3\"}"));

        assertEquals("u-3", participant.id());
        assertEquals("", participant.name());
        assertNull(participant.email());
        assertNull(participant.link());
        assertNull(participant.createdAt());
        assertNull(participant.updatedAt());
        assertNull(participant.picture());

        assertEquals("", Participant.of(jv("{}")).id());
    }

    @Test
    @DisplayName("Participant value semantics: equal by raw JSON, compact toString")
    void participantValueSemantics() {
        String json = "{\"id\":\"u-1\",\"name\":\"Alice\"}";
        Participant a = Participant.of(jv(json));
        Participant b = Participant.of(jv(json));
        Participant c = Participant.of(jv("{\"id\":\"u-2\",\"name\":\"Bob\"}"));

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertEquals("Participant{id=u-1, name=Alice}", a.toString());
    }

    // ── Page (plain-array shape: participants / user chats) ─────────────────────

    @Test
    @DisplayName("Page.ofArray maps a plain array in element order and reads pagination")
    void pageArrayOrdersAndPaginates() {
        Page<Participant> page = Page.ofArray(jv("""
                {"status":true,
                 "participants":[{"id":"u-2","name":"Bob"},{"id":"u-1","name":"Alice"}],
                 "meta":{"total":2,"output":0},
                 "pagination":{"items_per_page":50,"current":1,"total":1}}"""),
                "participants", Participant::of);

        List<Participant> items = page.items();
        // Order follows the array as returned, not sorted.
        assertEquals(List.of("u-2", "u-1"), items.stream().map(Participant::id).toList());
        assertEquals("Bob", items.get(0).name());

        assertEquals(50, page.itemsPerPage());
        assertEquals(1, page.currentPage());
        assertEquals(1, page.pageCount());
        assertEquals(2, page.totalCount());
        // meta.output is always 0 for the participant list (backend bug) — count items().
        assertEquals(0, page.outputCount());
        // The participant list omits next/prev page urls.
        assertNull(page.nextPageUrl());
        assertNull(page.prevPageUrl());
    }

    @Test
    @DisplayName("Page.ofArray over user chats reads ChatResource elements and page urls")
    void pageArrayUserChats() {
        Page<ChatDetails> page = Page.ofArray(jv("""
                {"status":true,
                 "chats":[{"id":"c-1","type":"group","title":"First"}],
                 "meta":{"total":1,"output":1},
                 "pagination":{"items_per_page":50,"current":2,"total":3,
                               "next_page_url":"http://x/next","prev_page_url":"http://x/prev"}}"""),
                "chats", ChatDetails::of);

        assertEquals(1, page.items().size());
        assertEquals("c-1", page.items().get(0).id());
        assertEquals(Chat.Type.GROUP, page.items().get(0).type());
        assertEquals("http://x/next", page.nextPageUrl());
        assertEquals("http://x/prev", page.prevPageUrl());
    }

    @Test
    @DisplayName("Page.ofArray over an empty/absent array is safe and empty")
    void pageArrayEmpty() {
        Page<Participant> empty = Page.ofArray(jv("""
                {"participants":[],"meta":{"total":0,"output":0},
                 "pagination":{"items_per_page":50,"current":0,"total":0}}"""),
                "participants", Participant::of);
        assertTrue(empty.items().isEmpty());
        assertEquals(0, empty.totalCount());

        // A body without the array key degrades gracefully.
        assertTrue(Page.ofArray(jv("{}"), "participants", Participant::of).items().isEmpty());
    }

    // ── Page as Iterable / stream / size ────────────────────────────────────────

    @Test
    @DisplayName("Iterating a map+sort page directly matches items() order")
    void pageForEachMapShape() {
        Page<ChatDetails> page = Page.of(jv("""
                {"chats":{"c-1":{"id":"c-1"},"c-2":{"id":"c-2"},"c-3":{"id":"c-3"}},
                 "chats_sort":["c-3","c-1","c-2"]}"""),
                "chats_sort", "chats", ChatDetails::of);

        List<String> viaForEach = new ArrayList<>();
        for (ChatDetails c : page) {
            viaForEach.add(c.id());
        }
        assertEquals(List.of("c-3", "c-1", "c-2"), viaForEach);
        assertEquals(page.items().stream().map(ChatDetails::id).toList(), viaForEach);
    }

    @Test
    @DisplayName("Iterating a plain-array page directly matches items() order")
    void pageForEachArrayShape() {
        Page<Participant> page = Page.ofArray(jv("""
                {"participants":[{"id":"u-2","name":"Bob"},{"id":"u-1","name":"Alice"}]}"""),
                "participants", Participant::of);

        List<String> viaForEach = new ArrayList<>();
        for (Participant p : page) {
            viaForEach.add(p.id());
        }
        assertEquals(List.of("u-2", "u-1"), viaForEach);
        assertEquals(page.items().stream().map(Participant::id).toList(), viaForEach);
    }

    @Test
    @DisplayName("stream() counts and maps the page's elements")
    void pageStream() {
        Page<ChatDetails> page = Page.of(jv("""
                {"chats":{"c-1":{"id":"c-1"},"c-2":{"id":"c-2"}},
                 "chats_sort":["c-1","c-2"]}"""),
                "chats_sort", "chats", ChatDetails::of);

        assertEquals(2, page.stream().count());
        assertEquals(List.of("c-1", "c-2"), page.stream().map(ChatDetails::id).toList());
    }

    @Test
    @DisplayName("size() / isEmpty() report this page's element count")
    void pageSizeAndIsEmpty() {
        Page<ChatDetails> nonEmpty = Page.of(jv("""
                {"chats":{"c-1":{"id":"c-1"},"c-2":{"id":"c-2"}},
                 "chats_sort":["c-1","c-2"],
                 "meta":{"total":9},"pagination":{"total":5}}"""),
                "chats_sort", "chats", ChatDetails::of);
        assertEquals(2, nonEmpty.size());
        assertFalse(nonEmpty.isEmpty());
        // size() is this page's count, distinct from totalCount()/pageCount().
        assertEquals(9, nonEmpty.totalCount());
        assertEquals(5, nonEmpty.pageCount());

        Page<Participant> empty = Page.ofArray(jv("""
                {"participants":[]}"""), "participants", Participant::of);
        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
    }

    @Test
    @DisplayName("The items list is assembled once and reused (memoized)")
    void pageItemsMemoized() {
        Page<ChatDetails> page = Page.of(jv("""
                {"chats":{"c-1":{"id":"c-1"}},"chats_sort":["c-1"]}"""),
                "chats_sort", "chats", ChatDetails::of);

        // Same list instance every call — items() no longer re-derives the list.
        assertSame(page.items(), page.items());
        assertEquals("c-1", page.items().get(0).id());
        assertEquals(1, page.size());
    }
}
