package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Transport behaviour — request shaping, error mapping and the retry policy. */
class TransportTest {

    private HttpServer server;
    private String origin;

    /** Details of the last request the stub server received. */
    private volatile String lastPath;
    private volatile String lastMethod;
    private volatile String lastBody;
    private volatile String lastAuth;
    private volatile String lastPrefer;
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(int status, String body) {
        handle(exchange -> {
            writeResponse(exchange, status, body);
            return null;
        });
    }

    /** Register a handler that records the request, then delegates. */
    private void handle(ThrowingHandler handler) {
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            lastPath = exchange.getRequestURI().toString();
            lastMethod = exchange.getRequestMethod();
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastPrefer = exchange.getRequestHeaders().getFirst("Prefer");
            try (InputStream in = exchange.getRequestBody()) {
                lastBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            handler.handle(exchange);
        });
    }

    private interface ThrowingHandler {
        Void handle(HttpExchange exchange) throws IOException;
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GetChat sdk() {
        return sdk(RequestOptions.builder().retryDelay(Duration.ZERO).build());
    }

    private GetChat sdk(RequestOptions options) {
        return new GetChat(GetChatConfig.builder()
                .apiToken("test-token")
                .baseUrl(origin)
                .options(options)
                .build());
    }

    @Test
    @DisplayName("GET sends a bearer token and parses the JSON response")
    void getSucceeds() {
        respond(200, "{\"status\":\"ok\",\"data\":[]}");

        JsonValue result = sdk().getChat("chat-1");

        assertEquals("ok", result.get("status").asString());
        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats/chat-1", lastPath);
        assertEquals("Bearer test-token", lastAuth);
    }

    @Test
    @DisplayName("query params are flattened PHP-style and escaped once")
    void getFlattensQuery() {
        respond(200, "{}");

        sdk().listChats(ChatsQuery.builder().page(2).limit(10).type(Chat.Type.GROUP).build());

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats?page=2&limit=10&type=group", lastPath);
    }

    @Test
    @DisplayName("a path param with a slash is encoded, not left to split the path")
    void encodesPathParams() {
        respond(200, "{}");

        sdk().getChat("a/b c");

        assertEquals("/api/v1/chats/a%2Fb%20c", lastPath);
    }

    @Test
    @DisplayName("POST sends the payload as a JSON body")
    void postSendsJsonBody() {
        respond(200, "{}");

        sdk().sendMessage(Chat.of("chat-1"), User.builder().id("u1").name("Alice").build(), "hello");

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/chats/chat-1/messages", lastPath);
        assertTrue(lastBody.contains("\"text\":\"hello\""), lastBody);
        assertTrue(lastBody.contains("\"user\""), lastBody);
    }

    @Test
    @DisplayName("a non-2xx response throws with the status and body attached")
    void errorsCarryStatusAndBody() {
        respond(422, "{\"message\":\"validation failed\"}");

        GetChat sdk = sdk(RequestOptions.builder().retries(0).build());

        GetChatApiException error = assertThrows(GetChatApiException.class, () -> sdk.getChat("chat-1"));

        assertEquals(422, error.status());
        assertEquals("validation failed", error.body().get("message").asString());
        assertTrue(error.getMessage().contains("validation failed"));
    }

    @Test
    @DisplayName("a GET retries a 500 and succeeds on a later attempt")
    void retriesIdempotentServerErrors() {
        AtomicInteger seen = new AtomicInteger();
        handle(exchange -> {
            if (seen.incrementAndGet() < 3) {
                writeResponse(exchange, 500, "{\"message\":\"boom\"}");
            } else {
                writeResponse(exchange, 200, "{\"status\":\"ok\"}");
            }
            return null;
        });

        JsonValue result = sdk().getChat("chat-1");

        assertEquals("ok", result.get("status").asString());
        assertEquals(3, requestCount.get(), "two failures plus the successful attempt");
    }

    @Test
    @DisplayName("a POST does not retry a 500 — the write may already have landed")
    void doesNotRetryWritesOnServerError() {
        respond(500, "{\"message\":\"boom\"}");

        assertThrows(
                GetChatApiException.class,
                () -> sdk().sendMessage(Chat.of("c1"), User.builder().id("u1").build(), "hi"));

        assertEquals(1, requestCount.get(), "no retry for a non-idempotent method");
    }

    @Test
    @DisplayName("a 429 is retried even for a write")
    void retriesRateLimitedWrites() {
        AtomicInteger seen = new AtomicInteger();
        handle(exchange -> {
            if (seen.incrementAndGet() < 2) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                writeResponse(exchange, 429, "{\"message\":\"slow down\"}");
            } else {
                writeResponse(exchange, 200, "{\"status\":\"ok\"}");
            }
            return null;
        });

        JsonValue result =
                sdk().sendMessage(Chat.of("c1"), User.builder().id("u1").build(), "hi");

        assertEquals("ok", result.get("status").asString());
        assertEquals(2, requestCount.get());
    }

    @Test
    @DisplayName("retries stop at the configured limit")
    void stopsAfterConfiguredRetries() {
        respond(500, "{\"message\":\"boom\"}");

        assertThrows(
                GetChatApiException.class,
                () -> sdk(RequestOptions.builder().retries(2).retryDelay(Duration.ZERO).build()).getChat("chat-1"));

        assertEquals(3, requestCount.get(), "the first attempt plus two retries");
    }

    @Test
    @DisplayName("an attempt that outlives its timeout throws GetChatTimeoutException")
    void timesOut() {
        handle(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "{}");
            return null;
        });

        GetChat sdk = sdk(RequestOptions.builder().timeout(Duration.ofMillis(150)).retries(0).build());

        assertThrows(GetChatTimeoutException.class, () -> sdk.getChat("chat-1"));
    }

    @Test
    @DisplayName("per-call control overrides the instance options")
    void perCallOverrides() {
        respond(500, "{\"message\":\"boom\"}");

        assertThrows(
                GetChatApiException.class,
                () -> sdk().listChats((ChatsQuery) null, RequestControl.builder().retries(0).build()));

        assertEquals(1, requestCount.get(), "the per-call retries:0 wins over the instance default");
    }

    @Test
    @DisplayName("PUT carries URL query params alongside an empty body")
    void putWithQueryParams() {
        respond(200, "{}");

        sdk().sendTyping("chat-1", "u1", 10);

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1/typing/u1?time=10", lastPath);
    }

    @Test
    @DisplayName("smart booleans reach the wire as 0/1 integers")
    void smartBooleanFlags() {
        respond(200, "{}");

        // set() feeds a lenient string flag ("yes") through the same coercion the
        // Map form used to exercise; the typed deleted(false) covers the 0 case.
        sdk().listMessages(
                "chat-1",
                MessagesQuery.builder().set("with_users", "yes").deleted(false).page(1).limit(20).build());

        assertEquals("/api/v1/chats/chat-1/messages?page=1&limit=20&isDeleted=0&with_users=1", lastPath);
    }

    @Test
    @DisplayName("input validation happens before any request is made")
    void validatesInput() {
        respond(200, "{}");
        GetChat sdk = sdk();

        assertThrows(GetChatException.class, () -> sdk.getChat(""));
        assertThrows(GetChatException.class, () -> sdk.sendMessage(Chat.of("c1"), User.of("u1"), ""));
        assertThrows(GetChatException.class, () -> sdk.addParticipants("c1", List.of()));

        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("RequestOptions rejects a negative timeout with GetChatException")
    void rejectsNegativeTimeout() {
        // Bounds validation is intentional input validation, so it joins the one
        // GetChatException hierarchy rather than throwing IllegalArgumentException.
        assertThrows(GetChatException.class, () -> RequestOptions.builder().timeout(Duration.ofMillis(-1)).build());
    }

    @Test
    @DisplayName("Duration.ZERO disables the timeout and the backoff wait")
    void zeroDurationDisables() {
        // ZERO is the documented "disabled" sentinel (previously the long 0); it is
        // accepted, not rejected, and round-trips as ZERO through the getters.
        RequestOptions opts = RequestOptions.builder()
                .timeout(Duration.ZERO)
                .retryDelay(Duration.ZERO)
                .build();

        assertEquals(Duration.ZERO, opts.timeout());
        assertEquals(Duration.ZERO, opts.retryDelay());
    }

    @Test
    @DisplayName("RequestOptions rejects a null Duration (required, non-null setting)")
    void rejectsNullDuration() {
        // timeout/retryDelay are required non-null settings under @NullMarked, so a
        // null is a caller bug enforced with NPE — distinct from bounds validation.
        assertThrows(NullPointerException.class, () -> RequestOptions.builder().timeout(null));
        assertThrows(NullPointerException.class, () -> RequestOptions.builder().retryDelay(null));
    }

    @Test
    @DisplayName("a per-call Duration override replaces just that field, inheriting the rest")
    void perCallDurationOverrideApplies() {
        RequestOptions defaults = RequestOptions.builder()
                .timeout(Duration.ofSeconds(30))
                .retries(3)
                .retryDelay(Duration.ofMillis(200))
                .build();

        // null overrides are legal on RequestControl and mean "leave unset".
        RequestOptions merged =
                RequestControl.builder().timeout(Duration.ofMillis(1_000)).build().applyTo(defaults);

        assertEquals(Duration.ofMillis(1_000), merged.timeout(), "the override wins for timeout");
        assertEquals(3, merged.retries(), "the un-overridden retries is inherited");
        assertEquals(Duration.ofMillis(200), merged.retryDelay(), "the un-overridden retryDelay is inherited");
    }

    @Test
    @DisplayName("close() is idempotent and never closes a caller-supplied client")
    void closeIsIdempotent() {
        // Owns its client (no httpClient on the config): closing twice is safe.
        GetChat owned = sdk();
        owned.close();
        owned.close();

        // A caller-supplied client is never torn down by the SDK; close() is a
        // safe no-op on it, so the client survives and stays usable.
        HttpClient shared = HttpClient.newHttpClient();
        GetChat borrowed = new GetChat(GetChatConfig.builder()
                .apiToken("test-token")
                .baseUrl(origin)
                .httpClient(shared)
                .options(RequestOptions.builder().retryDelay(Duration.ZERO).build())
                .build());
        borrowed.close();
        borrowed.close();

        // Prove the shared client wasn't closed: a fresh instance reusing it can
        // still complete a request (meaningful even on JDK 21+, where an owned
        // client would really have been closed).
        respond(200, "{\"status\":\"ok\"}");
        GetChat reuse = new GetChat(GetChatConfig.builder()
                .apiToken("test-token")
                .baseUrl(origin)
                .httpClient(shared)
                .options(RequestOptions.builder().retryDelay(Duration.ZERO).build())
                .build());
        assertEquals("ok", reuse.getChat("chat-1").get("status").asString());
    }

    // ── Typed request builders (stage 3) ─────────────────────────────────────

    @Test
    @DisplayName("MessagesQuery drives the same GET as the raw-map form")
    void messagesQueryShapesRequest() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.listMessages(
                "chat-1",
                MessagesQuery.builder().withUsers(true).deleted(false).edited(true).page(2).limit(20).build());

        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/chats/chat-1/messages?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=20"), lastPath);
        assertTrue(lastPath.contains("with_users=1"), lastPath);
        assertTrue(lastPath.contains("isDeleted=0"), lastPath);
        assertTrue(lastPath.contains("isEdited=1"), lastPath);
    }

    @Test
    @DisplayName("MessagesQuery emits the exact GET bytes the Map form produced")
    void messagesQueryMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        // Frozen bytes: what the (now-internal) Map form emitted for this input.
        // extra "1" is a lenient boolean word, so the flag coerces to "true".
        sdk.listMessages(
                "chat-1",
                MessagesQuery.builder()
                        .withUsers(true)
                        .deleted(false)
                        .extra("is_service", "1")
                        .page(2)
                        .limit(20)
                        .build());

        assertEquals("GET", lastMethod);
        assertEquals(
                "/api/v1/chats/chat-1/messages?page=2&limit=20&extra%5Bis_service%5D=true&isDeleted=0&with_users=1",
                lastPath);
    }

    @Test
    @DisplayName("ChatsQuery drives the same GET as the raw-map form")
    void chatsQueryShapesRequest() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.listChats(ChatsQuery.builder().page(2).limit(10).type(Chat.Type.GROUP).owner("o1").build());

        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/chats?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=10"), lastPath);
        assertTrue(lastPath.contains("type=group"), lastPath);
        assertTrue(lastPath.contains("owner=o1"), lastPath);
    }

    @Test
    @DisplayName("ChatsQuery emits the exact GET bytes the Map form produced")
    void chatsQueryMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        // Frozen bytes: what the (now-internal) Map form emitted for this input,
        // including the PHP-style metadata[plan] nesting and with_owners=1.
        sdk.listChats(ChatsQuery.builder()
                .page(2)
                .limit(10)
                .type(Chat.Type.GROUP)
                .owner("o1")
                .withOwners(true)
                .metadata(Map.of("plan", "pro"))
                .build());

        assertEquals("GET", lastMethod);
        assertEquals(
                "/api/v1/chats?page=2&limit=10&type=group&owner=o1&with_owners=1&metadata%5Bplan%5D=pro",
                lastPath);
    }

    @Test
    @DisplayName("ChatsQuery keeps the limit=1 default when limit is not set")
    void chatsQueryPreservesLimitWart() {
        respond(200, "{}");

        sdk().listChats(ChatsQuery.builder().type(Chat.Type.GROUP).build());

        assertTrue(lastPath.contains("limit=1"), lastPath);
    }

    @Test
    @DisplayName("listChats(null) is unambiguous and sends the default page/limit query")
    void listChatsNullSendsDefaultQuery() {
        respond(200, "{}");

        // With the Map overloads gone, a bare null binds to listChats(ChatsQuery)
        // with no ambiguity, and defaults to page=1, limit=1 (the node-parity wart).
        sdk().listChats(null);

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats?page=1&limit=1", lastPath);
    }

    @Test
    @DisplayName("updateChat(Chat) emits the exact {chat:...} body bytes the Map form produced")
    void updateChatFromBuilderMatchesMap() {
        respond(200, "{}");

        sdk().updateChat("chat-1", Chat.builder().title("New").build());

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1", lastPath);
        assertEquals("{\"chat\":{\"title\":\"New\"}}", lastBody);
    }

    @Test
    @DisplayName("updateUser(User) emits the exact {user:...} body bytes the Map form produced")
    void updateUserFromBuilderMatchesMap() {
        respond(200, "{}");

        sdk().updateUser("u1", User.builder().name("Bob").build());

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/users/u1", lastPath);
        assertEquals("{\"user\":{\"name\":\"Bob\"}}", lastBody);
    }

    @Test
    @DisplayName("UpdateMessageOptions defaults to merge and asks for no representation")
    void updateMessageMergeDefault() {
        respond(200, "{}");

        sdk().updateMessage("chat-1", "m-9", "edited", UpdateMessageOptions.builder()
                .extra(Map.of("k", "v"))
                .build());

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1/messages/m-9", lastPath);
        assertTrue(lastBody.contains("\"text\":\"edited\""), lastBody);
        assertTrue(lastBody.contains("\"update_extra_mode\":\"merge\""), lastBody);
        assertNull(lastPrefer);
    }

    @Test
    @DisplayName("UpdateMessageOptions REPLACE mode sets update_extra_mode=replace")
    void updateMessageReplaceMode() {
        respond(200, "{}");

        sdk().updateMessage("chat-1", "m-9", "edited", UpdateMessageOptions.builder()
                .extra(Map.of("k", "v"))
                .extraMode(UpdateMessageOptions.ExtraMode.REPLACE)
                .build());

        assertTrue(lastBody.contains("\"update_extra_mode\":\"replace\""), lastBody);
    }

    @Test
    @DisplayName("UpdateMessageOptions returnMessage sends Prefer: return=representation")
    void updateMessageReturnMessage() {
        respond(200, "{}");

        sdk().updateMessage("chat-1", "m-9", "edited", UpdateMessageOptions.builder()
                .returnMessage(true)
                .build());

        assertEquals("return=representation", lastPrefer);
    }

    @Test
    @DisplayName("UpdateMessageOptions carries typed buttons and set() message fields")
    void updateMessageTypedButtonsAndEscapeHatch() {
        respond(200, "{}");

        sdk().updateMessage("chat-1", "m-9", null, UpdateMessageOptions.builder()
                .buttons(Button.builder().type(Button.Type.URL).label("Open").action("https://x").build())
                .set("is_deleted", true)
                .build());

        assertTrue(lastBody.contains("\"buttons\""), lastBody);
        assertTrue(lastBody.contains("\"label\":\"Open\""), lastBody);
        assertTrue(lastBody.contains("\"type\":\"url\""), lastBody);
        assertTrue(lastBody.contains("\"is_deleted\":true"), lastBody);
    }

    @Test
    @DisplayName("sendMessage with typed buttons matches the raw-map form byte for byte")
    void sendMessageTypedButtonsMatchMaps() {
        respond(200, "{}");
        GetChat sdk = sdk();

        Button button = Button.builder().type(Button.Type.URL).label("Open").action("https://x").build();

        sdk.sendMessage(
                Chat.of("c1"), User.of("u1"), "hi", SendMessageOptions.builder().buttons(button).build());
        String typedBody = lastBody;
        assertTrue(typedBody.contains("\"buttons\""), typedBody);
        assertTrue(typedBody.contains("\"label\":\"Open\""), typedBody);

        sdk.sendMessage(
                Chat.of("c1"),
                User.of("u1"),
                "hi",
                SendMessageOptions.builder().buttons(List.of(button.asMap())).build());
        assertEquals(lastBody, typedBody);
    }

    @Test
    @DisplayName("sendMessage with SendMessageOptions carries participants, extra and buttons")
    void sendMessageWithOptions() {
        respond(200, "{}");

        sdk().sendMessage(
                Chat.builder().id("c1").create(true).build(),
                User.of("u1"),
                "hi",
                SendMessageOptions.builder()
                        .participant(Recipient.of("p1", "Bob"))
                        .extra(Map.of("source", "bot"))
                        .buttons(Button.builder()
                                .type(Button.Type.URL)
                                .label("Open")
                                .action("https://x")
                                .build())
                        .build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/chats/c1/messages", lastPath);
        assertTrue(lastBody.contains("\"text\":\"hi\""), lastBody);
        assertTrue(lastBody.contains("\"extra\""), lastBody);
        assertTrue(lastBody.contains("\"source\":\"bot\""), lastBody);
        assertTrue(lastBody.contains("\"buttons\""), lastBody);
        assertTrue(lastBody.contains("\"label\":\"Open\""), lastBody);
        assertTrue(lastBody.contains("\"participants\""), lastBody);
    }

    @Test
    @DisplayName("null SendMessageOptions applies all defaults, like the plain-text overload")
    void sendMessageNullOptionsMatchesPlainText() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.sendMessage(Chat.of("chat-1"), User.of("u1"), "hello", null);
        String withNull = lastBody;

        sdk.sendMessage(Chat.of("chat-1"), User.of("u1"), "hello");
        assertEquals(lastBody, withNull);
    }

    @Test
    @DisplayName("createChat(Chat) posts just the chat, no participants")
    void createChatConvenience() {
        respond(200, "{}");

        sdk().createChat(Chat.builder().id("c1").title("Support").build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/chats", lastPath);
        assertTrue(lastBody.contains("\"chat\""), lastBody);
        assertTrue(lastBody.contains("\"title\":\"Support\""), lastBody);
        assertFalse(lastBody.contains("participants"), lastBody);
    }

    @Test
    @DisplayName("sendTyping(chatId, userId) sends no time query param")
    void sendTypingConvenience() {
        respond(200, "{}");

        sdk().sendTyping("chat-1", "u1");

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1/typing/u1", lastPath);
    }

    // ── PageQuery pagination (participants / user chats) ──────────────────────

    @Test
    @DisplayName("listParticipants(chatId) defaults to page 1, limit 50")
    void listParticipantsConvenience() {
        respond(200, "{}");

        sdk().listParticipants("chat-1");

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats/chat-1/participants?page=1&limit=50", lastPath);
    }

    @Test
    @DisplayName("listParticipants(chatId, PageQuery) sends the requested page/limit")
    void listParticipantsWithPageQuery() {
        respond(200, "{}");

        sdk().listParticipants("chat-1", PageQuery.builder().page(3).limit(25).build());

        assertEquals("/api/v1/chats/chat-1/participants?page=3&limit=25", lastPath);
    }

    @Test
    @DisplayName("an unset PageQuery field falls back to the method default")
    void listParticipantsPageQueryPartialDefault() {
        respond(200, "{}");

        sdk().listParticipants("chat-1", PageQuery.builder().limit(10).build());

        assertEquals("/api/v1/chats/chat-1/participants?page=1&limit=10", lastPath);
    }

    @Test
    @DisplayName("the private layer still clamps page up to 1 and limit down to 1000")
    void listParticipantsClampsPreserved() {
        respond(200, "{}");

        sdk().listParticipants("chat-1", PageQuery.builder().page(0).limit(5000).build());

        assertEquals("/api/v1/chats/chat-1/participants?page=1&limit=1000", lastPath);
    }

    @Test
    @DisplayName("listUserChats(userId) defaults to page 1, limit 50")
    void listUserChatsConvenience() {
        respond(200, "{}");

        sdk().listUserChats("u-1");

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/users/u-1/chats?page=1&limit=50", lastPath);
    }

    @Test
    @DisplayName("listUserChats(userId, PageQuery) sends the requested page/limit")
    void listUserChatsWithPageQuery() {
        respond(200, "{}");

        sdk().listUserChats("u-1", PageQuery.builder().page(2).limit(100).build());

        assertEquals("/api/v1/users/u-1/chats?page=2&limit=100", lastPath);
    }

    // ── requestApi(ApiRequest) escape hatch ──────────────────────────────────

    @Test
    @DisplayName("requestApi(ApiRequest) GET carries the query string and bearer token")
    void apiRequestGet() {
        respond(200, "{\"status\":\"ok\"}");

        JsonValue result = sdk().requestApi(
                ApiRequest.get("chats").query("page", 2).query("limit", 10).build());

        assertEquals("ok", result.get("status").asString());
        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/chats?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=10"), lastPath);
        assertEquals("Bearer test-token", lastAuth);
    }

    @Test
    @DisplayName("requestApi(ApiRequest) POST sends body, URL query and a custom header")
    void apiRequestPost() {
        respond(200, "{}");

        sdk().requestApi(ApiRequest.post("chats/c1/webhook")
                .body(Map.of("url", "https://example.com/hook"))
                .query("dry_run", 1)
                .header("Prefer", "return=representation")
                .build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/chats/c1/webhook?dry_run=1", lastPath);
        assertTrue(lastBody.contains("\"url\":\"https://example.com/hook\""), lastBody);
        assertEquals("return=representation", lastPrefer);
    }

    @Test
    @DisplayName("requestApi(ApiRequest) GET matches the wrapped method byte for byte")
    void apiRequestGetMatchesWrapped() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.getChat("chat-1");
        String wrappedMethod = lastMethod;
        String wrappedPath = lastPath;

        sdk.requestApi(ApiRequest.get("chats/chat-1").build());

        assertEquals(wrappedMethod, lastMethod);
        assertEquals(wrappedPath, lastPath);
    }

    @Test
    @DisplayName("requestApi(ApiRequest) PUT body matches the wrapped method byte for byte")
    void apiRequestBodyMatchesWrapped() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.updateUser("u1", User.builder().name("Bob").build());
        String wrappedPath = lastPath;
        String wrappedBody = lastBody;

        sdk.requestApi(ApiRequest.put("users/u1")
                .body(Map.of("user", Map.of("name", "Bob")))
                .build());

        assertEquals(wrappedPath, lastPath);
        assertEquals(wrappedBody, lastBody);
    }
}
