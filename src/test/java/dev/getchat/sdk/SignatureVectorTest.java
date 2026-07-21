package dev.getchat.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Cross-language conformance: every URL in {@code signature-vectors.json} was
 * produced by the node SDK, and this SDK has to reproduce it character for
 * character — same field order, same escaping, same signature.
 *
 * <p>Regenerate the fixture from the node SDK repo (see CLAUDE.md, "Golden
 * signature vectors"). A diff here means the two SDKs have drifted apart and one
 * of them no longer verifies against the backend.
 */
class SignatureVectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLIENT_ID = "client-42";
    private static final String CLIENT_SECRET = "s3cr3t-key";
    private static final String BASE_URL = "https://chat.example.com/embed";

    @TestFactory
    Iterable<DynamicTest> matchesNodeSdk() throws Exception {
        JsonNode vectors;
        try (InputStream in = getClass().getResourceAsStream("/signature-vectors.json")) {
            vectors = MAPPER.readTree(in);
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : vectors) {
            String name = vector.get("name").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> runVector(vector)));
        }
        return tests;
    }

    private void runVector(JsonNode vector) {
        String expected = vector.get("url").asText();
        Map<String, String> query = parseQuery(expected);

        // The nonce and (for anonymous users) the session are random, so replay
        // the values the node SDK happened to generate. `session` is requested
        // first, during user normalisation, and is 40 chars; the nonce is 32.
        String nonce = query.get("nonce");
        String session = query.get("user[session]");

        GetChat sdk = new GetChat(GetChatConfig.builder()
                .id(CLIENT_ID)
                .secret(CLIENT_SECRET)
                .baseUrl(BASE_URL)
                .randomStringSupplier(len -> len == 40 ? session : nonce)
                .build());

        JsonNode args = vector.get("args");
        String actual = vector.get("method").asText().equals("url")
                ? sdk.url(urlOptions(args))
                : sdk.urlByChatId(
                        chat(args.get(0)),
                        user(args.size() > 1 ? args.get(1) : null),
                        recipients(args.size() > 2 ? args.get(2) : null),
                        extra(args.size() > 3 ? args.get(3) : null));

        assertEquals(expected, actual, "URL differs from the node SDK output");
    }

    // ── building SDK inputs from the recorded JSON args ──────────────────────

    private static UrlOptions urlOptions(JsonNode args) {
        UrlOptions.Builder builder = UrlOptions.builder().user(user(args.get("user")));

        JsonNode chat = args.get("chat");
        if (chat != null && !chat.isNull()) {
            builder.chat(chat(chat));
        }
        builder.participants(recipients(args.get("participants")));
        builder.extra(extra(args.get("extra")));

        return builder.build();
    }

    private static Chat chat(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return Chat.of(node.asText());
        }
        Chat.Builder builder = Chat.builder();
        fields(node).forEach(builder::set);
        return builder.build();
    }

    private static User user(JsonNode node) {
        User.Builder builder = User.builder();
        if (node != null && !node.isNull()) {
            fields(node).forEach(builder::set);
        }
        return builder.build();
    }

    private static List<Recipient> recipients(JsonNode node) {
        List<Recipient> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        for (JsonNode item : node) {
            Recipient.Builder builder = Recipient.builder();
            fields(item).forEach(builder::set);
            out.add(builder.build());
        }
        return out;
    }

    private static Map<String, Object> extra(JsonNode node) {
        return node == null || node.isNull() ? Map.of() : fields(node);
    }

    /** Object fields as an insertion-ordered map of plain Java values. */
    private static Map<String, Object> fields(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            out.put(entry.getKey(), value(entry.getValue()));
        }
        return out;
    }

    private static Object value(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(value(item)));
            return list;
        }
        return fields(node);
    }

    /** Decode a query string into {@code user[session]}-style flat keys. */
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> out = new LinkedHashMap<>();
        int mark = url.indexOf('?');
        if (mark < 0) {
            return out;
        }
        for (String pair : url.substring(mark + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
