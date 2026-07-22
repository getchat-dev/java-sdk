package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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

    private GetChatClient sdk() {
        return sdk(RequestOptions.builder().retryDelay(Duration.ZERO).build());
    }

    private GetChatClient sdk(RequestOptions options) {
        return GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(origin)
                .options(options)
                .build();
    }

    @Test
    @DisplayName("GET sends a bearer token and parses the JSON response into a typed model")
    void getSucceeds() {
        respond(200, "{\"status\":true,\"chat\":{\"id\":\"chat-1\",\"type\":\"group\",\"title\":\"Support\"}}");

        ChatDetails result = sdk().getChat("chat-1");

        // The envelope's {chat:...} is unwrapped and read through typed accessors.
        assertEquals("chat-1", result.id());
        assertEquals(Chat.Type.GROUP, result.type());
        assertEquals("Support", result.title());
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
    @DisplayName("ChatsQuery.createdFrom(LocalDateTime) puts the strict seconds-precision wire string on the query")
    void listChatsCreatedFromLocalDateTime() {
        respond(200, "{}");

        // nanos are dropped; the colons are percent-encoded (%3A) like any query value.
        sdk().listChats(ChatsQuery.builder()
                .limit(10)
                .createdFrom(LocalDateTime.of(2026, 1, 2, 12, 30, 45, 123_000_000))
                .build());

        assertEquals("/api/v1/chats?page=1&limit=10&created_from=2026-01-02T12%3A30%3A45", lastPath);

        // The String overload with the equivalent wire string produces the same path.
        sdk().listChats(ChatsQuery.builder().limit(10).createdFrom("2026-01-02T12:30:45").build());
        assertEquals("/api/v1/chats?page=1&limit=10&created_from=2026-01-02T12%3A30%3A45", lastPath);
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

        GetChatClient sdk = sdk(RequestOptions.builder().retries(0).build());

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
                writeResponse(exchange, 200, "{\"status\":true,\"chat\":{\"id\":\"chat-1\"}}");
            }
            return null;
        });

        ChatDetails result = sdk().getChat("chat-1");

        assertEquals("chat-1", result.id());
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
                writeResponse(exchange, 200, "{\"status\":true,\"message_ids\":[\"m-9\"]}");
            }
            return null;
        });

        SentMessages result =
                sdk().sendMessage(Chat.of("c1"), User.builder().id("u1").build(), "hi");

        assertEquals(List.of("m-9"), result.messageIds());
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

        GetChatClient sdk = sdk(RequestOptions.builder().timeout(Duration.ofMillis(150)).retries(0).build());

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

        sdk().sendTyping("chat-1", "u1", Duration.ofSeconds(10));

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
        GetChatClient sdk = sdk();

        assertThrows(GetChatException.class, () -> sdk.getChat(""));
        assertThrows(GetChatException.class, () -> sdk.sendMessage(Chat.of("c1"), User.of("u1"), ""));
        assertThrows(GetChatException.class, () -> sdk.addParticipants("c1", List.of()));

        assertEquals(0, requestCount.get());
    }

    @Test
    @DisplayName("sendMessage rejects a null or empty user before any request (user is required)")
    void sendMessageRejectsMissingUser() {
        respond(200, "{}");
        GetChatClient sdk = sdk();

        // openapi.yml chat.sendMessage marks `user` required; a null user used to
        // fall through and silently send an empty {} object.
        GetChatException nullUser = assertThrows(
                GetChatException.class, () -> sdk.sendMessage(Chat.of("c1"), (User) null, "hi"));
        assertEquals("user must be a non-empty object", nullUser.getMessage());

        // An empty user is rejected the same way, matching createUser's guard.
        GetChatException emptyUser = assertThrows(
                GetChatException.class, () -> sdk.sendMessage(Chat.of("c1"), User.builder().build(), "hi"));
        assertEquals("user must be a non-empty object", emptyUser.getMessage());

        assertEquals(0, requestCount.get(), "validation happens before any request is made");
    }

    @Test
    @DisplayName("an ApiRequest path/version with an illegal character throws GetChatException, not IllegalArgumentException")
    void malformedRequestUrlThrowsGetChatException() {
        respond(200, "{}");
        GetChatClient sdk = sdk();

        // A space in the path would make URI parsing throw a raw IllegalArgumentException;
        // assertThrows(GetChatException.class, ...) fails if that (non-GetChatException) leaks.
        GetChatException badPath = assertThrows(
                GetChatException.class, () -> sdk.requestApi(ApiRequest.get("bad path with spaces").build()));
        assertTrue(badPath.getMessage().startsWith("malformed request URL:"), badPath.getMessage());

        // An illegal version segment is covered the same way.
        assertThrows(
                GetChatException.class, () -> sdk.requestApi(ApiRequest.get("chats").version("v 1").build()));

        assertEquals(0, requestCount.get(), "the URL never parses, so nothing reaches the server");
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
        // Owns its client (no httpClient on the builder): closing twice is safe.
        GetChatClient owned = sdk();
        owned.close();
        owned.close();

        // A caller-supplied client is never torn down by the SDK; close() is a
        // safe no-op on it, so the client survives and stays usable.
        HttpClient shared = HttpClient.newHttpClient();
        GetChatClient borrowed = GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(origin)
                .httpClient(shared)
                .options(RequestOptions.builder().retryDelay(Duration.ZERO).build())
                .build();
        borrowed.close();
        borrowed.close();

        // Prove the shared client wasn't closed: a fresh instance reusing it can
        // still complete a request (meaningful even on JDK 21+, where an owned
        // client would really have been closed).
        respond(200, "{\"status\":true,\"chat\":{\"id\":\"chat-1\"}}");
        GetChatClient reuse = GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(origin)
                .httpClient(shared)
                .options(RequestOptions.builder().retryDelay(Duration.ZERO).build())
                .build();
        assertEquals("chat-1", reuse.getChat("chat-1").id());
    }

    // ── Typed request builders (stage 3) ─────────────────────────────────────

    @Test
    @DisplayName("MessagesQuery drives the same GET as the raw-map form")
    void messagesQueryShapesRequest() {
        respond(200, "{}");
        GetChatClient sdk = sdk();

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
        GetChatClient sdk = sdk();

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
        GetChatClient sdk = sdk();

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
        GetChatClient sdk = sdk();

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
    @DisplayName("ChatsQuery.withOwner(true) forwards with_owner=1 (the Map path used to drop it)")
    void chatsQueryForwardsWithOwner() {
        respond(200, "{}");

        sdk().listChats(ChatsQuery.builder().withOwner(true).build());

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/chats?page=1&limit=1&with_owner=1", lastPath);
    }

    @Test
    @DisplayName("the two owner flags keep a fixed order: with_owners then with_owner, before metadata")
    void chatsQueryOwnerFlagsKeepTheirOrder() {
        respond(200, "{}");

        sdk().listChats(ChatsQuery.builder()
                .page(2)
                .limit(10)
                .withOwners(true)
                .withOwner(true)
                .metadata(Map.of("plan", "pro"))
                .build());

        assertEquals("GET", lastMethod);
        assertEquals(
                "/api/v1/chats?page=2&limit=10&with_owners=1&with_owner=1&metadata%5Bplan%5D=pro",
                lastPath);
    }

    @Test
    @DisplayName("listChats with withOwner(true) embeds the owner, populating ChatDetails.owner()")
    void listChatsWithOwnerPopulatesOwner() {
        respond(200, """
                {"status":true,
                 "chats":{
                   "c-1":{"id":"c-1","type":"group","title":"First","owner_id":"o-1",
                          "owner":{"id":"o-1","name":"Olivia","email":"olivia@example.com"}}},
                 "chats_sort":["c-1"],
                 "meta":{"total":1,"output":1},
                 "pagination":{"items_per_page":50,"current":1,"total":1}}""");

        Page<ChatDetails> page = sdk().listChats(ChatsQuery.builder().limit(50).withOwner(true).build());

        // with_owner is what the singular embed rides on; owner() then reads it back.
        assertTrue(lastPath.contains("with_owner=1"), lastPath);
        assertEquals("o-1", page.items().get(0).owner().id());
        assertEquals("Olivia", page.items().get(0).owner().name());
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
    @DisplayName("UpdateMessageOptions returnResource sends Prefer: return=representation")
    void updateMessageReturnMessage() {
        respond(200, "{}");

        sdk().updateMessage("chat-1", "m-9", "edited", UpdateMessageOptions.builder()
                .returnResource(true)
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
        GetChatClient sdk = sdk();

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
        GetChatClient sdk = sdk();

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

    @Test
    @DisplayName("sendTyping with a whole-second Duration matches the old Integer wire bytes")
    void sendTypingDurationWholeSeconds() {
        respond(200, "{}");

        sdk().sendTyping("chat-1", "u1", Duration.ofSeconds(30));

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1/typing/u1?time=30", lastPath);
    }

    @Test
    @DisplayName("sendTyping rejects a Duration with a sub-second component (no silent truncation)")
    void sendTypingRejectsFractionalSeconds() {
        assertThrows(GetChatException.class,
                () -> sdk().sendTyping("chat-1", "u1", Duration.ofMillis(1500)));
        assertThrows(GetChatException.class,
                () -> sdk().sendTyping("chat-1", "u1", Duration.ofSeconds(10).plusNanos(1)));
        assertEquals(0, requestCount.get(), "validation fires before any request");
    }

    @Test
    @DisplayName("sendTyping rejects a Duration outside 1..60 seconds")
    void sendTypingRejectsOutOfRange() {
        assertThrows(GetChatException.class,
                () -> sdk().sendTyping("chat-1", "u1", Duration.ofSeconds(0)));
        assertThrows(GetChatException.class,
                () -> sdk().sendTyping("chat-1", "u1", Duration.ofSeconds(61)));
        assertEquals(0, requestCount.get(), "validation fires before any request");
    }

    @Test
    @DisplayName("sendTyping rejects a null Duration with a NullPointerException")
    void sendTypingRejectsNullDuration() {
        assertThrows(NullPointerException.class,
                () -> sdk().sendTyping("chat-1", "u1", (Duration) null));
    }

    @Test
    @DisplayName("apiUrl(URI) is equivalent to apiUrl(String)")
    void apiUrlUriOverload() {
        respond(200, "{}");

        GetChatClient sdk = GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(URI.create(origin))
                .options(RequestOptions.builder().retryDelay(Duration.ZERO).build())
                .build();
        sdk.sendTyping("chat-1", "u1", Duration.ofSeconds(10));

        // Identical to the path the String overload produces (see putWithQueryParams).
        assertEquals("/api/v1/chats/chat-1/typing/u1?time=10", lastPath);
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

    // ── Typed responses (stage 1: chats + messages) ──────────────────────────

    @Test
    @DisplayName("listChats unwraps the chats map into an ordered Page<ChatDetails>")
    void listChatsReturnsTypedPage() {
        respond(200, """
                {"status":true,
                 "chats":{
                   "c-1":{"id":"c-1","type":"group","title":"First",
                          "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00+00:00"},
                   "c-2":{"id":"c-2","type":null,"title":"Second",
                          "created_at":"2026-07-15T09:00:00+00:00","updated_at":"2026-07-15T09:00:00+00:00"}},
                 "chats_sort":["c-2","c-1"],
                 "meta":{"total":2,"output":2},
                 "pagination":{"items_per_page":50,"current":1,"total":1,"next_page_url":null,"prev_page_url":null}}""");

        Page<ChatDetails> page = sdk().listChats(ChatsQuery.builder().page(1).limit(50).build());

        // Order follows chats_sort, not the map's key order.
        assertEquals(2, page.items().size());
        assertEquals("c-2", page.items().get(0).id());
        assertEquals("c-1", page.items().get(1).id());
        assertNull(page.items().get(0).type(), "type null for a legacy chat");
        assertEquals(Chat.Type.GROUP, page.items().get(1).type());

        assertEquals(2, page.totalCount());
        assertEquals(1, page.currentPage());
        assertEquals(1, page.pageCount());
        assertEquals(50, page.itemsPerPage());
        assertNull(page.nextPageUrl());
    }

    @Test
    @DisplayName("listChats exposes an embedded owner on a chat element as a typed UserDetails")
    void listChatsExposesEmbeddedOwner() {
        respond(200, """
                {"status":true,
                 "chats":{
                   "c-1":{"id":"c-1","type":"group","title":"First","owner_id":"o-1",
                          "owner":{"id":"o-1","name":"Olivia","email":"olivia@example.com",
                                   "picture":{"kind":"auto","color":"#0af","initials":"OL"}}}},
                 "chats_sort":["c-1"],
                 "meta":{"total":1,"output":1},
                 "pagination":{"items_per_page":50,"current":1,"total":1}}""");

        Page<ChatDetails> page = sdk().listChats(ChatsQuery.builder().page(1).limit(50).withOwners(true).build());

        ChatDetails chat = page.items().get(0);
        assertNotNull(chat.owner(), "the embedded owner is exposed as a typed UserDetails");
        assertEquals("o-1", chat.owner().id());
        assertEquals("Olivia", chat.owner().name());
        // The owner's own avatar threads through as a typed Avatar.
        assertNotNull(chat.owner().picture());
        assertEquals("OL", chat.owner().picture().initials());
    }

    @Test
    @DisplayName("listMessages unwraps the messages map into an ordered Page<Message>")
    void listMessagesReturnsTypedPage() {
        respond(200, """
                {"status":true,
                 "messages":{
                   "m-1":{"id":"m-1","seq":1,"user_id":"u-1","text":"hello","created_at":1752664800,
                          "updated_at":null,"is_deleted":false,"is_edited":false,"versions":0,"extra":[]},
                   "m-2":{"id":"m-2","seq":2,"user_id":"u-2","text":null,"created_at":1752664900,
                          "updated_at":1752665000,"is_deleted":true,"is_edited":true,"versions":1,"extra":{"k":"v"}}},
                 "messages_sort":["m-1","m-2"],
                 "meta":{"total":2,"output":2},
                 "pagination":{"items_per_page":50,"current":1,"total":1}}""");

        Page<Message> page = sdk().listMessages("chat-1", MessagesQuery.builder().page(1).limit(50).build());

        assertEquals(2, page.items().size());
        Message first = page.items().get(0);
        assertEquals("m-1", first.id());
        assertEquals(1, first.seq());
        assertEquals("u-1", first.userId());
        assertEquals("hello", first.text());
        assertNotNull(first.createdAt(), "created_at is Unix seconds, parsed to an Instant");
        assertFalse(first.isDeleted());

        Message second = page.items().get(1);
        assertNull(second.text(), "text is null for a deleted message");
        assertTrue(second.isDeleted());
        assertEquals("v", second.extra().get("k").asString(""));
    }

    @Test
    @DisplayName("updateChat returns a ChatDetails view that is empty without a representation")
    void updateChatReturnsEmptyChatDetailsByDefault() {
        // No Prefer sent, so the backend echoes only a status: the typed view is
        // present but empty (accessors fall back to their documented defaults).
        respond(200, "{\"status\":true}");

        ChatDetails updated = sdk().updateChat("chat-1", Chat.builder().title("New").build());

        assertEquals("", updated.id());
        assertNull(updated.title());
        assertNull(lastPrefer, "no representation is requested by default");
    }

    @Test
    @DisplayName("createChat without options sends no Prefer header")
    void createChatDefaultSendsNoPrefer() {
        respond(201, "{\"status\":true}");

        sdk().createChat(Chat.builder().id("c1").title("Support").build());

        assertEquals("POST", lastMethod);
        assertNull(lastPrefer, "no representation is requested by default");
    }

    @Test
    @DisplayName("createChat with returnResource sends Prefer and exposes the populated chat")
    void createChatReturnChatSendsPreferAndPopulatesView() {
        // 201 with a full ChatResource, the shape the backend echoes for
        // Prefer: return=representation (openapi.yml chat.create).
        respond(201, """
                {"status":true,
                 "chat":{"id":"support-42","type":"group","title":"Support",
                         "created_at":"2026-07-21T12:00:00+00:00","updated_at":"2026-07-21T12:00:00+00:00"}}""");

        ChatDetails created = sdk().createChat(
                Chat.builder().id("support-42").title("Support").type(Chat.Type.GROUP).build(),
                List.of(Recipient.of("u-1", "Alice")),
                CreateChatOptions.builder().returnResource(true).build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/chats", lastPath);
        assertEquals("return=representation", lastPrefer);
        // The echoed chat is unwrapped and read through typed accessors.
        assertEquals("support-42", created.id());
        assertEquals("Support", created.title());
        assertEquals(Chat.Type.GROUP, created.type());
        assertNotNull(created.createdAt());
    }

    @Test
    @DisplayName("updateChat with returnResource sends Prefer and exposes the populated chat")
    void updateChatReturnChatSendsPreferAndPopulatesView() {
        respond(200, """
                {"status":true,
                 "chat":{"id":"chat-1","type":"group","title":"Renamed",
                         "created_at":"2026-07-21T12:00:00+00:00","updated_at":"2026-07-21T12:30:00+00:00"}}""");

        ChatDetails updated = sdk().updateChat("chat-1",
                Chat.builder().title("Renamed").build(),
                UpdateChatOptions.builder().returnResource(true).build());

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/chats/chat-1", lastPath);
        assertEquals("return=representation", lastPrefer);
        // The request body is unchanged by the opt-in — only the header is added.
        assertEquals("{\"chat\":{\"title\":\"Renamed\"}}", lastBody);
        assertEquals("chat-1", updated.id());
        assertEquals("Renamed", updated.title());
        assertEquals(Chat.Type.GROUP, updated.type());
        assertNotNull(updated.updatedAt());
    }

    @Test
    @DisplayName("updateMessage with returnResource exposes the echoed message")
    void updateMessageReturnsMessageWhenRequested() {
        respond(200, """
                {"status":true,"is_updated":true,
                 "message":{"id":"m-9","seq":7,"user_id":"u-1","text":"edited","created_at":1752664800,
                            "updated_at":1752665000,"is_deleted":false,"is_edited":true,"versions":1,"extra":[]}}""");

        UpdatedMessage result = sdk().updateMessage("chat-1", "m-9", "edited",
                UpdateMessageOptions.builder().returnResource(true).build());

        assertTrue(result.isUpdated());
        assertNotNull(result.message());
        assertEquals("m-9", result.message().id());
        assertTrue(result.message().isEdited());
    }

    @Test
    @DisplayName("updateMessage without a representation reports is_updated but no message")
    void updateMessageWithoutRepresentation() {
        respond(200, "{\"status\":true,\"is_updated\":true}");

        UpdatedMessage result = sdk().updateMessage("chat-1", "m-9", "edited");

        assertTrue(result.isUpdated());
        assertNull(result.message());
    }

    @Test
    @DisplayName("deleteChat, deleteMessage and sendTyping return the status flag")
    void statusOnlyMethodsReturnBoolean() {
        // A single handler serves all three: each reads only the top-level status.
        respond(200, "{\"status\":true,\"is_updated\":true}");
        GetChatClient sdk = sdk();

        assertTrue(sdk.deleteChat("chat-1"));
        assertTrue(sdk.deleteMessage("chat-1", "m-1"));
        assertTrue(sdk.sendTyping("chat-1", "u-1", Duration.ofSeconds(5)));
    }

    // ── Typed responses (stage 2: users + participants) ──────────────────────

    @Test
    @DisplayName("getUser unwraps the user object into a typed UserDetails")
    void getUserReturnsTypedUser() {
        respond(200, """
                {"status":true,
                 "user":{"id":"u-1","name":"Alice","email":"alice@example.com","link":"https://x/alice",
                         "picture":"https://cdn/alice.png",
                         "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00Z",
                         "metadata":{"plan":"pro"}}}""");

        UserDetails user = sdk().getUser("u-1");

        assertEquals("GET", lastMethod);
        assertEquals("/api/v1/users/u-1", lastPath);
        assertEquals("u-1", user.id());
        assertEquals("Alice", user.name());
        assertEquals("alice@example.com", user.email());
        assertNotNull(user.createdAt());
        assertEquals("pro", user.metadata().get("plan"));
    }

    @Test
    @DisplayName("createUser without options sends no Prefer and returns an empty view")
    void createUserDefaultSendsNoPrefer() {
        respond(201, "{\"status\":true}");

        UserDetails user = sdk().createUser(User.builder().id("u-3").name("Carol").build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/users", lastPath);
        assertNull(lastPrefer, "no representation is requested by default");
        // The payload is wrapped as {"user": ...}, matching openapi.yml and the node SDK.
        assertEquals("{\"user\":{\"id\":\"u-3\",\"name\":\"Carol\"}}", lastBody);
        // No representation echoed: the typed view is present but empty.
        assertEquals("", user.id());
    }

    @Test
    @DisplayName("createUser with returnResource sends Prefer and exposes the populated user")
    void createUserReturnResourcePopulatesView() {
        respond(201, """
                {"status":true,
                 "user":{"id":"u-3","name":"Carol",
                         "created_at":"2026-07-21T12:00:00+00:00","updated_at":"2026-07-21T12:00:00+00:00"}}""");

        UserDetails user = sdk().createUser(
                User.builder().id("u-3").name("Carol").build(),
                CreateUserOptions.builder().returnResource(true).build());

        assertEquals("POST", lastMethod);
        assertEquals("/api/v1/users", lastPath);
        assertEquals("return=representation", lastPrefer);
        // The opt-in adds only the header — the {"user": ...} body bytes are unchanged.
        assertEquals("{\"user\":{\"id\":\"u-3\",\"name\":\"Carol\"}}", lastBody);
        assertEquals("u-3", user.id());
        assertEquals("Carol", user.name());
        assertNotNull(user.createdAt());
    }

    @Test
    @DisplayName("updateUser with returnResource sends Prefer, keeps the {user:...} body, populates the view")
    void updateUserReturnResourcePopulatesView() {
        respond(200, """
                {"status":true,
                 "user":{"id":"u-3","name":"Caroline",
                         "created_at":"2026-07-21T12:00:00+00:00","updated_at":"2026-07-21T12:30:00+00:00"}}""");

        UserDetails user = sdk().updateUser("u-3",
                User.builder().name("Caroline").build(),
                UpdateUserOptions.builder().returnResource(true).build());

        assertEquals("PUT", lastMethod);
        assertEquals("/api/v1/users/u-3", lastPath);
        assertEquals("return=representation", lastPrefer);
        // The opt-in adds only the header — the body bytes are unchanged.
        assertEquals("{\"user\":{\"name\":\"Caroline\"}}", lastBody);
        assertEquals("Caroline", user.name());
        assertNotNull(user.updatedAt());
    }

    @Test
    @DisplayName("updateUser without options sends no Prefer and returns an empty view")
    void updateUserDefaultSendsNoPrefer() {
        respond(200, "{\"status\":true}");

        UserDetails user = sdk().updateUser("u-3", User.builder().name("Bob").build());

        assertNull(lastPrefer, "no representation is requested by default");
        assertEquals("", user.id());
    }

    @Test
    @DisplayName("deleteUser, addParticipants and removeParticipant return the status flag")
    void userParticipantStatusMethodsReturnBoolean() {
        respond(200, "{\"status\":true}");
        GetChatClient sdk = sdk();

        assertTrue(sdk.deleteUser("u-1"));
        assertTrue(sdk.addParticipants("chat-1", List.of(Recipient.of("u-2", "Bob"))));
        assertTrue(sdk.removeParticipant("chat-1", "u-2"));
    }

    @Test
    @DisplayName("listParticipants unwraps the participants array into a typed Page<Participant>")
    void listParticipantsReturnsTypedPage() {
        respond(200, """
                {"status":true,
                 "participants":[
                   {"id":"u-2","name":"Bob","email":"bob@example.com",
                    "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00+00:00"},
                   {"id":"u-1","name":"Alice",
                    "created_at":"2026-07-15T09:00:00+00:00","updated_at":"2026-07-15T09:00:00+00:00"}],
                 "meta":{"total":2,"output":0},
                 "pagination":{"items_per_page":50,"current":1,"total":1}}""");

        Page<Participant> page = sdk().listParticipants("chat-1", PageQuery.builder().page(1).limit(50).build());

        assertEquals(2, page.items().size());
        // Server order is preserved (the array is not re-sorted).
        assertEquals("u-2", page.items().get(0).id());
        assertEquals("Bob", page.items().get(0).name());
        assertEquals("bob@example.com", page.items().get(0).email());
        assertEquals("u-1", page.items().get(1).id());
        assertEquals(2, page.totalCount());
        assertEquals(1, page.currentPage());
        assertNull(page.nextPageUrl(), "participant list omits page urls");
    }

    @Test
    @DisplayName("listUserChats unwraps the chats array into a typed Page<ChatDetails>")
    void listUserChatsReturnsTypedPage() {
        respond(200, """
                {"status":true,
                 "chats":[
                   {"id":"c-1","type":"group","title":"First",
                    "created_at":"2026-07-16T12:00:00+00:00","updated_at":"2026-07-16T12:30:00+00:00"},
                   {"id":"c-2","type":null,"title":"Second",
                    "created_at":"2026-07-15T09:00:00+00:00","updated_at":"2026-07-15T09:00:00+00:00"}],
                 "meta":{"total":2,"output":2},
                 "pagination":{"items_per_page":50,"current":1,"total":1,
                               "next_page_url":"http://x/next","prev_page_url":null}}""");

        Page<ChatDetails> page = sdk().listUserChats("u-1", PageQuery.builder().page(1).limit(50).build());

        assertEquals(2, page.items().size());
        assertEquals("c-1", page.items().get(0).id());
        assertEquals(Chat.Type.GROUP, page.items().get(0).type());
        assertNull(page.items().get(1).type(), "type null for a legacy chat");
        assertEquals(2, page.totalCount());
        assertEquals("http://x/next", page.nextPageUrl());
        assertNull(page.prevPageUrl());
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
        GetChatClient sdk = sdk();

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
        GetChatClient sdk = sdk();

        sdk.updateUser("u1", User.builder().name("Bob").build());
        String wrappedPath = lastPath;
        String wrappedBody = lastBody;

        sdk.requestApi(ApiRequest.put("users/u1")
                .body(Map.of("user", Map.of("name", "Bob")))
                .build());

        assertEquals(wrappedPath, lastPath);
        assertEquals(wrappedBody, lastBody);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exception hierarchy
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every SDK exception subtype is caught by the GetChatException root")
    void rootCatchesEverySubtype() {
        // The refinement keeps GetChatException as the single root, so one catch
        // still covers all of them.
        assertTrue(GetChatException.class.isAssignableFrom(GetChatApiException.class));
        assertTrue(GetChatException.class.isAssignableFrom(GetChatTransportException.class));
        assertTrue(GetChatException.class.isAssignableFrom(GetChatTimeoutException.class));
        assertTrue(GetChatException.class.isAssignableFrom(GetChatSerializationException.class));
        assertTrue(GetChatException.class.isAssignableFrom(GetChatInterruptedException.class));

        // A real transport failure is still catchable through the root.
        GetChatClient sdk = GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(closedOrigin())
                .options(RequestOptions.builder().retries(0).retryDelay(Duration.ZERO).build())
                .build();
        try {
            sdk.getChat("chat-1");
            org.junit.jupiter.api.Assertions.fail("expected a transport failure");
        } catch (GetChatException e) {
            assertInstanceOf(GetChatTransportException.class, e);
        }
    }

    @Test
    @DisplayName("GetChatTimeoutException is a GetChatTransportException")
    void timeoutIsATransportException() {
        // Structural (the retarget) plus a live throw to prove the runtime type.
        assertTrue(GetChatTransportException.class.isAssignableFrom(GetChatTimeoutException.class));

        handle(exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writeResponse(exchange, 200, "{}");
            return null;
        });

        GetChatClient sdk = sdk(RequestOptions.builder().timeout(Duration.ofMillis(150)).retries(0).build());

        GetChatTransportException error =
                assertThrows(GetChatTransportException.class, () -> sdk.getChat("chat-1"));
        assertInstanceOf(GetChatTimeoutException.class, error);
    }

    @Test
    @DisplayName("a refused connection throws GetChatTransportException")
    void connectionRefusedThrowsTransport() {
        GetChatClient sdk = GetChatClient.builder()
                .apiToken("test-token")
                .apiUrl(closedOrigin())
                .options(RequestOptions.builder().retries(0).retryDelay(Duration.ZERO).build())
                .build();

        assertThrows(GetChatTransportException.class, () -> sdk.getChat("chat-1"));
    }

    @Test
    @DisplayName("GetChatApiException carries the request method and URI")
    void apiExceptionCarriesMethodAndUri() {
        respond(422, "{\"message\":\"validation failed\"}");

        GetChatClient sdk = sdk(RequestOptions.builder().retries(0).build());

        GetChatApiException error = assertThrows(GetChatApiException.class, () -> sdk.getChat("chat-1"));

        assertEquals("GET", error.method());
        assertEquals(origin + "/api/v1/chats/chat-1", error.uri().toString());
    }

    @Test
    @DisplayName("GetChatApiException requestId() returns the X-Request-Id header value")
    void apiExceptionCarriesRequestId() {
        handle(exchange -> {
            exchange.getResponseHeaders().add("X-Request-Id", "req-abc-123");
            writeResponse(exchange, 422, "{\"message\":\"nope\"}");
            return null;
        });

        GetChatClient sdk = sdk(RequestOptions.builder().retries(0).build());

        GetChatApiException error = assertThrows(GetChatApiException.class, () -> sdk.getChat("chat-1"));

        assertEquals("req-abc-123", error.requestId());
    }

    @Test
    @DisplayName("GetChatApiException requestId() is null when the server sends no header")
    void apiExceptionRequestIdNullWhenAbsent() {
        respond(422, "{\"message\":\"nope\"}");

        GetChatClient sdk = sdk(RequestOptions.builder().retries(0).build());

        GetChatApiException error = assertThrows(GetChatApiException.class, () -> sdk.getChat("chat-1"));

        assertNull(error.requestId());
    }

    @Test
    @DisplayName("an unparseable JSON success body throws GetChatSerializationException")
    void unparseableJsonThrowsSerialization() {
        // 200 with a JSON content-type but a body that is not valid JSON: the
        // exchange completed, but the response cannot be parsed.
        respond(200, "{ this is not json ");

        GetChatClient sdk = sdk(RequestOptions.builder().retries(0).build());

        assertThrows(GetChatSerializationException.class, () -> sdk.getChat("chat-1"));
    }

    /** An origin whose port is closed, so a connection to it is refused. */
    private static String closedOrigin() {
        try (ServerSocket probe = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + probe.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
