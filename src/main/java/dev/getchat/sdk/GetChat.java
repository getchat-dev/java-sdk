package dev.getchat.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.getchat.sdk.internal.Helpers;
import dev.getchat.sdk.internal.NormalizeFilter;
import dev.getchat.sdk.internal.Retry;
import dev.getchat.sdk.internal.Signing;
import dev.getchat.sdk.internal.UserRights;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * The GetChat SDK client: signed embed URLs plus a REST API wrapper.
 *
 * <p>The node SDK calls this class {@code Emby}, after the service's former
 * name. This SDK uses the current product name throughout; nothing on the wire
 * carries either name, so the two clients stay interchangeable.
 *
 * <pre>{@code
 * GetChat sdk = new GetChat(GetChatConfig.builder()
 *         .id("client-id")
 *         .secret("client-secret")
 *         .apiToken("api-token")
 *         .baseUrl("https://chat.example.com/embed")
 *         .build());
 *
 * String url = sdk.url(UrlOptions.builder()
 *         .chat("support-42")
 *         .user(User.builder().id("u1").name("Alice").build())
 *         .build());
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads; create one and
 * share it for the life of the application. When the SDK created the underlying
 * {@link HttpClient} itself (no {@code httpClient} on the config), a
 * short-lived instance can release it via {@link #close()} or
 * try-with-resources; a client you passed in is never closed for you. See
 * {@link #close()} for the JDK-version caveat.
 */
public class GetChat implements AutoCloseable {

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

    private final @Nullable String clientId;
    private final @Nullable String clientSecret;
    private final @Nullable String apiToken;
    private final @Nullable String baseUrl;
    private final @Nullable String apiUrl;
    private final RequestOptions requestOptions;
    private final HttpClient httpClient;
    // True only when the SDK built the HttpClient itself; a caller-supplied
    // client stays the caller's to close, so close() must leave it alone.
    private final boolean ownsHttpClient;
    private final IntFunction<String> random;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── URL-flow whitelists ──────────────────────────────────────────────────
    // Field ORDER here is part of the wire contract: it decides both the query
    // string layout and the order values enter the signature.

    private static final List<String> URL_USER_SIGNATURE_FIELDS =
            List.of("id", "name", "email", "link", "picture", "rights");
    private static final List<String> URL_CHAT_SIGNATURE_FIELDS = List.of("id", "title", "create");
    private static final List<String> URL_RECIPIENT_SIGNATURE_FIELDS = List.of("id", "name");

    private static final List<String> LEGACY_USER_SIGNATURE_FIELDS =
            List.of("id", "name", "email", "picture");
    private static final List<String> LEGACY_RECIPIENT_SIGNATURE_FIELDS =
            List.of("id", "name", "email", "picture");
    private static final List<String> LEGACY_CHAT_SIGNATURE_FIELDS = List.of("id", "list", "title", "create");

    private static final NormalizeFilter URL_RECIPIENT_FILTER =
            NormalizeFilter.create().fields("id", "name").withDefault("is_bot", Boolean.FALSE);

    private static final NormalizeFilter LEGACY_RECIPIENT_FILTER = NormalizeFilter.create()
            .fields("id", "name", "email", "link", "picture")
            .withDefault("is_bot", Boolean.FALSE);

    public GetChat(GetChatConfig config) {
        // A missing config is a programming error, not user input, so it stays a
        // JDK-level NPE rather than the SDK's GetChatException — the deliberate
        // boundary of the "validation throws GetChatException" policy.
        Objects.requireNonNull(config, "config is required");
        this.clientId = config.id();
        this.clientSecret = config.secret();
        this.apiToken = config.apiToken();
        this.baseUrl = config.baseUrl();
        this.apiUrl = config.apiUrl();
        this.requestOptions = config.options();
        this.random = config.randomStringSupplier() != null ? config.randomStringSupplier() : Helpers::randomString;
        this.ownsHttpClient = config.httpClient() == null;
        this.httpClient = ownsHttpClient
                ? HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                : config.httpClient();
    }

    /**
     * Release the HTTP client this instance owns.
     *
     * <p>Only closes the client when the SDK created it (no {@code httpClient} on
     * the config). A client you supplied is left open — its lifecycle is yours.
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
    // URL signing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build a signed embed URL (HMAC-SHA256 scheme).
     *
     * <p>The signature covers the client id, a fresh nonce, the user fields, each
     * recipient's id/name, and the chat's id/title/create — in that exact order.
     * {@code extra} is appended afterwards and is NOT signed.
     *
     * @throws GetChatException if the client id or secret is missing
     */
    public String url(UrlOptions options) {
        requireSigningCredentials();

        Map<String, Object> chatData =
                options.chat() == null ? null : Signing.normalizeChat(options.chat().asMap());
        Map<String, Object> userData = normalizeUrlUser(options.user());

        String nonce = random.apply(32);

        List<Object> signature = new ArrayList<>();
        signature.add(clientId);
        signature.add(nonce);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("nonce", nonce);
        query.put("user", userData);
        List<Object> recipients = new ArrayList<>();
        query.put("recipients", recipients);

        signature = Signing.addToSignature(signature, userData, URL_USER_SIGNATURE_FIELDS);

        for (Recipient participant : options.participants()) {
            Map<String, Object> normalized = Signing.normalizeData(participant.asMap(), URL_RECIPIENT_FILTER);
            recipients.add(normalized);
            signature = Signing.addToSignature(signature, normalized, URL_RECIPIENT_SIGNATURE_FIELDS);
        }

        if (chatData != null) {
            signature = Signing.addToSignature(signature, chatData, URL_CHAT_SIGNATURE_FIELDS);
            query.put("chat", chatData);
        }

        query.put("signature", hmacSha256Hex(clientSecret, Signing.joinSignature(signature)));

        query.putAll(options.extra());

        return emit(query);
    }

    /**
     * Build a signed embed URL with the legacy MD5 scheme.
     *
     * <p>Kept for backward compatibility; the backend verifies it separately from
     * {@link #url}. It signs {@code secret + nonce} rather than {@code clientId},
     * sorts each section alphabetically instead of using a fixed field order, and
     * leaves {@code rights} out of the signature entirely.
     *
     * @throws GetChatException if credentials are missing or the chat has no id
     */
    public String urlByChatId(UrlOptions options) {
        requireSigningCredentials();

        // The legacy scheme requires a chat; {@code url()} treats it as optional,
        // which is the only shape difference between the two builders' inputs.
        if (options.chat() == null) {
            throw new GetChatException("first parameter(chat) have to be a plain object or string");
        }

        Map<String, Object> chatData = Signing.normalizeChat(options.chat().asMap());
        if (!Helpers.isString(chatData.get("id"))) {
            throw new GetChatException("chat id isn't passed");
        }

        // Order of the random draws is load-bearing: the session (40 chars) is
        // requested inside user normalisation, before the nonce (32 chars).
        Map<String, Object> userData = normalizeUrlUser(options.user());

        String nonce = random.apply(32);

        List<Object> signature = new ArrayList<>();
        signature.add(clientSecret);
        signature.add(nonce);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("nonce", nonce);
        query.put("chat", chatData);
        query.put("user", userData);
        List<Object> recipients = new ArrayList<>();
        query.put("recipients", recipients);

        signature = Signing.appendLegacy(signature, userData, LEGACY_USER_SIGNATURE_FIELDS);

        for (Recipient participant : options.participants()) {
            Map<String, Object> normalized = Signing.normalizeData(participant.asMap(), LEGACY_RECIPIENT_FILTER);
            recipients.add(normalized);
            signature = Signing.appendLegacy(signature, normalized, LEGACY_RECIPIENT_SIGNATURE_FIELDS);
        }

        signature = Signing.appendLegacy(signature, chatData, LEGACY_CHAT_SIGNATURE_FIELDS);

        query.put("signature", md5Hex(Signing.joinSignature(signature)));

        query.putAll(options.extra());

        return emit(query);
    }

    /** Convenience overload: no participants, no extra params. */
    public String urlByChatId(Chat chat, User user) {
        return urlByChatId(UrlOptions.builder().user(user).chat(chat).build());
    }

    /** Convenience overload taking a bare chat id. */
    public String urlByChatId(String chatId, User user) {
        return urlByChatId(UrlOptions.builder().user(user).chat(Chat.of(chatId)).build());
    }

    /** Coerce booleans to 1/0 (post-signature), flatten, then percent-encode. */
    private String emit(Map<String, Object> query) {
        Object wire = Signing.coerceBooleansForWire(query);
        return baseUrl + "?" + dev.getchat.sdk.internal.QueryString.stringify(
                dev.getchat.sdk.internal.QueryString.flatten(wire));
    }

    /**
     * The user whitelist shared by both URL builders: {@code id, name, email,
     * picture, rights, session}. Note {@code link} and {@code is_bot} are dropped
     * here even though {@code link} appears in the HMAC field list — it never has
     * a value to contribute.
     */
    private Map<String, Object> normalizeUrlUser(User user) {
        if (user == null) {
            throw new GetChatException("user parameter have to be a plain object");
        }
        Map<String, Object> source = user.asMap();

        NormalizeFilter filter = NormalizeFilter.create()
                .fields("id", "name", "email", "picture")
                .processed("rights", value -> {
                    if (Helpers.isFilledPlainObject(value)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> rights = (Map<String, Object>) value;
                        Map<String, String> processed = UserRights.process(rights);
                        if (processed != null && !processed.isEmpty()) {
                            return processed;
                        }
                    }
                    return null;
                })
                // An anonymous viewer (no id) gets a random session token so the
                // backend can still tell one browser from another.
                .processed("session", value -> {
                    if (source.get("id") == null) {
                        return Helpers.isString(value) ? value : random.apply(40);
                    }
                    return null;
                });

        return Signing.normalizeData(source, filter);
    }

    private void requireSigningCredentials() {
        if (clientId == null || clientId.isEmpty()) {
            throw new GetChatException(
                    "To generate chat URL client id is required, please set it in the constructor config");
        }
        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new GetChatException(
                    "To generate chat URL client secret is required, please set it in the constructor config");
        }
    }

    private static String hmacSha256Hex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new GetChatException("failed to compute HMAC-SHA256 signature", e);
        }
    }

    private static String md5Hex(String message) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            return toHex(md.digest(message.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new GetChatException("failed to compute MD5 signature", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
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
     * JsonValue hooks = sdk.requestApi(ApiRequest.get("chats/support-42/webhooks")
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
        Objects.requireNonNull(request, "request is required");
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
     * @param request the fully-built call to make
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
                .header("Content-Type", "application/json");

        if (apiToken != null) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        if (headers != null) {
            headers.forEach(builder::header);
        }
        if (opts.timeout() > 0) {
            builder.timeout(Duration.ofMillis(opts.timeout()));
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

            sleep(Retry.backoffDelay(attempt, opts.retryDelay(), retryAfterMs));
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
     * The internal {@code Map} implementation behind {@link #getChats(ChatsQuery)}.
     *
     * <p>Accepted keys: {@code type, owner, created_from, created_to,
     * last_message_from, last_message_to} (strings), {@code with_owners}
     * (lenient boolean, sent as 0/1), {@code metadata} (map), plus {@code page}
     * and {@code limit}. The typed {@link ChatsQuery} builder is the public front
     * end; use its {@code set(key, value)} for a key without a typed setter.
     *
     * <p>Note {@code limit} defaults to 1, not to a page size — this mirrors the
     * node SDK, whose callers always pass one explicitly.
     */
    private JsonValue getChats(@Nullable Map<String, Object> queryParams) {
        return getChats(queryParams, null);
    }

    /** {@link #getChats(ChatsQuery)} with per-call transport overrides, {@code Map} form. */
    private JsonValue getChats(@Nullable Map<String, Object> queryParams, @Nullable RequestControl control) {
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

        Object withOwners = params.get("with_owners");
        if (withOwners != null) {
            if (Helpers.isBoolean(withOwners)) {
                query.put("with_owners", Boolean.TRUE.equals(withOwners) ? 1 : 0);
            } else if (Helpers.isTRUE(withOwners)) {
                query.put("with_owners", 1);
            } else if (Helpers.isNumeric(withOwners)) {
                int n = ((Number) withOwners).intValue();
                if (n == 0 || n == 1) {
                    query.put("with_owners", n);
                }
            }
        }

        if (Helpers.isFilledPlainObject(params.get("metadata"))) {
            query.put("metadata", params.get("metadata"));
        }

        return execute(ApiRequest.get("chats").query(query).control(control).build());
    }

    /**
     * List chats with typed filters.
     *
     * <p>Accepts the filters {@link ChatsQuery} carries — {@code type},
     * {@code owner}, the four date-range strings, {@code with_owners} (sent as
     * 0/1), {@code metadata}, plus {@code page} and {@code limit}. Reach a key
     * without a typed setter through {@link ChatsQuery.Builder#set(String, Object)}.
     * Note {@code limit} defaults to 1 when it is not set — a wart kept for parity
     * with the node SDK; pass a limit explicitly.
     */
    public JsonValue getChats(@Nullable ChatsQuery query) {
        return getChats(query == null ? Map.of() : query.asMap());
    }

    /** {@link #getChats(ChatsQuery)} with per-call transport overrides. */
    public JsonValue getChats(@Nullable ChatsQuery query, @Nullable RequestControl control) {
        return getChats(query == null ? Map.of() : query.asMap(), control);
    }

    /** Fetch a single chat. */
    public JsonValue getChatInfo(String chatId) {
        requireChatId(chatId);
        return execute(ApiRequest.get("chats/" + pathParam(chatId)).build());
    }

    /**
     * Create a chat. The backend requires {@code participants} for a private chat.
     */
    public JsonValue createChat(Chat chat, @Nullable List<Recipient> participants) {
        if (chat == null || chat.asMap().isEmpty()) {
            throw new GetChatException("chat must be a non-empty object");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat", chat.asMap());
        if (participants != null && !participants.isEmpty()) {
            body.put("participants", normalizeParticipants(participants));
        }
        return execute(ApiRequest.post("chats").body(body).build());
    }

    /** Create a chat with no seeded participants. */
    public JsonValue createChat(Chat chat) {
        return createChat(chat, null);
    }

    /** The internal {@code Map} implementation behind {@link #updateChat(String, Chat)}. */
    private JsonValue updateChat(String chatId, @Nullable Map<String, Object> updates) {
        requireChatId(chatId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat", updates == null ? Map.of() : updates);
        return execute(ApiRequest.put("chats/" + pathParam(chatId)).body(body).build());
    }

    /**
     * Update mutable chat fields from a {@link Chat} builder.
     *
     * <p>The update endpoint ({@code chat.update}) accepts only {@code id},
     * {@code title} and {@code metadata}. Any other field a {@code Chat} can
     * carry — {@code create}, {@code type} — is not part of the update contract;
     * this SDK does not filter them (the backend is the source of truth), so send
     * only the fields you intend to change.
     */
    public JsonValue updateChat(String chatId, @Nullable Chat changes) {
        return updateChat(chatId, changes == null ? null : changes.asMap());
    }

    /** Delete a chat permanently. */
    public JsonValue deleteChat(String chatId) {
        requireChatId(chatId);
        return execute(ApiRequest.delete("chats/" + pathParam(chatId)).build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Messages
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The internal {@code Map} implementation behind
     * {@link #getMessagesFromChat(String, MessagesQuery, int, int)}.
     *
     * <p>Accepted keys in {@code queryParams}: {@code extra} (map of scalars),
     * {@code isDeleted}, {@code isEdited}, {@code with_users} — all lenient
     * booleans sent as 0/1.
     */
    private JsonValue getMessagesFromChat(
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

    /** Read the first page of messages. */
    public JsonValue getMessagesFromChat(String chatId) {
        return getMessagesFromChat(chatId, Map.of(), 1, 50);
    }

    /**
     * Read messages from a chat with typed filters.
     *
     * <p>Accepts the filters {@link MessagesQuery} carries — {@code extra},
     * {@code isDeleted}, {@code isEdited}, {@code with_users} — and reaches a key
     * without a typed setter through {@link MessagesQuery.Builder#set(String, Object)}.
     */
    public JsonValue getMessagesFromChat(String chatId, @Nullable MessagesQuery query, int page, int limit) {
        return getMessagesFromChat(chatId, query == null ? Map.of() : query.asMap(), page, limit);
    }

    /** Read the first page (up to 50) of messages with typed filters. */
    public JsonValue getMessagesFromChat(String chatId, @Nullable MessagesQuery query) {
        return getMessagesFromChat(chatId, query, 1, 50);
    }

    /**
     * Post a message.
     *
     * @param chat    target chat; only its id is required, other fields create/update it
     * @param user    the author — required by the backend at the top level
     * @param text    message body; must be non-empty
     * @param options participants to seed a chat being created, message extra and
     *                buttons; {@code null} applies all defaults (none of them)
     */
    public JsonValue sendMessage(Chat chat, User user, String text, @Nullable SendMessageOptions options) {
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

        return execute(ApiRequest.post("chats/" + pathParam(chatId) + "/messages").body(body).build());
    }

    /** Post a plain-text message (no participants, extra or buttons). */
    public JsonValue sendMessage(Chat chat, User user, String text) {
        return sendMessage(chat, user, text, null);
    }

    /**
     * Edit a message.
     *
     * <p>{@code text} is the new message body; pass {@code null} or an empty
     * string to leave it unchanged. Everything else — {@code extra}, buttons,
     * merge-vs-replace and whether to get the message back — rides on
     * {@link UpdateMessageOptions}.
     */
    public JsonValue updateMessage(
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

        Map<String, String> headers = opts.returnMessage() ? Map.of("Prefer", "return=representation") : null;

        return execute(ApiRequest.put("chats/" + pathParam(chatId) + "/messages/" + pathParam(messageId))
                .body(body)
                .headers(headers)
                .build());
    }

    /** Replace a message's text (merge extra, no message returned). */
    public JsonValue updateMessage(String chatId, String messageId, @Nullable String text) {
        return updateMessage(chatId, messageId, text, UpdateMessageOptions.builder().build());
    }

    /** Soft-delete a message. */
    public JsonValue deleteMessage(String chatId, String messageId) {
        Map<String, Object> body = Map.of("message", Map.of("is_deleted", true));
        return execute(ApiRequest.put("chats/" + pathParam(chatId) + "/messages/" + pathParam(messageId))
                .body(body)
                .build());
    }

    /**
     * Show a typing indicator.
     *
     * @param seconds how long the indicator stays up (1–60); null uses the client default
     */
    public JsonValue sendTyping(String chatId, String userId, @Nullable Integer seconds) {
        Map<String, Object> query = seconds == null ? null : Map.of("time", seconds);
        return execute(ApiRequest.put("chats/" + pathParam(chatId) + "/typing/" + pathParam(userId))
                .query(query)
                .build());
    }

    /** Show a typing indicator for the client default duration. */
    public JsonValue sendTyping(String chatId, String userId) {
        return sendTyping(chatId, userId, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Participants
    // ─────────────────────────────────────────────────────────────────────────

    /** List a chat's participants. */
    public JsonValue getChatParticipants(String chatId, int page, int limit) {
        requireChatId(chatId);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", Math.max(page, 1));
        query.put("limit", Math.min(limit, 1000));
        return execute(ApiRequest.get("chats/" + pathParam(chatId) + "/participants").query(query).build());
    }

    /** Add participants to a chat. */
    public JsonValue addParticipantsToChat(String chatId, List<Recipient> participants) {
        requireChatId(chatId);
        if (participants == null || participants.isEmpty()) {
            throw new GetChatException("participants have to be an array of participant objects");
        }
        Map<String, Object> body = Map.of("participants", normalizeParticipants(participants));
        return execute(ApiRequest.post("chats/" + pathParam(chatId) + "/participants").body(body).build());
    }

    /** Remove a participant from a chat. */
    public JsonValue removeParticipantFromChat(String chatId, String userId) {
        requireChatId(chatId);
        return execute(
                ApiRequest.delete("chats/" + pathParam(chatId) + "/participants/" + pathParam(userId)).build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Users
    // ─────────────────────────────────────────────────────────────────────────

    /** Create a user. */
    public JsonValue createUser(User user) {
        if (user == null || user.asMap().isEmpty()) {
            throw new GetChatException("user must be a non-empty object");
        }
        return execute(ApiRequest.post("users").body(user.asMap()).build());
    }

    /** Fetch a user. */
    public JsonValue getUser(String userId) {
        return execute(ApiRequest.get("users/" + pathParam(userId)).build());
    }

    /**
     * Update mutable user fields.
     *
     * <p>The payload is wrapped as {@code {"user": updates}}, matching the node
     * SDK and {@code user.update} in {@code openapi.yml} (the same envelope
     * chats use). This is the internal {@code Map} implementation behind
     * {@link #updateUser(String, User)}.
     */
    private JsonValue updateUser(String userId, @Nullable Map<String, Object> updates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", updates == null ? Map.of() : updates);
        return execute(ApiRequest.put("users/" + pathParam(userId)).body(body).build());
    }

    /**
     * Update mutable user fields from a {@link User} builder.
     *
     * <p>The update endpoint ({@code user.update}) accepts only {@code id},
     * {@code name}, {@code email}, {@code link}, {@code picture} and
     * {@code metadata}. Other fields a {@code User} can carry — {@code session},
     * {@code is_bot}, {@code rights} — are not part of the update contract; this
     * SDK does not filter them (the backend is the source of truth), so send only
     * the fields you intend to change.
     */
    public JsonValue updateUser(String userId, @Nullable User changes) {
        return updateUser(userId, changes == null ? null : changes.asMap());
    }

    /** Delete a user. */
    public JsonValue deleteUser(String userId) {
        return execute(ApiRequest.delete("users/" + pathParam(userId)).build());
    }

    /** List the chats a user belongs to. */
    public JsonValue getUserChats(String userId, int page, int limit) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", Math.max(page, 1));
        query.put("limit", Math.min(limit, 1000));
        return execute(ApiRequest.get("users/" + pathParam(userId) + "/chats").query(query).build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void requireChatId(String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            throw new GetChatException("chat id isn't passed");
        }
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
}
