# GetChat Java SDK

Server-side Java SDK for [GetChat](https://getchat.dev). Two jobs:

1. Generate **signed chat URLs** for embedding the chat UI in an iframe or WebView.
2. Wrap the **GetChat REST API** with `Bearer` token auth.

Java 17+. Its single runtime dependency, Jackson, is used only internally — REST
responses come back as the SDK's own `JsonValue`, so no `com.fasterxml.jackson`
type appears on the public API. (JSpecify ships alongside it but is annotations
only, no runtime code.)

> Status: early. URL signing is stable and covered by a byte-exact conformance
> suite; the REST layer covers the common chat/message/user/participant
> endpoints, not yet the full API surface.

## Install

Not published yet. Build and install locally:

```bash
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("dev.getchat:getchat-java-sdk:0.2.0-SNAPSHOT")
}
```

## Setup

```java
GetChat sdk = new GetChat(GetChatConfig.builder()
        .id("your-client-id")          // URL signing
        .secret("your-client-secret")  // URL signing
        .apiToken("your-api-token")    // REST API
        .baseUrl("https://chat.example.com/embed")
        .build());
```

`apiUrl` defaults to `baseUrl`; set it separately when the REST API lives on a
different host. An instance that only signs URLs can leave `apiToken` unset, and
vice versa.

## Signed embed URLs

```java
String url = sdk.url(UrlOptions.builder()
        .chat(Chat.builder().id("support-42").title("Support").create(true).build())
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
```

A user with no `id` is treated as anonymous and gets a random 40-character
session token, so each browser stays distinguishable across page loads.

`extra` values are appended after the signature and are **not signed** — treat
them as presentation hints, never as authorisation.

### Legacy URLs

`urlByChatId` produces the older MD5-signed URL. The backend verifies it under a
separate scheme, so the two are not interchangeable — prefer `url` for anything
new.

```java
String legacy = sdk.urlByChatId("support-42", User.builder().id("u-1").name("Alice").build());
```

It also accepts the same `UrlOptions` as `url` when you need participants or
extra query params — a `chat` is required here (the legacy scheme signs by chat
id):

```java
String legacy = sdk.urlByChatId(UrlOptions.builder()
        .chat("support-42")
        .user(User.builder().id("u-1").name("Alice").build())
        .participant(Recipient.of("u-2", "Bob"))
        .build());
```

## REST API

Every method returns a `JsonValue` — the SDK's own immutable, null-safe view over
the JSON response. Jackson does the parsing underneath, but no Jackson type
crosses the API boundary.

```java
JsonValue chats = sdk.listChats(ChatsQuery.builder().page(1).limit(20).withOwners(true).build());
JsonValue chat  = sdk.getChat("support-42");

sdk.createChat(
        Chat.builder().id("support-42").title("Support").type(Chat.Type.GROUP).build(),
        List.of(Recipient.of("u-1", "Alice")));

sdk.sendMessage(Chat.of("support-42"), User.builder().id("u-1").name("Alice").build(), "Hello");
sdk.sendTyping("support-42", "u-1", 5);

sdk.deleteMessage("support-42", "m-1");

sdk.createUser(User.builder().id("u-3").name("Carol").build());
sdk.listUserChats("u-3", PageQuery.builder().page(1).limit(20).build());
```

### Reading a `JsonValue`

Navigation is chain-safe: `get(...)` never throws, and a step that does not
resolve collapses to the **missing** sentinel rather than `null`, so a deep
lookup on absent data just falls through to your default. `at(...)` follows the
same rule for a valid JSON Pointer that does not resolve, but a *syntactically
invalid* pointer (a non-empty string without a leading `/`) is a programming
error and throws `GetChatException`.

```java
JsonValue chat = sdk.getChat("support-42");

String title = chat.get("data").get("title").asString("(untitled)");   // lenient, with default
long   count = chat.get("data").get("messages_count").asLong(0);

// A path that does not exist collapses to a default, never an NPE:
String missing = chat.get("nope").get("still nope").asString("fallback");

// JSON Pointer (RFC 6901) reaches deep in one step:
String first = chat.at("/data/participants/0/name").asString("");

// Iterate arrays; values() is empty (never null) for a non-array:
for (JsonValue m : sdk.listMessages("support-42").get("data").values()) {
    System.out.println(m.get("text").asString(""));
}
```

The `as*(default)` accessors are lenient and coerce scalars; the no-argument
`asString()` is strict and throws `GetChatException` when the value is not a
present JSON string. Predicates (`isMissing()`, `isNull()`, `isObject()`, …) and
`toMap()` / `toList()` (plain JDK collections, no Jackson) round out the surface.

### Typed request builders

Filters, edits and updates take typed builders. Each builder has a
`set(key, value)` escape hatch for a field without a typed setter, so nothing is
lost by dropping the old raw-`Map` overloads:

```java
// List chats with typed filters:
JsonValue groups = sdk.listChats(ChatsQuery.builder()
        .page(1).limit(20)
        .type(Chat.Type.GROUP)
        .withOwners(true)
        .build());

// Read messages (drops the with_users vs withUsers footgun); page/limit ride the query:
JsonValue messages = sdk.listMessages("support-42",
        MessagesQuery.builder().withUsers(true).deleted(false).page(1).limit(50).build());

// Edit a message: text plus an options object (replaces the old trailing booleans):
sdk.updateMessage("support-42", "m-1", "Edited", UpdateMessageOptions.builder()
        .extra(Map.of("edited_by", "bot"))
        .extraMode(UpdateMessageOptions.ExtraMode.REPLACE)   // default is MERGE
        .returnMessage(true)
        .build());

// The short overload keeps working — text only, merge extra, nothing returned:
sdk.updateMessage("support-42", "m-1", "Edited");

// Participants (to seed a new chat), extra and interactive buttons ride on a
// SendMessageOptions object (replaces the old six-argument sendMessage):
sdk.sendMessage(Chat.of("support-42"),
        User.builder().id("u-1").name("Alice").build(),
        "Pick one",
        SendMessageOptions.builder()
                .buttons(Button.of(Button.Type.URL, "Open"),
                        Button.builder().type(Button.Type.LOCAL).label("Dismiss")
                                .style(Button.Style.NEGATIVE).build())
                .build());
```

`listParticipants` and `listUserChats` take a `PageQuery` the same way
(`PageQuery.builder().page(2).limit(50).build()`); the no-query overloads of the
list methods default to page 1, 50 per page.

`updateChat` and `updateUser` take a typed builder:
`sdk.updateChat("support-42", Chat.builder().title("Renamed").build())`. For a
field without a typed setter, use the builder's `set(key, value)`; for an
endpoint the SDK does not wrap, use `requestApi(ApiRequest)` (below).

Anything not yet wrapped — the participant-rights endpoints, for example — can
go through the transport directly. Describe the call
with an `ApiRequest` — a verb factory (`get`/`post`/`put`/`delete`) plus a builder
for the body, query, headers and version:

```java
// GET an endpoint the SDK does not wrap yet:
JsonValue hooks = sdk.requestApi(ApiRequest.get("chats/support-42/webhooks")
        .query("with_disabled", 1)
        .build());

// PUT with a JSON body, a URL query param and a custom header:
JsonValue result = sdk.requestApi(ApiRequest.put("chats/support-42/webhook")
        .body(Map.of("url", "https://example.com/hook"))
        .query("dry_run", 1)
        .header("Prefer", "return=representation")
        .build());
```

For `GET`/`DELETE` the `query` is the URL query string; for `POST`/`PUT` the
`body` is the JSON payload and `query` the URL query string. Setting a `body` on a
`GET`/`DELETE` is rejected at `build()` rather than silently dropped.

## Errors

| Exception | When |
| --- | --- |
| `GetChatApiException` | non-2xx/3xx response; carries `status()`, `body()`, `rawBody()` |
| `GetChatTimeoutException` | an attempt exceeded its timeout |
| `GetChatException` | bad input, transport failure, serialisation failure |

All are unchecked and share `GetChatException` as a base. Every deliberate input
check throws `GetChatException` (e.g. a missing chat id, empty message text, or
absent signing credentials); a raw `NullPointerException` is reserved for
null-contract violations — passing `null` where a required argument such as the
config is expected, surfaced via `Objects.requireNonNull`.

`GetChatApiException.body()` returns a `JsonValue`: the parsed error payload when
the response was JSON, or the **missing** sentinel (`body().isMissing()` is
`true`) when the body was not JSON or could not be parsed. `rawBody()` always
holds the response text exactly as received.

```java
try {
    sdk.getChat("does-not-exist");
} catch (GetChatApiException e) {
    int status = e.status();                              // e.g. 404
    String code = e.body().get("error").asString("");    // chain-safe on any body
}
```

## Timeouts and retries

Defaults: 30s per attempt, 2 retries, 200ms base backoff with jitter.

`timeout` and `retryDelay` take a `java.time.Duration`; `Duration.ZERO` disables
the per-attempt timeout (no deadline). `retries` stays an `int`.

```java
import java.time.Duration;

GetChat sdk = new GetChat(GetChatConfig.builder()
        .apiToken("...")
        .baseUrl("...")
        .options(RequestOptions.builder().timeout(Duration.ofSeconds(5)).retries(3).build())
        .build());

// or per call — a null override leaves that field on the instance default
sdk.listChats(
        ChatsQuery.builder().limit(20).build(),
        RequestControl.builder().timeout(Duration.ofSeconds(1)).retries(0).build());
```

Retries follow idempotency: `GET`/`DELETE` retry on network errors, 5xx and 429;
`POST`/`PUT` retry only on 429 and on connection failures that prove the request
never reached the server, so a write is never silently duplicated. `Retry-After`
is honoured, capped at 30s.

Because a timeout counts as a transport error, a hung read can take up to
roughly `(retries + 1) × timeout` plus backoff before it gives up.

## Thread safety and lifecycle

`GetChat` is immutable and safe to share. It creates one `HttpClient` per instance,
so build one client and reuse it rather than constructing per request. Supply
your own via `GetChatConfig.builder().httpClient(...)` for proxies or custom TLS.

Most applications build one long-lived instance and never close it. `GetChat`
does implement `AutoCloseable` for the short-lived case:

```java
try (GetChat sdk = new GetChat(config)) {
    sdk.getChat("support-42");
}
```

`close()` releases **only** an `HttpClient` the SDK created itself; a client you
passed in via `httpClient(...)` is left open, since its lifecycle is yours. It is
idempotent and never throws. `HttpClient` became `AutoCloseable` only in JDK 21,
so on JDK 17–20 `close()` is a no-op and the client's resources are reclaimed by
the garbage collector instead.

`GetChatConfig` and the value types (`User`, `Chat`, `Button`, the query builders,
…) implement `equals`/`hashCode`/`toString`. `GetChatConfig.toString()` redacts
`secret` and `apiToken` (they render as `***`), so a config is safe to log.

## Development

```bash
./gradlew build    # compile, test, jar, javadoc
./gradlew test     # tests only
./gradlew test --tests '*SignatureVectorTest*'
```

See `CLAUDE.md` for architecture and the rules around changing signing code.

## License

MIT — see [LICENSE](LICENSE).
