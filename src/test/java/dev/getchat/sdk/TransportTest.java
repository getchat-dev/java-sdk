package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        return sdk(RequestOptions.builder().retryDelay(0).build());
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

        JsonValue result = sdk().getChatInfo("chat-1");

        assertEquals("ok", result.get("status").asString());
        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats/chat-1", lastPath);
        assertEquals("Bearer test-token", lastAuth);
    }

    @Test
    @DisplayName("query params are flattened PHP-style and escaped once")
    void getFlattensQuery() {
        respond(200, "{}");

        sdk().getChats(Map.of("page", 2, "limit", 10, "type", "group"));

        assertTrue(lastPath.startsWith("/api/v1/chats?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=10"), lastPath);
        assertTrue(lastPath.contains("type=group"), lastPath);
    }

    @Test
    @DisplayName("a path param with a slash is encoded, not left to split the path")
    void encodesPathParams() {
        respond(200, "{}");

        sdk().getChatInfo("a/b c");

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

        GetChatApiException error = assertThrows(GetChatApiException.class, () -> sdk.getChatInfo("chat-1"));

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

        JsonValue result = sdk().getChatInfo("chat-1");

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
                () -> sdk(RequestOptions.builder().retries(2).retryDelay(0).build()).getChatInfo("chat-1"));

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

        GetChat sdk = sdk(RequestOptions.builder().timeout(150).retries(0).build());

        assertThrows(GetChatTimeoutException.class, () -> sdk.getChatInfo("chat-1"));
    }

    @Test
    @DisplayName("per-call control overrides the instance options")
    void perCallOverrides() {
        respond(500, "{\"message\":\"boom\"}");

        assertThrows(
                GetChatApiException.class,
                () -> sdk().getChats(Map.of(), RequestControl.builder().retries(0).build()));

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

        sdk().getMessagesFromChat("chat-1", Map.of("with_users", "yes", "isDeleted", false), 1, 20);

        assertTrue(lastPath.contains("with_users=1"), lastPath);
        assertTrue(lastPath.contains("isDeleted=0"), lastPath);
    }

    @Test
    @DisplayName("input validation happens before any request is made")
    void validatesInput() {
        respond(200, "{}");
        GetChat sdk = sdk();

        assertThrows(GetChatException.class, () -> sdk.getChatInfo(""));
        assertThrows(GetChatException.class, () -> sdk.sendMessage(Chat.of("c1"), User.of("u1"), ""));
        assertThrows(GetChatException.class, () -> sdk.addParticipantsToChat("c1", List.of()));

        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("RequestOptions rejects a negative timeout with GetChatException")
    void rejectsNegativeTimeout() {
        // Bounds validation is intentional input validation, so it joins the one
        // GetChatException hierarchy rather than throwing IllegalArgumentException.
        assertThrows(GetChatException.class, () -> RequestOptions.builder().timeout(-1).build());
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
                .options(RequestOptions.builder().retryDelay(0).build())
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
                .options(RequestOptions.builder().retryDelay(0).build())
                .build());
        assertEquals("ok", reuse.getChatInfo("chat-1").get("status").asString());
    }

    // ── Typed request builders (stage 3) ─────────────────────────────────────

    @Test
    @DisplayName("MessagesQuery drives the same GET as the raw-map form")
    void messagesQueryShapesRequest() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.getMessagesFromChat(
                "chat-1", MessagesQuery.builder().withUsers(true).deleted(false).edited(true).build(), 2, 20);

        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/chats/chat-1/messages?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=20"), lastPath);
        assertTrue(lastPath.contains("with_users=1"), lastPath);
        assertTrue(lastPath.contains("isDeleted=0"), lastPath);
        assertTrue(lastPath.contains("isEdited=1"), lastPath);
    }

    @Test
    @DisplayName("MessagesQuery and the raw map put identical bytes on the wire")
    void messagesQueryMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.getMessagesFromChat(
                "chat-1",
                MessagesQuery.builder().withUsers(true).deleted(false).extra("is_service", "1").build(),
                2,
                20);
        String typedPath = lastPath;

        sdk.getMessagesFromChat(
                "chat-1",
                Map.of("with_users", true, "isDeleted", false, "extra", Map.of("is_service", "1")),
                2,
                20);
        String mapPath = lastPath;

        assertEquals(mapPath, typedPath);
    }

    @Test
    @DisplayName("ChatsQuery drives the same GET as the raw-map form")
    void chatsQueryShapesRequest() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.getChats(ChatsQuery.builder().page(2).limit(10).type(Chat.Type.GROUP).owner("o1").build());

        assertEquals("GET", lastMethod);
        assertTrue(lastPath.startsWith("/api/v1/chats?"), lastPath);
        assertTrue(lastPath.contains("page=2"), lastPath);
        assertTrue(lastPath.contains("limit=10"), lastPath);
        assertTrue(lastPath.contains("type=group"), lastPath);
        assertTrue(lastPath.contains("owner=o1"), lastPath);
    }

    @Test
    @DisplayName("ChatsQuery and the raw map put identical bytes on the wire")
    void chatsQueryMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.getChats(ChatsQuery.builder()
                .page(2)
                .limit(10)
                .type(Chat.Type.GROUP)
                .owner("o1")
                .withOwners(true)
                .metadata(Map.of("plan", "pro"))
                .build());
        String typedPath = lastPath;

        sdk.getChats(Map.of(
                "page", 2, "limit", 10, "type", "group", "owner", "o1", "with_owners", true,
                "metadata", Map.of("plan", "pro")));
        String mapPath = lastPath;

        assertEquals(mapPath, typedPath);
    }

    @Test
    @DisplayName("ChatsQuery keeps the limit=1 default when limit is not set")
    void chatsQueryPreservesLimitWart() {
        respond(200, "{}");

        sdk().getChats(ChatsQuery.builder().type(Chat.Type.GROUP).build());

        assertTrue(lastPath.contains("limit=1"), lastPath);
    }

    @Test
    @DisplayName("updateChat(Chat) wraps in {chat:...} exactly like the raw map")
    void updateChatFromBuilderMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.updateChat("chat-1", Chat.builder().title("New").build());
        String typedBody = lastBody;

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1", lastPath);
        assertTrue(typedBody.contains("\"chat\""), typedBody);
        assertTrue(typedBody.contains("\"title\":\"New\""), typedBody);

        sdk.updateChat("chat-1", Map.<String, Object>of("title", "New"));
        assertEquals(lastBody, typedBody);
    }

    @Test
    @DisplayName("updateUser(User) wraps in {user:...} exactly like the raw map")
    void updateUserFromBuilderMatchesMap() {
        respond(200, "{}");
        GetChat sdk = sdk();

        sdk.updateUser("u1", User.builder().name("Bob").build());
        String typedBody = lastBody;

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/users/u1", lastPath);
        assertTrue(typedBody.contains("\"user\""), typedBody);
        assertTrue(typedBody.contains("\"name\":\"Bob\""), typedBody);

        sdk.updateUser("u1", Map.<String, Object>of("name", "Bob"));
        assertEquals(lastBody, typedBody);
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

        sdk.sendMessage(Chat.of("c1"), User.of("u1"), null, "hi", null, button);
        String typedBody = lastBody;
        assertTrue(typedBody.contains("\"buttons\""), typedBody);
        assertTrue(typedBody.contains("\"label\":\"Open\""), typedBody);

        sdk.sendMessage(Chat.of("c1"), User.of("u1"), null, "hi", null, List.of(button.asMap()));
        assertEquals(lastBody, typedBody);
    }
}
