package dev.getchat.sdk;

import dev.getchat.sdk.internal.Helpers;
import dev.getchat.sdk.internal.NormalizeFilter;
import dev.getchat.sdk.internal.Signing;
import dev.getchat.sdk.internal.UserRights;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Builds signed embed URLs for dropping the chat UI into an iframe or WebView.
 *
 * <p>This is one of the SDK's two entry points; {@link GetChatClient} is the
 * other. Signing needs only a client id, a client secret and a base URL — no
 * network resources — so this class opens none: it is pure computation and is
 * neither {@link AutoCloseable} nor backed by an HTTP client.
 *
 * <p>The node SDK folds signing and REST into a single {@code Emby} class. This
 * SDK keeps them apart on purpose: the {@link Builder} checks at
 * {@link Builder#build()} that every value it needs is present, so a
 * half-configured signer cannot be created. Nothing on the wire carries either
 * product name, so the clients stay interchangeable.
 *
 * <pre>{@code
 * GetChatUrlSigner signer = GetChatUrlSigner.builder()
 *         .clientId("client-id")
 *         .secret("client-secret")
 *         .baseUrl("https://chat.example.com/embed")
 *         .build();
 *
 * String url = signer.url(UrlOptions.builder()
 *         .chat("support-42")
 *         .user(User.builder().id("u1").name("Alice").build())
 *         .build());
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class GetChatUrlSigner {

    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private final IntFunction<String> random;
    // True only when the caller supplied a custom nonce/session generator; drives
    // the set/unset rendering in toString without exposing the supplier itself.
    private final boolean customRandom;

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

    private GetChatUrlSigner(Builder b) {
        this.clientId = b.clientId;
        this.clientSecret = b.secret;
        this.baseUrl = b.baseUrl;
        this.customRandom = b.randomStringSupplier != null;
        this.random = customRandom ? b.randomStringSupplier : Helpers::randomString;
    }

    /** Start building a {@link GetChatUrlSigner}. */
    public static Builder builder() {
        return new Builder();
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
     */
    public String url(UrlOptions options) {
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
     * @throws GetChatException if the chat has no id
     */
    public String urlByChatId(UrlOptions options) {
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

    /**
     * Diagnostic dump for logs. The {@code secret} is <strong>redacted</strong> —
     * a set value renders as {@code ***} and never appears in the clear — and the
     * random supplier renders only as {@code set}/{@code unset}.
     */
    @Override
    public String toString() {
        return "GetChatUrlSigner{clientId=" + clientId
                + ", secret=***"
                + ", baseUrl=" + baseUrl
                + ", randomStringSupplier=" + (customRandom ? "set" : "unset")
                + "}";
    }

    /** Builder for {@link GetChatUrlSigner}; every field it needs is required. */
    public static final class Builder {

        private String clientId = "";
        private String secret = "";
        private String baseUrl = "";
        private @Nullable IntFunction<String> randomStringSupplier;

        private Builder() {}

        /** Client id, the first field the signature covers. Required. */
        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        /** Client secret, the key for both URL signature schemes. Required. */
        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        /** Base URL the embed URLs point at. Required. Trailing slashes are stripped. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Override the nonce/session generator. Package-private on purpose: it
         * exists so the golden-vector tests can replay recorded nonces and
         * sessions, and production code should never touch it.
         */
        Builder randomStringSupplier(IntFunction<String> supplier) {
            this.randomStringSupplier = supplier;
            return this;
        }

        /**
         * @throws GetChatException if the client id, secret or base URL is missing
         *     or blank
         */
        public GetChatUrlSigner build() {
            if (isBlank(clientId)) {
                throw new GetChatException("client id is required");
            }
            if (isBlank(secret)) {
                throw new GetChatException("client secret is required");
            }
            if (isBlank(baseUrl)) {
                throw new GetChatException("base url is required");
            }
            // Trailing slashes are stripped so `baseUrl + "?" + query` is well-formed.
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            return new GetChatUrlSigner(this);
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
