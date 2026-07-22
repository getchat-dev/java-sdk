package dev.getchat.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.getchat.sdk.internal.Helpers;
import dev.getchat.sdk.internal.Retry;
import dev.getchat.sdk.internal.Signing;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The GetChat REST API client, authenticated with a Bearer {@code apiToken}.
 *
 * <p>This is one of the SDK's two entry points; {@link GetChatUrlSigner} is the
 * other. The node SDK folds signing and REST into a single {@code Emby} class;
 * this SDK keeps them apart so a half-configured client cannot exist. The
 * {@link Builder} checks at {@link Builder#build()} that both the API URL and the
 * API token are present, so a call can never fire without a destination or
 * without authentication.
 *
 * <pre>{@code
 * GetChatClient client = GetChatClient.builder()
 *         .apiUrl("https://chat.example.com")
 *         .apiToken("api-token")
 *         .build();
 *
 * ChatDetails chat = client.getChat("support-42");
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads; create one and
 * share it for the life of the application. When the SDK created the underlying
 * {@link HttpClient} itself (no {@code httpClient} on the builder), a short-lived
 * instance can release it via {@link #close()} or try-with-resources; a client
 * you passed in is never closed for you. See {@link #close()} for the
 * JDK-version caveat.
 */
public final class GetChatClient implements AutoCloseable {

    /** HTTP verbs the API uses. */
    public enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE;

        String wire() {
            return name();
        }

        boolean hasBody() {
            return this == POST || this == PUT;
        }
    }

    // Package-private so ApiRequest can share the single source of truth for the
    // default API version segment.
    static final String DEFAULT_VERSION = "v1";

    private final String apiToken;
    private final String apiUrl;
    private final RequestOptions requestOptions;
    private final HttpClient httpClient;
    // True only when the SDK built the HttpClient itself; a caller-supplied
    // client stays the caller's to close, so close() must leave it alone.
    private final boolean ownsHttpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private GetChatClient(Builder b) {
        this.apiToken = b.apiToken;
        this.apiUrl = b.apiUrl;
        this.requestOptions = b.options != null ? b.options : RequestOptions.defaults();
        this.ownsHttpClient = b.httpClient == null;
        this.httpClient = ownsHttpClient
                ? HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                : b.httpClient;
    }

    /** Start building a {@link GetChatClient}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Release the HTTP client this instance owns.
     *
     * <p>Only closes the client when the SDK created it (no {@code httpClient} on
     * the builder). A client you supplied is left open — its lifecycle is yours.
     *
     * <p>{@link HttpClient} only became {@link AutoCloseable} in JDK 21, so on
     * JDK 17–20 this is a no-op and the client's resources are reclaimed by the
     * garbage collector instead; the SDK targets Java 17 and, where the runtime
     * supports it, closes the client through the {@link AutoCloseable} interface
     * (an {@code instanceof} check plus an interface call, not reflection).
     *
     * <p>After closing an instance that owns its client, that instance must not be
     * used again — its requests would fail. Calling {@code close()} more than once
     * is safe (idempotent) and never throws a checked exception. Most callers hold
     * one long-lived instance and never call this; it exists for short-lived
     * clients used from try-with-resources.
     */
    @Override
    public void close() {
        if (ownsHttpClient && httpClient instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                // close() must stay quiet: an instance is being torn down and
                // there is nothing useful a caller can do with a failure here.
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REST transport
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The public REST entry point and escape hatch for endpoints the SDK does not
     * wrap yet. Describe the call as an {@link ApiRequest}; retry, timeout and
     * error shaping are shared with every high-level method.
     *
     * <pre>{@code
     * JsonValue hooks = client.requestApi(ApiRequest.get("chats/support-42/webhooks")
     *         .query("with_disabled", 1)
     *         .build());
     * }</pre>
     *
     * @param request the call to make; must not be {@code null}
     * @return the response as a {@link JsonValue}; a JSON null for an empty body
     * @throws GetChatApiException on a non-2xx/3xx response
     * @throws GetChatTimeoutException when an attempt exceeds its timeout
     */
    public JsonValue requestApi(ApiRequest request) {
        java.util.Objects.requireNonNull(request, "request is required");
        return execute(request);
    }

    /**
     * The single internal HTTP entry point. Every high-level method funnels through
     * here as an {@link ApiRequest}, so retry, timeout and error shaping live in one
     * place. The public surface is {@link #requestApi(ApiRequest)}.
     *
     * <p>The request's fields map onto the wire exactly as the public path defines:
     * for GET/DELETE the {@code query()} is the sole source of URL params (it feeds
     * the query string); for POST/PUT the {@code body()} is the JSON payload and
     * {@code query()} the URL query params. Headers merge on top of the defaults and
     * {@code control()} carries per-call timeout/retry overrides ({@code null} uses
     * the instance defaults).
     *
     * @param apiRequest the fully-built call to make
     * @return the response as a {@link JsonValue}; a JSON null for an empty body
     * @throws GetChatApiException on a non-2xx/3xx response
     * @throws GetChatTimeoutException when an attempt exceeds its timeout
     */
    private JsonValue execute(ApiRequest apiRequest) {
        HttpMethod type = apiRequest.method();
        String method = apiRequest.path();
        String version = apiRequest.version();
        Map<String, String> headers = apiRequest.headers();
        RequestControl control = apiRequest.control();

        // For GET/DELETE the query is the sole source of URL params (mapped onto the
        // `params` channel); for POST/PUT the body is the JSON payload and the query
        // rides the URL.
        Map<String, Object> params = type.hasBody() ? apiRequest.body() : apiRequest.query();
        Map<String, Object> query = type.hasBody() ? apiRequest.query() : null;

        Map<String, Object> safeParams = params == null ? Map.of() : params;
        RequestOptions opts = control == null ? requestOptions : control.applyTo(requestOptions);

        StringBuilder url = new StringBuilder(apiUrl + "/api/" + version + "/" + method);

        String queryString;
        if (type.hasBody()) {
            queryString = query == null || query.isEmpty()
                    ? ""
                    : dev.getchat.sdk.internal.QueryString.stringify(
                            dev.getchat.sdk.internal.QueryString.flatten(query));
        } else {
            Map<String, Object> merged = new LinkedHashMap<>(safeParams);
            if (query != null) {
                merged.putAll(query);
            }
            queryString = dev.getchat.sdk.internal.QueryString.stringify(
                    dev.getchat.sdk.internal.QueryString.flatten(merged));
        }
        if (!queryString.isEmpty()) {
            url.append('?').append(queryString);
        }

        String body = type.hasBody() ? writeJson(safeParams) : null;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiToken);

        if (headers != null) {
            headers.forEach(builder::header);
        }
        if (!opts.timeout().isZero()) {
            builder.timeout(opts.timeout());
        }
        builder.method(
                type.wire(),
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

        HttpRequest request = builder.build();

        // The retry loop works in Jackson terms; wrap once at the public boundary
        // so no JsonNode escapes into the SDK's public surface.
        return JsonValue.wrap(runWithRetry(request, type, method, opts));
    }

    private JsonNode runWithRetry(HttpRequest request, HttpMethod type, String method, RequestOptions opts) {
        int total = Math.max(1, opts.retries() + 1);
        RuntimeException lastError = null;

        for (int attempt = 0; attempt < total; attempt++) {
            Integer status = null;
            boolean transportError = false;
            boolean preSend = false;
            Long retryAfterMs = null;

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();

                if (code >= 200 && code < 400) {
                    return parseBody(response);
                }

                String raw = response.body();
                JsonNode parsed = isJson(response) ? tryParse(raw) : null;
                lastError = new GetChatApiException(code, raw, JsonValue.wrap(parsed));
                status = code;
                retryAfterMs = response.headers().firstValue("retry-after").map(Retry::parseRetryAfter).orElse(null);

            } catch (HttpTimeoutException e) {
                lastError = new GetChatTimeoutException(opts.timeout(), method, e);
                transportError = true;
            } catch (ConnectException | UnknownHostException e) {
                // The request provably never reached the server, so even a write
                // is safe to replay.
                lastError = new GetChatException("request to '" + method + "' failed: " + e.getMessage(), e);
                transportError = true;
                preSend = true;
            } catch (IOException e) {
                lastError = new GetChatException("request to '" + method + "' failed: " + e.getMessage(), e);
                transportError = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GetChatException("request to '" + method + "' was interrupted", e);
            }

            boolean isLast = attempt + 1 >= total;
            if (isLast || !Retry.shouldRetry(type.wire(), status, transportError, preSend)) {
                break;
            }

            sleep(Retry.backoffDelay(attempt, opts.retryDelayMillis(), retryAfterMs));
        }

        throw lastError;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GetChatException("interrupted while waiting to retry", e);
        }
    }

    private static boolean isJson(HttpResponse<String> response) {
        Optional<String> contentType = response.headers().firstValue("content-type");
        return contentType.map(v -> v.toLowerCase(Locale.ROOT).startsWith("application/json")).orElse(false);
    }

    private JsonNode parseBody(HttpResponse<String> response) {
        String raw = response.body();
        if (raw == null || raw.isEmpty()) {
            return mapper.nullNode();
        }
        if (!isJson(response)) {
            return mapper.getNodeFactory().textNode(raw);
        }
        try {
            return mapper.readTree(raw);
        } catch (IOException e) {
            throw new GetChatException("failed to parse JSON response: " + e.getMessage(), e);
        }
    }

    private JsonNode tryParse(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (IOException e) {
            return null;
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new GetChatException("failed to serialise request body: " + e.getMessage(), e);
        }
    }

    /**
     * Percent-encode a value being substituted into a URL path.
     *
     * <p>Unlike the node SDK — which escapes the query and then runs
     * {@code encodeURI()} over the whole URL — this SDK encodes path params at
     * substitution time and leaves the assembled URL alone. See CLAUDE.md
     * ("Known divergences from the node SDK").
     */
    private static String pathParam(String value) {
        return dev.getchat.sdk.internal.QueryString.escape(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chats
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The internal {@code Map} implementation behind {@link #listChats(ChatsQuery)}.
     *
     * <p>Accepted keys: {@code type, owner, created_from, created_to,
     * last_message_from, last_message_to} (strings), {@code with_owner} and
     * {@code with_owners} (lenient booleans, sent as 0/1), {@code metadata} (map),
     * plus {@code page} and {@code limit}. The typed {@link ChatsQuery} builder is
     * the public front end; use its {@code set(key, value)} for a key without a
     * typed setter.
     *
     * <p>Note {@code limit} defaults to 1, not to a page size — this mirrors the
     * node SDK, whose callers always pass one explicitly.
     */
    private JsonValue listChats(@Nullable Map<String, Object> queryParams) {
        return listChats(queryParams, null);
    }

    /** {@link #listChats(ChatsQuery)} with per-call transport overrides, {@code Map} form. */
    private JsonValue listChats(@Nullable Map<String, Object> queryParams, @Nullable RequestControl control) {
        Map<String, Object> params = queryParams == null ? Map.of() : queryParams;
        Map<String, Object> query = new LinkedHashMap<>();

        query.put("page", Math.max(intOrDefault(params.get("page"), 1), 1));
        query.put("limit", Math.min(intOrDefault(params.get("limit"), 1), 1000));

        for (String key : List.of("type", "owner", "created_from", "created_to", "last_message_from",
                "last_message_to")) {
            Object raw = params.get(key);
            if (raw instanceof String s && !s.isEmpty()) {
                query.put(key, s);
            }
        }

        // Both owner flags share one lenient-boolean → 0/1 coercion. with_owners
        // stays in its original slot so the default wire bytes are unchanged;
        // with_owner slots in right after it (it was silently dropped before, so
        // the ChatsQuery.set("with_owner", …) escape hatch never reached the wire).
        putOwnerFlag(query, "with_owners", params.get("with_owners"));
        putOwnerFlag(query, "with_owner", params.get("with_owner"));

        if (Helpers.isFilledPlainObject(params.get("metadata"))) {
            query.put("metadata", params.get("metadata"));
        }

        return execute(ApiRequest.get("chats").query(query).control(control).build());
    }

    /**
     * List chats with typed filters.
     *
     * <p>Accepts the filters {@link ChatsQuery} carries — {@code type},
     * {@code owner}, the four date-range strings, {@code with_owner} and
     * {@code with_owners} (sent as 0/1), {@code metadata}, plus {@code page} and
     * {@code limit}. {@code with_owner} embeds each chat's owner and so populates
     * {@link ChatDetails#owner()}; {@code with_owners} fills a separate {@code users}
     * side-map instead. Reach a key without a typed setter through
     * {@link ChatsQuery.Builder#set(String, Object)}.
     * Note {@code limit} defaults to 1 when it is not set — a wart kept for parity
     * with the node SDK; pass a limit explicitly.
     */
    public Page<ChatDetails> listChats(@Nullable ChatsQuery query) {
        return chatsPage(listChats(query == null ? Map.of() : query.asMap()));
    }

    /** {@link #listChats(ChatsQuery)} with per-call transport overrides. */
    public Page<ChatDetails> listChats(@Nullable ChatsQuery query, @Nullable RequestControl control) {
        return chatsPage(listChats(query == null ? Map.of() : query.asMap(), control));
    }

    /** Fetch a single chat. */
    public ChatDetails getChat(String chatId) {
        requireChatId(chatId);
        return ChatDetails.of(execute(ApiRequest.get("chats/" + pathParam(chatId)).build()).get("chat"));
    }

    /**
     * Create a chat, optionally asking the backend to echo it back.
     *
     * <p>The backend requires {@code participants} for a private chat.
     *
     * <p>The returned {@link ChatDetails} unwraps the response {@code chat} object,
     * which the backend includes only when the caller asks for a representation
     * ({@code Prefer: return=representation}). Set {@code returnResource(true)} on the
     * options to request it and get a populated view back; without it (or with
     * {@code null} options) the SDK sends no such header and the returned view is
     * empty (its accessors fall back to defaults) — call {@link #getChat(String)}
     * to read the created chat back, or {@link ChatDetails#raw()} to inspect what
     * was returned.
     *
     * @param chat         the chat to create; must be a non-empty object
     * @param participants optional participants (required for a private chat)
     * @param options      create flags such as {@code returnResource}; {@code null}
     *                     applies the defaults (no representation requested)
     */
    public ChatDetails createChat(
            Chat chat, @Nullable List<Recipient> participants, @Nullable CreateChatOptions options) {
        if (chat == null || chat.asMap().isEmpty()) {
            throw new GetChatException("chat must be a non-empty object");
        }
        CreateChatOptions opts = options == null ? CreateChatOptions.builder().build() : options;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat", chat.asMap());
        if (participants != null && !participants.isEmpty()) {
            body.put("participants", normalizeParticipants(participants));
        }
        Map<String, String> headers = opts.returnResource() ? Map.of("Prefer", "return=representation") : null;
        return ChatDetails.of(execute(ApiRequest.post("chats").body(body).headers(headers).build()).get("chat"));
    }

    /** Create a chat with participants and no representation requested. */
    public ChatDetails createChat(Chat chat, @Nullable List<Recipient> participants) {
        return createChat(chat, participants, null);
    }

    /** Create a chat with no seeded participants and no representation requested. */
    public ChatDetails createChat(Chat chat) {
        return createChat(chat, null, null);
    }

    /** The internal {@code Map} implementation behind {@link #updateChat(String, Chat, UpdateChatOptions)}. */
    private JsonValue updateChat(
            String chatId, @Nullable Map<String, Object> updates, @Nullable Map<String, String> headers) {
        requireChatId(chatId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat", updates == null ? Map.of() : updates);
        return execute(ApiRequest.put("chats/" + pathParam(chatId)).body(body).headers(headers).build());
    }

    /**
     * Update mutable chat fields from a {@link Chat} builder, optionally asking the
     * backend to echo the chat back.
     *
     * <p>The update endpoint ({@code chat.update}) accepts only {@code id},
     * {@code title} and {@code metadata}. Any other field a {@code Chat} can
     * carry — {@code create}, {@code type} — is not part of the update contract;
     * this SDK does not filter them (the backend is the source of truth), so send
     * only the fields you intend to change.
     *
     * <p>Like {@link #createChat}, the returned {@link ChatDetails} unwraps the
     * response {@code chat} object, which the backend includes only for a requested
     * representation. Set {@code returnResource(true)} on the options to get a populated
     * view back; without it (or with {@code null} options) the SDK sends no such
     * preference and the returned view is empty — use {@link #getChat(String)} to
     * read the chat back.
     *
     * @param chatId  the chat to update
     * @param changes the fields to change; {@code null} sends an empty update
     * @param options update flags such as {@code returnResource}; {@code null} applies
     *                the defaults (no representation requested)
     */
    public ChatDetails updateChat(String chatId, @Nullable Chat changes, @Nullable UpdateChatOptions options) {
        UpdateChatOptions opts = options == null ? UpdateChatOptions.builder().build() : options;
        Map<String, String> headers = opts.returnResource() ? Map.of("Prefer", "return=representation") : null;
        return ChatDetails.of(
                updateChat(chatId, changes == null ? null : changes.asMap(), headers).get("chat"));
    }

    /** Update mutable chat fields with no representation requested. */
    public ChatDetails updateChat(String chatId, @Nullable Chat changes) {
        return updateChat(chatId, changes, null);
    }

    /**
     * Delete a chat permanently. Returns the response {@code status} flag (always
     * {@code true} on a successful call — a non-2xx response throws instead). The
     * backend dispatches the actual removal as background jobs, so the chat may
     * disappear shortly after this returns.
     */
    public boolean deleteChat(String chatId) {
        requireChatId(chatId);
        return statusOf(execute(ApiRequest.delete("chats/" + pathParam(chatId)).build()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Messages
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The internal {@code Map} implementation behind
     * {@link #listMessages(String, MessagesQuery)}.
     *
     * <p>Accepted keys in {@code queryParams}: {@code extra} (map of scalars),
     * {@code isDeleted}, {@code isEdited}, {@code with_users} — all lenient
     * booleans sent as 0/1. {@code page}/{@code limit} arrive as separate
     * arguments (pulled from the query by the public wrapper) and are clamped here.
     */
    private JsonValue listMessages(
            String chatId, @Nullable Map<String, Object> queryParams, int page, int limit) {
        requireChatId(chatId);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", Math.max(page, 1));
        query.put("limit", Math.min(limit, 1000));

        if (queryParams != null && !queryParams.isEmpty()) {
            Object extra = queryParams.get("extra");
            if (Helpers.isFilledPlainObject(extra)) {
                Map<String, String> extraParams = new LinkedHashMap<>();
                ((Map<?, ?>) extra).forEach((k, v) -> {
                    if (Helpers.isScalar(v)) {
                        extraParams.put(
                                String.valueOf(k),
                                Helpers.isBoolean(v, true) ? String.valueOf(Helpers.isTRUE(v)) : Helpers.jsString(v));
                    }
                });
                if (!extraParams.isEmpty()) {
                    query.put("extra", extraParams);
                }
            }

            putSmartFlag(query, "isDeleted", queryParams.get("isDeleted"));
            putSmartFlag(query, "isEdited", queryParams.get("isEdited"));

            Object withUsers = queryParams.containsKey("with_users")
                    ? queryParams.get("with_users")
                    : queryParams.get("withUsers");
            putSmartFlag(query, "with_users", withUsers);
        }

        return execute(ApiRequest.get("chats/" + pathParam(chatId) + "/messages").query(query).build());
    }

    /** Read the first page (up to 50) of messages. */
    public Page<Message> listMessages(String chatId) {
        return messagesPage(listMessages(chatId, Map.of(), 1, 50));
    }

    /**
     * Read messages from a chat with typed filters and pagination.
     *
     * <p>Accepts the filters {@link MessagesQuery} carries — {@code extra},
     * {@code isDeleted}, {@code isEdited}, {@code with_users} — plus its
     * {@code page}/{@code limit}, and reaches a key without a typed setter through
     * {@link MessagesQuery.Builder#set(String, Object)}. Pagination lives inside
     * the query: an unset {@code page} defaults to 1 and an unset {@code limit} to
     * 50 (the endpoint then clamps {@code page} up to 1 and {@code limit} down to
     * 1000).
     */
    public Page<Message> listMessages(String chatId, @Nullable MessagesQuery query) {
        Map<String, Object> map = query == null ? Map.of() : query.asMap();
        return messagesPage(
                listMessages(chatId, map, intOrDefault(map.get("page"), 1), intOrDefault(map.get("limit"), 50)));
    }

    /**
     * Post a message.
     *
     * @param chat    target chat; only its id is required, other fields create/update it
     * @param user    the author — required by the backend at the top level
     * @param text    message body; must be non-empty
     * @param options participants to seed a chat being created, message extra and
     *                buttons; {@code null} applies all defaults (none of them)
     * @return the created message ids (the send endpoint does not echo the stored
     *         messages); see {@link SentMessages}
     */
    public SentMessages sendMessage(Chat chat, User user, String text, @Nullable SendMessageOptions options) {
        SendMessageOptions opts = options == null ? SendMessageOptions.builder().build() : options;

        if (text == null || text.isEmpty()) {
            throw new GetChatException("message text is required");
        }
        if (chat == null) {
            throw new GetChatException("first parameter(chat) have to be a plain object or string");
        }

        Map<String, Object> chatData = Signing.normalizeChat(chat.asMap());
        Object rawId = chatData.get("id");
        if (!Helpers.isString(rawId)) {
            if (!Helpers.isNumeric(rawId)) {
                throw new GetChatException("chat id isn't passed");
            }
            rawId = Helpers.jsString(rawId);
        }
        String chatId = (String) rawId;
        chatData.remove("id");

        if (chatData.containsKey("create")) {
            chatData.put("create", Helpers.isTRUE(chatData.get("create")));
        }

        Map<String, Object> extra = opts.extra();
        List<Map<String, Object>> buttons = opts.buttons();

        Map<String, Object> messageData = new LinkedHashMap<>();
        messageData.put("text", text);
        if (extra != null && !extra.isEmpty()) {
            messageData.put("extra", extra);
        }
        if (buttons != null && !buttons.isEmpty()) {
            messageData.put("buttons", buttons);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user == null ? Map.of() : user.asMap());
        body.put("messages", List.of(messageData));
        if (!chatData.isEmpty()) {
            body.put("chat", chatData);
        }
        List<Recipient> participants = opts.participants();
        if (participants != null && !participants.isEmpty()) {
            body.put("participants", normalizeParticipants(participants));
        }

        return SentMessages.of(
                execute(ApiRequest.post("chats/" + pathParam(chatId) + "/messages").body(body).build()));
    }

    /** Post a plain-text message (no participants, extra or buttons). */
    public SentMessages sendMessage(Chat chat, User user, String text) {
        return sendMessage(chat, user, text, null);
    }

    /**
     * Edit a message.
     *
     * <p>{@code text} is the new message body; pass {@code null} or an empty
     * string to leave it unchanged. Everything else — {@code extra}, buttons,
     * merge-vs-replace and whether to get the message back — rides on
     * {@link UpdateMessageOptions}.
     *
     * <p>The returned {@link UpdatedMessage} always reports {@link UpdatedMessage#isUpdated()};
     * its {@link UpdatedMessage#message()} is populated only when the options set
     * {@code returnResource(true)}.
     */
    public UpdatedMessage updateMessage(
            String chatId, String messageId, @Nullable String text, @Nullable UpdateMessageOptions options) {
        UpdateMessageOptions opts = options == null ? UpdateMessageOptions.builder().build() : options;

        Map<String, Object> message = new LinkedHashMap<>();
        if (text != null && !text.isEmpty()) {
            message.put("text", text);
        }
        Map<String, Object> extra = opts.extra();
        if (extra != null && !extra.isEmpty()) {
            message.put("extra", extra);
        }
        List<Map<String, Object>> buttons = opts.buttons();
        if (buttons != null && !buttons.isEmpty()) {
            message.put("buttons", buttons);
        }
        // The set() escape hatch (e.g. is_deleted) goes on the message object last.
        message.putAll(opts.messageFields());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("update_extra_mode", opts.extraMode() == UpdateMessageOptions.ExtraMode.REPLACE ? "replace" : "merge");

        Map<String, String> headers = opts.returnResource() ? Map.of("Prefer", "return=representation") : null;

        return UpdatedMessage.of(
                execute(ApiRequest.put("chats/" + pathParam(chatId) + "/messages/" + pathParam(messageId))
                        .body(body)
                        .headers(headers)
                        .build()));
    }

    /** Replace a message's text (merge extra, no message returned). */
    public UpdatedMessage updateMessage(String chatId, String messageId, @Nullable String text) {
        return updateMessage(chatId, messageId, text, UpdateMessageOptions.builder().build());
    }

    /**
     * Soft-delete a message. Returns the response {@code status} flag (always
     * {@code true} on a successful call — a non-2xx response throws instead). This
     * is a {@code PUT} of {@code is_deleted: true}; the SDK requests no
     * representation, so the deleted message is not returned.
     */
    public boolean deleteMessage(String chatId, String messageId) {
        Map<String, Object> body = Map.of("message", Map.of("is_deleted", true));
        return statusOf(execute(ApiRequest.put("chats/" + pathParam(chatId) + "/messages/" + pathParam(messageId))
                .body(body)
                .build()));
    }

    /**
     * Show a typing indicator for a given duration. Returns the response
     * {@code status} flag (always {@code true} on a successful call — a non-2xx
     * response throws instead).
     *
     * <p>The backend accepts whole seconds only, from 1 to 60. The {@code duration}
     * must therefore have no fractional-second component (it is not silently
     * truncated) and must land in {@code [1s, 60s]}; anything else throws
     * {@link GetChatException}. To send no duration (the client default applies),
     * use {@link #sendTyping(String, String)}.
     *
     * @param duration how long the indicator stays up (1–60 whole seconds); must not be null
     * @throws GetChatException if the duration has a sub-second component or falls outside 1–60 seconds
     */
    public boolean sendTyping(String chatId, String userId, Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.getNano() != 0) {
            throw new GetChatException("typing duration must be a whole number of seconds");
        }
        long seconds = duration.getSeconds();
        if (seconds < 1 || seconds > 60) {
            throw new GetChatException("typing duration must be between 1 and 60 seconds");
        }
        Map<String, Object> query = Map.of("time", (int) seconds);
        return statusOf(execute(ApiRequest.put("chats/" + pathParam(chatId) + "/typing/" + pathParam(userId))
                .query(query)
                .build()));
    }

    /** Show a typing indicator for the client default duration (no {@code time} sent). */
    public boolean sendTyping(String chatId, String userId) {
        return statusOf(execute(ApiRequest.put("chats/" + pathParam(chatId) + "/typing/" + pathParam(userId))
                .build()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Participants
    // ─────────────────────────────────────────────────────────────────────────

    /** The internal implementation behind {@link #listParticipants(String, PageQuery)}. */
    private JsonValue listParticipants(String chatId, int page, int limit) {
        requireChatId(chatId);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", Math.max(page, 1));
        query.put("limit", Math.min(limit, 1000));
        return execute(ApiRequest.get("chats/" + pathParam(chatId) + "/participants").query(query).build());
    }

    /**
     * List a chat's participants, paginated.
     *
     * <p>Pagination rides a {@link PageQuery}: an unset {@code page} defaults to 1
     * and an unset {@code limit} to 50 (matching the node SDK's defaults for this
     * endpoint), and the endpoint clamps {@code page} up to 1 and {@code limit}
     * down to 1000.
     *
     * <p>The backend serialises this list as a plain {@code participants} array (not
     * the id-map used by {@link #listChats}); {@link Page#items()} maps it to
     * {@link Participant} in server order.
     */
    public Page<Participant> listParticipants(String chatId, @Nullable PageQuery query) {
        return participantsPage(listParticipants(chatId, pageOr(query, 1), limitOr(query, 50)));
    }

    /** List the first page (up to 50) of a chat's participants. */
    public Page<Participant> listParticipants(String chatId) {
        return participantsPage(listParticipants(chatId, 1, 50));
    }

    /**
     * Add participants to a chat. Returns the response {@code status} flag (always
     * {@code true} on a successful call — a non-2xx response throws instead); the
     * endpoint does not echo the added participants (read them back with
     * {@link #listParticipants(String)}).
     */
    public boolean addParticipants(String chatId, List<Recipient> participants) {
        requireChatId(chatId);
        if (participants == null || participants.isEmpty()) {
            throw new GetChatException("participants have to be an array of participant objects");
        }
        Map<String, Object> body = Map.of("participants", normalizeParticipants(participants));
        return statusOf(execute(ApiRequest.post("chats/" + pathParam(chatId) + "/participants").body(body).build()));
    }

    /**
     * Remove a participant from a chat. Returns the response {@code status} flag
     * (always {@code true} on a successful call — a non-2xx response throws instead).
     */
    public boolean removeParticipant(String chatId, String userId) {
        requireChatId(chatId);
        return statusOf(execute(
                ApiRequest.delete("chats/" + pathParam(chatId) + "/participants/" + pathParam(userId)).build()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Users
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a user, optionally asking the backend to echo it back.
     *
     * <p>The payload is wrapped as {@code {"user": user}}, matching the node SDK
     * and {@code user.create} in {@code openapi.yml} (the same envelope
     * {@link #updateUser} and {@link #createChat} use).
     *
     * <p>The returned {@link UserDetails} unwraps the response {@code user} object,
     * which the backend includes only when the caller asks for a representation
     * ({@code Prefer: return=representation}). Set {@code returnResource(true)} on the
     * options to request it and get a populated view back; without it (or with
     * {@code null} options) the SDK sends no such header and the returned view is
     * empty (its accessors fall back to defaults) — call {@link #getUser(String)} to
     * read the created user back.
     *
     * @param user    the user to create; must be a non-empty object
     * @param options create flags such as {@code returnResource}; {@code null} applies
     *                the defaults (no representation requested)
     */
    public UserDetails createUser(User user, @Nullable CreateUserOptions options) {
        if (user == null || user.asMap().isEmpty()) {
            throw new GetChatException("user must be a non-empty object");
        }
        CreateUserOptions opts = options == null ? CreateUserOptions.builder().build() : options;
        Map<String, String> headers = opts.returnResource() ? Map.of("Prefer", "return=representation") : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", user.asMap());
        return UserDetails.of(
                execute(ApiRequest.post("users").body(body).headers(headers).build()).get("user"));
    }

    /** Create a user with no representation requested. */
    public UserDetails createUser(User user) {
        return createUser(user, null);
    }

    /**
     * Fetch a user.
     *
     * <p>The returned {@link UserDetails} unwraps the response {@code user} object.
     */
    public UserDetails getUser(String userId) {
        return UserDetails.of(execute(ApiRequest.get("users/" + pathParam(userId)).build()).get("user"));
    }

    /**
     * The internal {@code Map} implementation behind
     * {@link #updateUser(String, User, UpdateUserOptions)}.
     *
     * <p>The payload is wrapped as {@code {"user": updates}}, matching the node
     * SDK and {@code user.update} in {@code openapi.yml} (the same envelope
     * chats use).
     */
    private JsonValue updateUser(
            String userId, @Nullable Map<String, Object> updates, @Nullable Map<String, String> headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", updates == null ? Map.of() : updates);
        return execute(ApiRequest.put("users/" + pathParam(userId)).body(body).headers(headers).build());
    }

    /**
     * Update mutable user fields from a {@link User} builder, optionally asking the
     * backend to echo the user back.
     *
     * <p>The update endpoint ({@code user.update}) accepts only {@code id},
     * {@code name}, {@code email}, {@code link}, {@code picture} and
     * {@code metadata}. Other fields a {@code User} can carry — {@code session},
     * {@code is_bot}, {@code rights} — are not part of the update contract; this
     * SDK does not filter them (the backend is the source of truth), so send only
     * the fields you intend to change.
     *
     * <p>Like {@link #createUser}, the returned {@link UserDetails} unwraps the
     * response {@code user} object, which the backend includes only for a requested
     * representation. Set {@code returnResource(true)} on the options to get a
     * populated view back; without it (or with {@code null} options) the SDK sends no
     * such preference and the returned view is empty — use {@link #getUser(String)}
     * to read the user back.
     *
     * @param userId  the user to update
     * @param changes the fields to change; {@code null} sends an empty update
     * @param options update flags such as {@code returnResource}; {@code null} applies
     *                the defaults (no representation requested)
     */
    public UserDetails updateUser(String userId, @Nullable User changes, @Nullable UpdateUserOptions options) {
        UpdateUserOptions opts = options == null ? UpdateUserOptions.builder().build() : options;
        Map<String, String> headers = opts.returnResource() ? Map.of("Prefer", "return=representation") : null;
        return UserDetails.of(
                updateUser(userId, changes == null ? null : changes.asMap(), headers).get("user"));
    }

    /** Update mutable user fields with no representation requested. */
    public UserDetails updateUser(String userId, @Nullable User changes) {
        return updateUser(userId, changes, null);
    }

    /**
     * Delete a user. Returns the response {@code status} flag (always {@code true} on
     * a successful call — a non-2xx response throws instead).
     */
    public boolean deleteUser(String userId) {
        return statusOf(execute(ApiRequest.delete("users/" + pathParam(userId)).build()));
    }

    /** The internal implementation behind {@link #listUserChats(String, PageQuery)}. */
    private JsonValue listUserChats(String userId, int page, int limit) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", Math.max(page, 1));
        query.put("limit", Math.min(limit, 1000));
        return execute(ApiRequest.get("users/" + pathParam(userId) + "/chats").query(query).build());
    }

    /**
     * List the chats a user belongs to, paginated.
     *
     * <p>Pagination rides a {@link PageQuery}: an unset {@code page} defaults to 1
     * and an unset {@code limit} to 50 (matching the node SDK's defaults for this
     * endpoint), and the endpoint clamps {@code page} up to 1 and {@code limit}
     * down to 1000.
     *
     * <p>Unlike {@link #listChats}, this endpoint serialises its {@code chats} as a
     * plain array (not an id-map with a sort order); {@link Page#items()} maps it to
     * {@link ChatDetails} in server order.
     */
    public Page<ChatDetails> listUserChats(String userId, @Nullable PageQuery query) {
        return userChatsPage(listUserChats(userId, pageOr(query, 1), limitOr(query, 50)));
    }

    /** List the first page (up to 50) of the chats a user belongs to. */
    public Page<ChatDetails> listUserChats(String userId) {
        return userChatsPage(listUserChats(userId, 1, 50));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void requireChatId(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            throw new GetChatException("chat id isn't passed");
        }
    }

    /**
     * Wrap a {@code GET /chats} envelope as a typed page. The backend serialises the
     * list as a {@code chats} map keyed by id plus a {@code chats_sort} array giving
     * the order; {@link Page} rebuilds the ordered list of {@link ChatDetails}.
     */
    private static Page<ChatDetails> chatsPage(JsonValue envelope) {
        return Page.of(envelope, "chats_sort", "chats", ChatDetails::of);
    }

    /** Wrap a {@code GET /chats/{id}/messages} envelope as a typed page of {@link Message}. */
    private static Page<Message> messagesPage(JsonValue envelope) {
        return Page.of(envelope, "messages_sort", "messages", Message::of);
    }

    /**
     * Wrap a {@code GET /chats/{id}/participants} envelope as a typed page of
     * {@link Participant}. The backend serialises this list as a plain
     * {@code participants} array, not the id-map {@link #chatsPage} handles.
     */
    private static Page<Participant> participantsPage(JsonValue envelope) {
        return Page.ofArray(envelope, "participants", Participant::of);
    }

    /**
     * Wrap a {@code GET /users/{id}/chats} envelope as a typed page of
     * {@link ChatDetails}. Unlike {@code GET /chats}, this endpoint returns a plain
     * {@code chats} array, so it uses the array-shaped page, not {@link #chatsPage}.
     */
    private static Page<ChatDetails> userChatsPage(JsonValue envelope) {
        return Page.ofArray(envelope, "chats", ChatDetails::of);
    }

    /**
     * The {@code status} flag of a status-only response. Defaults to {@code true}
     * because the caller only reaches this code on a 2xx/3xx response (a non-2xx
     * throws), so a missing flag still means success.
     */
    private static boolean statusOf(JsonValue envelope) {
        return envelope.get("status").asBoolean(true);
    }

    private static List<Map<String, Object>> normalizeParticipants(List<Recipient> participants) {
        List<Map<String, Object>> out = new ArrayList<>(participants.size());
        for (Recipient participant : participants) {
            out.add(Signing.normalizeParticipant(participant.asMap()));
        }
        return out;
    }

    /** Accept a lenient boolean and write it as the integer 0/1 the backend wants. */
    private static void putSmartFlag(Map<String, Object> query, String key, @Nullable Object value) {
        if (Helpers.isBoolean(value, true)) {
            query.put(key, Helpers.isTRUE(value) ? 1 : 0);
        }
    }

    /**
     * Write a {@code with_owner(s)} flag as the integer 0/1 the backend wants.
     *
     * <p>Shared verbatim by {@code with_owners} and {@code with_owner} so the two
     * flags coerce identically: a real boolean maps to 1/0, a lenient truthy
     * string ({@code "1"}/{@code "true"}/{@code "on"}/{@code "yes"}) to 1, and a
     * numeric 0/1 passes through; anything else (including {@code null}) is
     * skipped, leaving the key off the wire.
     */
    private static void putOwnerFlag(Map<String, Object> query, String key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        if (Helpers.isBoolean(value)) {
            query.put(key, Boolean.TRUE.equals(value) ? 1 : 0);
        } else if (Helpers.isTRUE(value)) {
            query.put(key, 1);
        } else if (Helpers.isNumeric(value)) {
            int n = ((Number) value).intValue();
            if (n == 0 || n == 1) {
                query.put(key, n);
            }
        }
    }

    /** The {@link PageQuery}'s page, or {@code fallback} when the query or field is unset. */
    private static int pageOr(@Nullable PageQuery query, int fallback) {
        return query != null && query.page() != null ? query.page() : fallback;
    }

    /** The {@link PageQuery}'s limit, or {@code fallback} when the query or field is unset. */
    private static int limitOr(@Nullable PageQuery query, int fallback) {
        return query != null && query.limit() != null ? query.limit() : fallback;
    }

    private static int intOrDefault(@Nullable Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            double parsed = Helpers.jsNumber(s);
            if (!Double.isNaN(parsed) && parsed != 0) {
                return (int) parsed;
            }
        }
        return fallback;
    }

    /**
     * Diagnostic dump for logs. The {@code apiToken} is <strong>redacted</strong> —
     * a set value renders as {@code ***} and never appears in the clear, so a client
     * is safe to log. The {@code httpClient} renders only as {@code set}/{@code unset}.
     */
    @Override
    public String toString() {
        return "GetChatClient{apiUrl=" + apiUrl
                + ", apiToken=***"
                + ", options=" + requestOptions
                + ", httpClient=" + (ownsHttpClient ? "unset" : "set")
                + "}";
    }

    /** Builder for {@link GetChatClient}; the API URL and token are required. */
    public static final class Builder {

        private String apiUrl = "";
        private String apiToken = "";
        private @Nullable RequestOptions options;
        private @Nullable HttpClient httpClient;

        private Builder() {}

        /** REST API base URL. Required. Trailing slashes are stripped. */
        public Builder apiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
            return this;
        }

        /**
         * REST API base URL, as a {@link URI}. Equivalent to {@link #apiUrl(String)}
         * with {@code apiUrl.toString()}. Required. Trailing slashes are stripped.
         */
        public Builder apiUrl(URI apiUrl) {
            this.apiUrl = apiUrl.toString();
            return this;
        }

        /** Bearer token for the REST API. Required. */
        public Builder apiToken(String apiToken) {
            this.apiToken = apiToken;
            return this;
        }

        /** Instance-wide timeout/retry settings; defaults to {@link RequestOptions#defaults()}. */
        public Builder options(@Nullable RequestOptions options) {
            this.options = options;
            return this;
        }

        /** Supply your own {@link HttpClient} (proxy, custom SSL, executor). */
        public Builder httpClient(@Nullable HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * @throws GetChatException if the API URL or API token is missing or blank,
         *     or the API URL is not an absolute {@code http}/{@code https} URL
         */
        public GetChatClient build() {
            if (isBlank(apiUrl)) {
                throw new GetChatException("api url is required");
            }
            if (isBlank(apiToken)) {
                throw new GetChatException("api token is required");
            }
            requireHttpUrl(apiUrl, "api url");
            // Strip trailing slashes so `apiUrl + "/api/..."` never doubles a slash.
            this.apiUrl = apiUrl.replaceAll("/+$", "");
            return new GetChatClient(this);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        /**
         * Fail fast on a URL the transport could never use: it must parse and be an
         * absolute URI carrying an {@code http}/{@code https} scheme. Anything else
         * (relative, opaque, {@code ftp:}, malformed) is a configuration error.
         */
        private static void requireHttpUrl(String value, String label) {
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException e) {
                throw new GetChatException(label + " must be an absolute http(s) URL");
            }
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new GetChatException(label + " must be an absolute http(s) URL");
            }
        }
    }
}
