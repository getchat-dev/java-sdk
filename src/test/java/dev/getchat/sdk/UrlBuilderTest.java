package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The public URL-building API as documented in README.md. Byte-exactness is
 * covered by {@link SignatureVectorTest}; this suite covers the behaviour a
 * caller can reason about.
 */
class UrlBuilderTest {

    private static GetChatUrlSigner sdk() {
        return GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl("https://chat.example.com/embed")
                .build();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            out.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    @DisplayName("the README example produces a signed URL with all its parts")
    void readmeExample() {
        String url = sdk().url(UrlOptions.builder()
                .chat(Chat.builder()
                        .id("support-42")
                        .title("Support")
                        .create(true)
                        .build())
                .user(User.builder()
                        .id("u-1")
                        .name("Alice")
                        .rights(Rights.builder()
                                .sendMessages(true)
                                .editMessages(Rights.Scope.MY)
                                .pinMessages(Rights.Pin.FOR_EVERYONE)
                                .build())
                        .build())
                .participant(Recipient.of("u-2", "Bob"))
                .extra("theme", "dark")
                .build());

        assertTrue(url.startsWith("https://chat.example.com/embed?"));

        Map<String, String> q = query(url);
        assertAll(
                () -> assertEquals("support-42", q.get("chat[id]")),
                () -> assertEquals("Support", q.get("chat[title]")),
                () -> assertEquals("1", q.get("chat[create]"), "booleans reach the wire as 1/0"),
                () -> assertEquals("u-1", q.get("user[id]")),
                () -> assertEquals("1", q.get("user[rights][send_messages]")),
                () -> assertEquals("my", q.get("user[rights][edit_messages]")),
                () -> assertEquals("for_everyone", q.get("user[rights][pin_messages]")),
                () -> assertEquals("u-2", q.get("recipients[0][id]")),
                () -> assertEquals("0", q.get("recipients[0][is_bot]"), "is_bot defaults to false"),
                () -> assertEquals("dark", q.get("theme")),
                () -> assertEquals(64, q.get("signature").length(), "HMAC-SHA256 hex"));
    }

    @Test
    @DisplayName("the legacy builder signs with MD5 and orders the query differently")
    void legacyUrl() {
        String url = sdk().urlByChatId(
                        "support-42", User.builder().id("u-1").name("Alice").build());

        assertEquals(32, query(url).get("signature").length(), "MD5 hex");
        // Legacy puts chat before user; the HMAC builder puts user first.
        assertTrue(url.indexOf("chat%5Bid%5D") < url.indexOf("user%5Bid%5D"));
    }

    @Test
    @DisplayName("each call gets a fresh nonce, so URLs are not reusable fingerprints")
    void nonceIsFresh() {
        GetChatUrlSigner sdk = sdk();
        UrlOptions options = UrlOptions.builder()
                .user(User.builder().id("u-1").name("Alice").build())
                .build();

        assertNotEquals(sdk.url(options), sdk.url(options));
    }

    @Test
    @DisplayName("a user without an id becomes an anonymous session")
    void anonymousUserGetsSession() {
        String url = sdk().url(UrlOptions.builder()
                .user(User.builder().name("Anonymous").build())
                .build());

        Map<String, String> q = query(url);
        assertEquals(40, q.get("user[session]").length());
        assertFalse(q.containsKey("user[id]"));
    }

    @Test
    @DisplayName("an explicit session is kept, and a user with an id gets none")
    void sessionRules() {
        String explicit = sdk().url(UrlOptions.builder()
                .user(User.builder().name("Anonymous").session("fixed-token").build())
                .build());
        assertEquals("fixed-token", query(explicit).get("user[session]"));

        String identified = sdk().url(UrlOptions.builder()
                .user(User.builder().id("u-1").session("ignored").build())
                .build());
        assertFalse(query(identified).containsKey("user[session]"), "an identified user needs no session");
    }

    @Test
    @DisplayName("fields outside the whitelist never reach the URL")
    void whitelistDropsUnknownFields() {
        String url = sdk().url(UrlOptions.builder()
                .user(User.builder().id("u-1").set("internal_note", "secret").build())
                .chat(Chat.builder().id("c1").set("secret_flag", "leak").build())
                .build());

        assertFalse(url.contains("internal_note"));
        assertFalse(url.contains("secret"));
        assertFalse(url.contains("leak"));
    }

    @Test
    @DisplayName("every chat type in openapi.yml has an enum constant")
    void chatTypesMatchSpec() {
        assertEquals(
                List.of("private", "group", "supergroup", "channel"),
                Arrays.stream(Chat.Type.values()).map(Chat.Type::wire).toList());
    }

    @Test
    @DisplayName("rights outside the scheme are dropped")
    void unknownRightsDropped() {
        String url = sdk().url(UrlOptions.builder()
                .user(User.builder()
                        .id("u-1")
                        .rights(Rights.builder()
                                .set("bogus_right", true)
                                .sendMessages(true)
                                .build())
                        .build())
                .build());

        assertFalse(url.contains("bogus_right"));
        assertTrue(url.contains("send_messages"));
    }

    @Test
    @DisplayName("changing any signed input changes the signature")
    void signatureCoversInputs() {
        GetChatUrlSigner sdk = GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl("https://chat.example.com/embed")
                .randomStringSupplier(len -> "x".repeat(len)) // pin the nonce
                .build();

        String base = sdk.url(UrlOptions.builder()
                .user(User.builder().id("u-1").name("Alice").build())
                .build());
        String renamed = sdk.url(UrlOptions.builder()
                .user(User.builder().id("u-1").name("Mallory").build())
                .build());

        assertNotEquals(query(base).get("signature"), query(renamed).get("signature"));
    }

    @Test
    @DisplayName("extra params sit outside the signature")
    void extraIsNotSigned() {
        GetChatUrlSigner sdk = GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl("https://chat.example.com/embed")
                .randomStringSupplier(len -> "x".repeat(len))
                .build();

        String plain = sdk.url(
                UrlOptions.builder().user(User.builder().id("u-1").build()).build());
        String decorated = sdk.url(UrlOptions.builder()
                .user(User.builder().id("u-1").build())
                .extra("theme", "dark")
                .build());

        assertEquals(query(plain).get("signature"), query(decorated).get("signature"));
    }

    // Validation moved to build(): a missing field is caught before the signer
    // exists at all, so url() can never fire half-configured. Each case names the
    // credential it omits and the message fragment build() must surface.
    static Stream<Arguments> missingCredentials() {
        return Stream.of(
                arguments(
                        "client id",
                        (Executable) () -> GetChatUrlSigner.builder()
                                .secret("s")
                                .baseUrl("https://x")
                                .build(),
                        "client id is required"),
                arguments(
                        "client secret",
                        (Executable) () -> GetChatUrlSigner.builder()
                                .clientId("i")
                                .baseUrl("https://x")
                                .build(),
                        "client secret is required"),
                arguments(
                        "base url",
                        (Executable) () -> GetChatUrlSigner.builder()
                                .clientId("i")
                                .secret("s")
                                .build(),
                        "base url is required"));
    }

    @ParameterizedTest(name = "missing {0} fails build()")
    @MethodSource("missingCredentials")
    @DisplayName("a signer cannot be built without credentials — build() fails loudly")
    void requiresCredentials(String label, Executable build, String expectedMessage) {
        assertTrue(assertThrows(GetChatException.class, build).getMessage().contains(expectedMessage));
    }

    // Blank (whitespace or empty) values are rejected the same way as missing ones.
    static Stream<Arguments> blankCredentials() {
        return Stream.of(
                arguments("blank client id", (Executable) () -> GetChatUrlSigner.builder()
                        .clientId("  ")
                        .secret("s")
                        .baseUrl("https://x")
                        .build()),
                arguments("empty client secret", (Executable) () -> GetChatUrlSigner.builder()
                        .clientId("i")
                        .secret("")
                        .baseUrl("https://x")
                        .build()),
                arguments("blank base url", (Executable) () -> GetChatUrlSigner.builder()
                        .clientId("i")
                        .secret("s")
                        .baseUrl("   ")
                        .build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankCredentials")
    @DisplayName("blank credentials are rejected the same way as missing ones")
    void rejectsBlankCredentials(String label, Executable build) {
        assertThrows(GetChatException.class, build);
    }

    @Test
    @DisplayName("the legacy builder insists on a chat id")
    void legacyRequiresChatId() {
        GetChatUrlSigner sdk = sdk();
        User user = User.of("u-1");

        assertThrows(
                GetChatException.class, () -> sdk.urlByChatId(Chat.builder().build(), user));
        assertThrows(GetChatException.class, () -> sdk.urlByChatId((Chat) null, user));
    }

    @Test
    @DisplayName("UrlOptions requires a user")
    void requiresUser() {
        // Input validation throws the SDK's own catchable type, not a raw
        // IllegalArgumentException.
        assertThrows(
                GetChatException.class, () -> UrlOptions.builder().chat("c1").build());
    }

    @Test
    @DisplayName("baseUrl(URI) is equivalent to baseUrl(String)")
    void baseUrlUriOverload() {
        // A pinned nonce/session makes the two URLs directly comparable.
        UrlOptions options = UrlOptions.builder()
                .user(User.builder().id("u-1").name("Alice").build())
                .build();

        String fromString = GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl("https://chat.example.com/embed")
                .randomStringSupplier(len -> "x".repeat(len))
                .build()
                .url(options);

        String fromUri = GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl(java.net.URI.create("https://chat.example.com/embed"))
                .randomStringSupplier(len -> "x".repeat(len))
                .build()
                .url(options);

        assertEquals(fromString, fromUri);
    }

    @Test
    @DisplayName("trailing slashes on baseUrl are stripped")
    void stripsTrailingSlashes() {
        GetChatUrlSigner sdk = GetChatUrlSigner.builder()
                .clientId("client-42")
                .secret("s3cr3t-key")
                .baseUrl("https://chat.example.com/embed///")
                .build();

        String url = sdk.url(UrlOptions.builder().user(User.of("u-1")).build());

        assertTrue(url.startsWith("https://chat.example.com/embed?"), url);
    }

    @Test
    @DisplayName("participants keep the order they were added")
    void participantOrderPreserved() {
        String url = sdk().url(UrlOptions.builder()
                .user(User.of("u-1"))
                .participants(List.of(Recipient.of("a", "A"), Recipient.of("b", "B")))
                .participant(Recipient.of("c", "C"))
                .build());

        Map<String, String> q = query(url);
        assertAll(
                () -> assertEquals("a", q.get("recipients[0][id]")),
                () -> assertEquals("b", q.get("recipients[1][id]")),
                () -> assertEquals("c", q.get("recipients[2][id]")));
    }
}
