package dev.getchat.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Additive hardening of the {@code equals}/{@code hashCode} surface with
 * EqualsVerifier: it exhaustively checks reflexivity, symmetry, transitivity,
 * consistency and the {@code hashCode} agreement for every value type that fits
 * the tool cleanly. This complements — does not replace — the hand-written,
 * intent-revealing assertions in {@link ValueSemanticsTest} and the per-type
 * value-semantics tests in {@link RequestBuildersTest} / {@link ResponseModelsTest},
 * which pin the specific semantics EqualsVerifier does not express (e.g. that
 * {@link Rights} equality is order-independent).
 *
 * <p>These tests live in-package so the prefab {@link JsonValue} instances can be
 * built through the package-private {@link JsonValue#wrap} seam, exactly as the
 * client does.
 */
class EqualsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Two distinct, non-equal JSON documents used as red/blue prefab values. The
    // read models and Page hold a JsonValue and delegate equals/hashCode to it, so
    // EqualsVerifier needs two unequal JsonValue instances to exercise the contract;
    // JsonValue itself wraps an (abstract) Jackson JsonNode, so its own test needs
    // the underlying node prefabs too.
    private static final JsonNode RED_NODE = node("{\"id\":\"red\",\"n\":1}");
    private static final JsonNode BLUE_NODE = node("{\"id\":\"blue\",\"n\":2}");
    private static final JsonValue RED_JSON = JsonValue.wrap(RED_NODE);
    private static final JsonValue BLUE_JSON = JsonValue.wrap(BLUE_NODE);

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("bad prefab JSON: " + json, e);
        }
    }

    /**
     * Plain value types whose {@code equals}/{@code hashCode} read ordinary
     * fields (primitives, boxed numbers, {@link java.time.Duration}, enums,
     * strings and {@code Map}/{@code List} of those). Each is a {@code final}
     * class with {@code final} fields and no lazily-derived state, so
     * EqualsVerifier verifies them with no extra configuration.
     */
    @ParameterizedTest
    @ValueSource(classes = {
        RequestOptions.class,
        RequestControl.class,
        PageQuery.class,
        CreateChatOptions.class,
        UpdateChatOptions.class,
        CreateUserOptions.class,
        UpdateUserOptions.class,
        User.class,
        Chat.class,
        Recipient.class,
        Rights.class,
        MessagesQuery.class,
        ChatsQuery.class,
        Button.class,
        SendMessageOptions.class,
        UpdateMessageOptions.class,
        UrlOptions.class,
        ApiRequest.class,
    })
    @DisplayName("plain value types satisfy the equals/hashCode contract")
    void plainValueTypes(Class<?> type) {
        EqualsVerifier.forClass(type).verify();
    }

    /**
     * The lazy read models: each wraps a single {@link JsonValue} {@code raw}
     * field and defines {@code equals}/{@code hashCode} purely in terms of it, so
     * they need the JsonValue prefab values but nothing else.
     */
    @ParameterizedTest
    @ValueSource(classes = {
        ChatDetails.class,
        Message.class,
        UserDetails.class,
        Participant.class,
        Avatar.class,
        ButtonDetails.class,
        SentMessages.class,
        UpdatedMessage.class,
    })
    @DisplayName("raw-backed read models satisfy the equals/hashCode contract")
    void rawBackedReadModels(Class<?> type) {
        EqualsVerifier.forClass(type)
                .withPrefabValues(JsonValue.class, RED_JSON, BLUE_JSON)
                .verify();
    }

    @Test
    @DisplayName("JsonValue equals/hashCode delegate to the wrapped JsonNode")
    void jsonValueContract() {
        EqualsVerifier.forClass(JsonValue.class)
                // JsonNode is abstract; EqualsVerifier cannot instantiate it, so
                // supply two concrete, unequal parsed nodes as the red/blue values.
                .withPrefabValues(JsonNode.class, RED_NODE, BLUE_NODE)
                .verify();
    }

    @Test
    @DisplayName("Page equals/hashCode use only the raw envelope, not the derived item list")
    void pageContract() {
        EqualsVerifier.forClass(Page.class)
                .withPrefabValues(JsonValue.class, RED_JSON, BLUE_JSON)
                // Page carries a second field, `items`, memoized from `raw` at
                // construction; equals/hashCode intentionally use only `raw` (two
                // pages built from equal envelopes are equal, and the item list is a
                // pure function of the envelope). Tell EqualsVerifier that the
                // derived field is deliberately excluded.
                .suppress(Warning.ALL_FIELDS_SHOULD_BE_USED)
                .verify();
    }
}
