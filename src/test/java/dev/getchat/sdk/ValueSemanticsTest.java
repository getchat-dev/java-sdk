package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Value semantics for the stage-4 types: {@code equals}/{@code hashCode} by
 * content, informative {@code toString}, and the security-critical redaction of
 * secrets in the two entry points' {@code toString()} plus the build()-time
 * validation that keeps a half-configured entry point from existing.
 */
class ValueSemanticsTest {

    @Nested
    @DisplayName("equality by content")
    class Equality {

        @Test
        @DisplayName("two identically built Users are equal with equal hashCode")
        void usersEqual() {
            User a = User.builder().id("10").name("Fred").build();
            User b = User.builder().id("10").name("Fred").build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Users with different data are not equal")
        void usersUnequal() {
            User a = User.builder().id("10").name("Fred").build();
            User c = User.builder().id("11").name("Fred").build();

            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("two identically built Chats are equal with equal hashCode")
        void chatsEqual() {
            Chat a = Chat.builder().id("support-42").title("Help").build();
            Chat b = Chat.builder().id("support-42").title("Help").build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, Chat.of("other"));
        }

        @Test
        @DisplayName("two identically built RequestOptions are equal with equal hashCode")
        void requestOptionsEqual() {
            RequestOptions a = RequestOptions.builder()
                    .timeout(Duration.ofMillis(5_000))
                    .retries(1)
                    .retryDelay(Duration.ofMillis(100))
                    .build();
            RequestOptions b = RequestOptions.builder()
                    .timeout(Duration.ofMillis(5_000))
                    .retries(1)
                    .retryDelay(Duration.ofMillis(100))
                    .build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(
                    a,
                    RequestOptions.builder()
                            .timeout(Duration.ofMillis(5_000))
                            .retries(2)
                            .retryDelay(Duration.ofMillis(100))
                            .build());
        }

        @Test
        @DisplayName("Rights compare by entries (map content), not insertion order")
        void rightsEqualByContent() {
            Rights a = Rights.builder().sendMessages(true).reactMessages(false).build();
            Rights b = Rights.builder().reactMessages(false).sendMessages(true).build();

            // Backed by a Map, so equality is by entry set — the differing insertion
            // order still yields two objects carrying the same rights.
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("RequestControl equality accounts for unset (null) overrides")
        void requestControlEquality() {
            RequestControl a = RequestControl.builder().timeout(Duration.ofMillis(5_000)).build();
            RequestControl b = RequestControl.builder().timeout(Duration.ofMillis(5_000)).build();
            RequestControl c = RequestControl.builder().timeout(Duration.ofMillis(5_000)).retries(0).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }
    }

    @Nested
    @DisplayName("equals contract")
    class Contract {

        @Test
        @DisplayName("nothing equals null")
        void notEqualToNull() {
            assertNotEquals(User.of("1"), null);
            assertNotEquals(Chat.of("1"), null);
            assertNotEquals(RequestOptions.defaults(), null);
        }

        @Test
        @DisplayName("nothing equals a foreign type")
        void notEqualToForeignType() {
            assertNotEquals(User.of("1"), "not a user");
            assertNotEquals(RequestOptions.defaults(), 42);
        }

        @Test
        @DisplayName("equals is reflexive")
        void reflexive() {
            User a = User.builder().id("1").name("x").build();
            assertEquals(a, a);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("User.toString carries the key fields")
        void userToString() {
            String s = User.builder().id("10").name("Fred").build().toString();

            assertTrue(s.startsWith("User{"), s);
            assertTrue(s.contains("id=10"), s);
            assertTrue(s.contains("name=Fred"), s);
        }

        @Test
        @DisplayName("RequestOptions.toString carries the tuning fields")
        void requestOptionsToString() {
            String s = RequestOptions.builder()
                    .timeout(Duration.ofMillis(5_000))
                    .retries(1)
                    .retryDelay(Duration.ofMillis(100))
                    .build()
                    .toString();

            assertTrue(s.contains("timeout=5000"), s);
            assertTrue(s.contains("retries=1"), s);
            assertTrue(s.contains("retryDelay=100"), s);
        }
    }

    @Nested
    @DisplayName("the entry-point toStrings redact secrets")
    class SecretRedaction {

        private static final String SECRET = "super-secret-signing-key";
        private static final String API_TOKEN = "tok_live_abc123def456";

        @Test
        @DisplayName("GetChatUrlSigner.toString never prints the client secret")
        void signerRedactsSecret() {
            String s = GetChatUrlSigner.builder()
                    .clientId("client-1")
                    .secret(SECRET)
                    .baseUrl("https://chat.example.com")
                    .build()
                    .toString();

            // The load-bearing assertion: the raw secret material is absent.
            assertFalse(s.contains(SECRET), "secret value leaked: " + s);
            assertTrue(s.contains("secret=***"), s);

            // Non-secret fields stay visible for diagnostics.
            assertTrue(s.contains("clientId=client-1"), s);
            assertTrue(s.contains("baseUrl=https://chat.example.com"), s);
            // The nonce/session hook shows only its presence, not its identity.
            assertTrue(s.contains("randomStringSupplier=unset"), s);
        }

        @Test
        @DisplayName("GetChatClient.toString never prints the api token")
        void clientRedactsToken() {
            String s = GetChatClient.builder()
                    .apiUrl("https://chat.example.com")
                    .apiToken(API_TOKEN)
                    .build()
                    .toString();

            assertFalse(s.contains(API_TOKEN), "apiToken value leaked: " + s);
            assertTrue(s.contains("apiToken=***"), s);

            // Non-secret fields stay visible for diagnostics.
            assertTrue(s.contains("apiUrl=https://chat.example.com"), s);
            // The http client shows only its presence, not its identity.
            assertTrue(s.contains("httpClient=unset"), s);
        }
    }

    @Nested
    @DisplayName("build() rejects a half-configured entry point")
    class BuildValidation {

        @Test
        @DisplayName("GetChatUrlSigner.build() needs client id, secret and base url")
        void signerRequiresAll() {
            assertThrows(GetChatException.class,
                    () -> GetChatUrlSigner.builder().secret("s").baseUrl("https://x").build());
            assertThrows(GetChatException.class,
                    () -> GetChatUrlSigner.builder().clientId("c").baseUrl("https://x").build());
            assertThrows(GetChatException.class,
                    () -> GetChatUrlSigner.builder().clientId("c").secret("s").build());
        }

        @Test
        @DisplayName("GetChatClient.build() needs api url and api token")
        void clientRequiresBoth() {
            assertThrows(GetChatException.class,
                    () -> GetChatClient.builder().apiToken("t").build());
            assertThrows(GetChatException.class,
                    () -> GetChatClient.builder().apiUrl("https://x").build());
        }

        @Test
        @DisplayName("blank values are rejected just like missing ones")
        void blankIsRejected() {
            assertThrows(GetChatException.class,
                    () -> GetChatUrlSigner.builder().clientId(" ").secret("s").baseUrl("https://x").build());
            assertThrows(GetChatException.class,
                    () -> GetChatClient.builder().apiUrl("   ").apiToken("t").build());
            assertThrows(GetChatException.class,
                    () -> GetChatClient.builder().apiUrl("https://x").apiToken("").build());
        }
    }

    @Nested
    @DisplayName("hashCode stability")
    class HashCodeStability {

        @Test
        @DisplayName("hashCode is stable across repeated calls")
        void stable() {
            User user = User.builder().id("1").name("x").build();

            assertEquals(user.hashCode(), user.hashCode());
        }
    }
}
