package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Value semantics for the stage-4 types: {@code equals}/{@code hashCode} by
 * content, informative {@code toString}, and the security-critical redaction of
 * secrets in {@link GetChatConfig#toString()}.
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
            RequestOptions a = RequestOptions.builder().timeout(5_000).retries(1).retryDelay(100).build();
            RequestOptions b = RequestOptions.builder().timeout(5_000).retries(1).retryDelay(100).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, RequestOptions.builder().timeout(5_000).retries(2).retryDelay(100).build());
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
            RequestControl a = RequestControl.builder().timeout(5_000).build();
            RequestControl b = RequestControl.builder().timeout(5_000).build();
            RequestControl c = RequestControl.builder().timeout(5_000).retries(0).build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("two identically built GetChatConfigs are equal with equal hashCode")
        void configEqual() {
            GetChatConfig a = GetChatConfig.builder().id("c").secret("s").apiToken("t").baseUrl("https://x").build();
            GetChatConfig b = GetChatConfig.builder().id("c").secret("s").apiToken("t").baseUrl("https://x").build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
            assertNotEquals(a, GetChatConfig.builder().id("c").secret("other").build());
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
            assertNotEquals(GetChatConfig.builder().id("c").build(), null);
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
            String s = RequestOptions.builder().timeout(5_000).retries(1).retryDelay(100).build().toString();

            assertTrue(s.contains("timeout=5000"), s);
            assertTrue(s.contains("retries=1"), s);
            assertTrue(s.contains("retryDelay=100"), s);
        }
    }

    @Nested
    @DisplayName("GetChatConfig.toString redacts secrets")
    class SecretRedaction {

        private static final String SECRET = "super-secret-signing-key";
        private static final String API_TOKEN = "tok_live_abc123def456";

        @Test
        @DisplayName("a set secret and apiToken never appear in the clear")
        void secretsRedactedWhenSet() {
            GetChatConfig config = GetChatConfig.builder()
                    .id("client-1")
                    .secret(SECRET)
                    .apiToken(API_TOKEN)
                    .baseUrl("https://chat.example.com")
                    .build();

            String s = config.toString();

            // The load-bearing assertions: the raw secret material is absent.
            assertFalse(s.contains(SECRET), "secret value leaked: " + s);
            assertFalse(s.contains(API_TOKEN), "apiToken value leaked: " + s);

            // ...but the fields are acknowledged as redacted.
            assertTrue(s.contains("secret=***"), s);
            assertTrue(s.contains("apiToken=***"), s);

            // Non-secret fields stay visible for diagnostics.
            assertTrue(s.contains("id=client-1"), s);
            assertTrue(s.contains("baseUrl=https://chat.example.com"), s);
        }

        @Test
        @DisplayName("an unset secret and apiToken render as null, not ***")
        void secretsShownNullWhenUnset() {
            String s = GetChatConfig.builder().id("client-1").build().toString();

            assertTrue(s.contains("secret=null"), s);
            assertTrue(s.contains("apiToken=null"), s);
            assertFalse(s.contains("secret=***"), s);
            assertFalse(s.contains("apiToken=***"), s);
        }

        @Test
        @DisplayName("the opaque collaborators show only set/unset, never their identity")
        void collaboratorsShownAsPresence() {
            String s = GetChatConfig.builder().id("client-1").build().toString();

            assertTrue(s.contains("httpClient=unset"), s);
            assertTrue(s.contains("randomStringSupplier=unset"), s);
        }
    }

    @Nested
    @DisplayName("hashCode stability")
    class HashCodeStability {

        @Test
        @DisplayName("hashCode is stable across repeated calls")
        void stable() {
            User user = User.builder().id("1").name("x").build();
            GetChatConfig config = GetChatConfig.builder().id("c").secret("s").build();

            assertEquals(user.hashCode(), user.hashCode());
            assertEquals(config.hashCode(), config.hashCode());
        }
    }
}
