# GetChat Java SDK

Server-side Java SDK for [GetChat](https://getchat.dev). It does two things:

1. Builds **signed chat URLs** so you can drop the chat UI into an iframe or WebView.
2. Wraps the **GetChat REST API**, authenticating with a `Bearer` token.

Java 17 or newer. Its one runtime dependency, Jackson, is used only inside the
SDK: chat and message reads come back as typed objects (`ChatDetails`,
`Message`, `Page<T>`, …) and everything else as the SDK's own `JsonValue`, so no
`com.fasterxml.jackson` type ever appears in the public API. (JSpecify ships
alongside it but is annotations only — no runtime code.)

> Status: early. URL signing is stable and covered by a conformance test suite.
> The REST layer covers the common chat, message, user and participant
> endpoints; for anything else, call the API directly (see
> [Calling endpoints the SDK does not wrap](#calling-endpoints-the-sdk-does-not-wrap)).

## Install

Not published to Maven Central yet. Build and install it into your local Maven
repository:

```bash
./gradlew publishToMavenLocal
```

```kotlin
dependencies {
    implementation("dev.getchat:getchat-java-sdk:0.3.0-SNAPSHOT")
}
```

## Setup

Create one `GetChat` instance and reuse it — it is safe to share across threads.

```java
GetChat sdk = new GetChat(GetChatConfig.builder()
        .id("your-client-id")          // needed to sign URLs
        .secret("your-client-secret")  // needed to sign URLs
        .apiToken("your-api-token")    // needed for the REST API
        .baseUrl("https://chat.example.com/embed")
        .build());
```

- Signing URLs needs `id` and `secret`; the REST API needs `apiToken`. An
  instance that only signs URLs can leave `apiToken` unset, and one that only
  calls the API can leave `id`/`secret` unset.
- `apiUrl` is where REST calls go; it defaults to `baseUrl`. Set it separately
  when the API lives on a different host.

## Signed embed URLs

`url(...)` builds the current, recommended URL. Pass a user (required) and,
usually, the chat to open:

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

- A user with no `id` is treated as anonymous and gets a random 40-character
  session token, so each browser stays distinguishable across page loads.
- `extra` values are added after the signature and are **not signed** — treat
  them as display hints, never as permissions.

### Legacy URLs

`urlByChatId(...)` builds an older URL that the backend checks with a different,
separate scheme, so the two are not interchangeable — prefer `url(...)` for new
work. It always needs a chat (its id is what the older scheme signs by). There
are three forms:

```java
// Just a chat id and a user:
String a = sdk.urlByChatId("support-42", User.of("u-1"));

// A Chat object and a user:
String b = sdk.urlByChatId(Chat.builder().id("support-42").title("Support").build(), User.of("u-1"));

// Full options, when you also need participants or extra query params:
String c = sdk.urlByChatId(UrlOptions.builder()
        .chat("support-42")
        .user(User.builder().id("u-1").name("Alice").build())
        .participant(Recipient.of("u-2", "Bob"))
        .build());
```

## REST API

Read methods return typed objects — `ChatDetails`, `Message`, `UserDetails`,
`Participant`, and a `Page<T>` for lists. Create and edit methods return a typed
result too. Simple delete and typing calls return a `boolean` that is `true`
when the call succeeded — any error throws instead, so `false` never comes back.

One rule worth knowing up front: **`createChat`, `updateChat`, `createUser` and
`updateUser` return an empty object** — their getters fall back to defaults —
because the server does not send the object back by default. Pass the matching
options with `returnResource(true)` to get a filled-in object, or read it back
afterwards with `getChat` / `getUser`.

### Chats

| Method | What it does | Returns |
| --- | --- | --- |
| `listChats(ChatsQuery)` | List chats matching the filters | `Page<ChatDetails>` |
| `listChats(ChatsQuery, RequestControl)` | Same, with per-call timeout/retry overrides | `Page<ChatDetails>` |
| `getChat(String chatId)` | Fetch one chat by id | `ChatDetails` |
| `createChat(Chat)` | Create a chat | `ChatDetails` |
| `createChat(Chat, List<Recipient>)` | Create a chat with starting participants | `ChatDetails` |
| `createChat(Chat, List<Recipient>, CreateChatOptions)` | Create a chat; options can ask for the new chat back | `ChatDetails` |
| `updateChat(String chatId, Chat)` | Change a chat's title or metadata | `ChatDetails` |
| `updateChat(String chatId, Chat, UpdateChatOptions)` | Same; options can ask for the updated chat back | `ChatDetails` |
| `deleteChat(String chatId)` | Delete a chat | `boolean` |

```java
Page<ChatDetails> chats = sdk.listChats(ChatsQuery.builder()
        .type(Chat.Type.GROUP)
        .withOwners(true)
        .page(1).limit(20)
        .build());
for (ChatDetails c : chats.items()) {
    System.out.println(c.id() + " " + c.title() + " (" + c.type() + ")");
}

// Ask for the created chat back so its getters are filled in:
ChatDetails created = sdk.createChat(
        Chat.builder().id("support-42").title("Support").type(Chat.Type.GROUP).build(),
        List.of(Recipient.of("u-1", "Alice")),
        CreateChatOptions.builder().returnResource(true).build());
System.out.println(created.id() + " " + created.title());
```

Notes:

- Always pass a `limit`. Without one, `listChats` returns just **one** chat.
- A private chat needs its participants at creation time.
- `updateChat` changes only a chat's title and metadata; send only the fields
  you want to change.
- `deleteChat` returns once the delete is accepted; the server finishes the
  removal in the background, so the chat may disappear a moment later.

### Messages

| Method | What it does | Returns |
| --- | --- | --- |
| `listMessages(String chatId)` | First page of a chat's messages (up to 50) | `Page<Message>` |
| `listMessages(String chatId, MessagesQuery)` | Messages with filters and paging | `Page<Message>` |
| `sendMessage(Chat, User, String text)` | Post a message | `SentMessages` |
| `sendMessage(Chat, User, String text, SendMessageOptions)` | Post a message with participants, extra fields or buttons | `SentMessages` |
| `updateMessage(String chatId, String messageId, String text)` | Edit a message's text | `UpdatedMessage` |
| `updateMessage(String chatId, String messageId, String text, UpdateMessageOptions)` | Edit text, extra fields and buttons; can ask for the message back | `UpdatedMessage` |
| `deleteMessage(String chatId, String messageId)` | Delete a message | `boolean` |
| `sendTyping(String chatId, String userId)` | Show a typing indicator | `boolean` |
| `sendTyping(String chatId, String userId, Integer seconds)` | Typing indicator for a set time (1–60s) | `boolean` |

```java
SentMessages sent = sdk.sendMessage(
        Chat.of("support-42"),
        User.builder().id("u-1").name("Alice").build(),
        "Pick one",
        SendMessageOptions.builder()
                .buttons(Button.of(Button.Type.URL, "Open"),
                         Button.builder().type(Button.Type.LOCAL).label("Dismiss")
                                 .style(Button.Style.NEGATIVE).build())
                .build());
System.out.println("sent " + sent.messageIds());

Page<Message> messages = sdk.listMessages("support-42",
        MessagesQuery.builder().withUsers(true).page(1).limit(50).build());
for (Message m : messages.items()) {
    System.out.println(m.userId() + ": " + m.text());   // text() is null for a deleted message
}

sdk.updateMessage("support-42", "m-1", "Edited text");
```

Notes:

- `sendMessage` returns only the **ids** of the messages it created
  (`sent.messageIds()`), not the stored messages — the server does not send
  those back.
- For `sendMessage`, the chat only needs its id; any other chat fields you set
  create or update the chat. The user is required and the text must be non-empty.
- In `updateMessage`, a `null` or empty `text` leaves the text unchanged.
- The text of a deleted message is `null`.

### Users

| Method | What it does | Returns |
| --- | --- | --- |
| `createUser(User)` | Create a user | `UserDetails` |
| `createUser(User, CreateUserOptions)` | Create a user; options can ask for the new user back | `UserDetails` |
| `getUser(String userId)` | Fetch a user | `UserDetails` |
| `updateUser(String userId, User)` | Change a user's fields | `UserDetails` |
| `updateUser(String userId, User, UpdateUserOptions)` | Same; options can ask for the updated user back | `UserDetails` |
| `deleteUser(String userId)` | Delete a user | `boolean` |
| `listUserChats(String userId)` | First page of chats a user belongs to (up to 50) | `Page<ChatDetails>` |
| `listUserChats(String userId, PageQuery)` | Same, with paging | `Page<ChatDetails>` |

```java
UserDetails created = sdk.createUser(
        User.builder().id("u-3").name("Carol").email("carol@example.com").build(),
        CreateUserOptions.builder().returnResource(true).build());
System.out.println(created.id() + " " + created.name());

UserDetails user = sdk.getUser("u-3");
System.out.println(user.name() + " joined " + user.createdAt());   // createdAt() is an Instant

Page<ChatDetails> userChats = sdk.listUserChats("u-3", PageQuery.builder().page(1).limit(20).build());
```

Notes:

- `createUser` needs a user with at least one field set.
- `updateUser` changes a user's name, email, link, picture and metadata; send
  only the fields you want to change.

### Participants

| Method | What it does | Returns |
| --- | --- | --- |
| `listParticipants(String chatId)` | First page of a chat's participants (up to 50) | `Page<Participant>` |
| `listParticipants(String chatId, PageQuery)` | Same, with paging | `Page<Participant>` |
| `addParticipants(String chatId, List<Recipient>)` | Add participants to a chat | `boolean` |
| `removeParticipant(String chatId, String userId)` | Remove one participant | `boolean` |

```java
sdk.addParticipants("support-42", List.of(
        Recipient.of("u-2", "Bob"),
        Recipient.builder().id("u-3").name("Carol")
                .rights(Rights.builder().sendMessages(true).build())
                .build()));

Page<Participant> participants = sdk.listParticipants("support-42");
for (Participant p : participants.items()) {
    System.out.println(p.id() + " " + p.name());
}

sdk.removeParticipant("support-42", "u-2");
```

Notes:

- `addParticipants` and `removeParticipant` do not send the participants back;
  read them with `listParticipants`.
- A `Participant` carries a person's identity fields but no metadata, and the
  participant list does not include a person's per-chat rights.

## Working with results

### Pages

Every list method returns a `Page<T>`:

- `items()` — the elements as typed objects, in the server's order. Always a
  list, never `null`; empty when the page carries none.
- `currentPage()` — the current page number.
- `itemsPerPage()` — the page size that was requested.
- `pageCount()` — how many pages there are in total.
- `totalCount()` — how many items match across all pages.
- `outputCount()` — how many items are on this page. The participant list does
  not fill this in (it stays 0) — count `items()` instead there.
- `nextPageUrl()` / `prevPageUrl()` — the next/previous page URL, or `null` when
  there is none (also `null` on the participant list).
- `raw()` — the whole response, for anything without a typed getter.

The participant and user-chats lists default to page 1 with 50 items when you
pass no `PageQuery`; use one to page through them.

### The result types

`Page<T>` and the models it holds — `ChatDetails`, `Message`, `UserDetails`,
`Participant`, `SentMessages`, `UpdatedMessage` — are read-only wrappers over the
returned JSON. Each accessor reads its field when you call it, every model has a
`raw()` for anything without a typed accessor, and one rule covers absent data:
**if the server did not send a field the accessor gives back `null`** (or an
empty string / empty list where noted, and a `JsonValue` you can read with the
[accessors below](#reading-a-jsonvalue)). Dates arrive as `java.time.Instant`.

#### `Page<T>`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `items()` | `List<T>` | The page's elements as typed models, in the server's order; empty list, never `null` |
| `currentPage()` | `int` | The current page number; 0 when there are no results |
| `itemsPerPage()` | `int` | The page size that was requested; 0 if missing |
| `pageCount()` | `int` | Total number of pages; 0 if missing |
| `totalCount()` | `int` | Total items matching across all pages; 0 if missing |
| `outputCount()` | `int` | Items on this page; 0 if missing — the participant list leaves it 0, so count `items()` there |
| `nextPageUrl()` | `String` | URL of the next page, or `null` on the last page (and on the participant list) |
| `prevPageUrl()` | `String` | URL of the previous page, or `null` on the first page (and on the participant list) |
| `raw()` | `JsonValue` | The whole response, for fields without a typed accessor |

#### `ChatDetails`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | Chat id; empty string if the server ever omits it |
| `type()` | `Chat.Type` | Chat type, or `null` for a chat with no type or a type this SDK version does not recognise |
| `title()` | `String` | Chat title, or `null` when unset |
| `createdAt()` | `Instant` | When the chat was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the chat last changed, or `null` if the value cannot be read |
| `lastMessageAt()` | `Instant` | Time of the newest message, or `null` when the chat has none |
| `lastMessage()` | `Message` | The newest message, or `null` unless it was requested (with `with_last_message`) |
| `ownerId()` | `String` | Owner id, or `null` when the chat has no owner |
| `owner()` | `JsonValue` | The owner object, present only with `with_owner`; empty value when absent |
| `metadata()` | `JsonValue` | Chat metadata; empty value when absent |
| `raw()` | `JsonValue` | The whole chat object, for fields without a typed accessor |

#### `Message`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | Message id; empty string if the server ever omits it |
| `seq()` | `long` | Per-chat sequence number (the sort order); 0 if missing |
| `userId()` | `String` | Sender's id; empty string if the server ever omits it |
| `text()` | `String` | Message text, or `null` for a deleted message |
| `createdAt()` | `Instant` | When the message was sent, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the message was last edited, or `null` before the first edit |
| `isDeleted()` | `boolean` | Whether the message is deleted |
| `isEdited()` | `boolean` | Whether the message was edited |
| `versions()` | `int` | Number of stored earlier versions |
| `recipientId()` | `String` | Recipient id, or `null` when unset |
| `extra()` | `JsonValue` | The message's extra fields; empty value when absent |
| `buttons()` | `List<JsonValue>` | The message's buttons, in order; empty list when it has none |
| `raw()` | `JsonValue` | The whole message object, for fields without a typed accessor |

#### `UserDetails`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | User id; empty string if the server ever omits it |
| `name()` | `String` | Display name; empty string if the server ever omits it |
| `email()` | `String` | Email, or `null` when unset |
| `link()` | `String` | Profile link, or `null` when unset |
| `picture()` | `JsonValue` | Avatar — a URL string or an object (check with `isString()` / `isObject()`); empty value when absent |
| `createdAt()` | `Instant` | When the user was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the user last changed, or `null` if the value cannot be read |
| `metadata()` | `JsonValue` | User metadata; empty value when absent |
| `raw()` | `JsonValue` | The whole user object, for fields without a typed accessor |

#### `Participant`

Carries a person's identity fields but, unlike `UserDetails`, has **no
metadata**, and the participant list does not include a person's per-chat rights.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | User id; empty string if the server ever omits it |
| `name()` | `String` | Display name; empty string if the server ever omits it |
| `email()` | `String` | Email, or `null` when unset |
| `link()` | `String` | Profile link, or `null` when unset |
| `picture()` | `JsonValue` | Avatar — a URL string or an object; empty value when absent |
| `createdAt()` | `Instant` | When the participant was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the participant last changed, or `null` if the value cannot be read |
| `raw()` | `JsonValue` | The whole participant object, for fields without a typed accessor |

#### `SentMessages` (from `sendMessage`)

| Accessor | Type | What it holds |
| --- | --- | --- |
| `messageIds()` | `List<String>` | Ids of the messages just created, in send order; empty list when none |
| `raw()` | `JsonValue` | The whole response, for fields without a typed accessor |

#### `UpdatedMessage` (from `updateMessage`)

| Accessor | Type | What it holds |
| --- | --- | --- |
| `isUpdated()` | `boolean` | Whether the edit actually changed the message |
| `message()` | `Message` | The updated message, or `null` unless you asked for it with `returnResource(true)` |
| `raw()` | `JsonValue` | The whole response, for fields without a typed accessor |

### Reading a `JsonValue`

`requestApi` and every model's `raw()` return a `JsonValue`: the SDK's own
read-only wrapper over JSON. It is safe to walk without null checks — a step
that does not exist gives back an empty value instead of throwing, so a chain of
lookups never fails on missing data.

```java
JsonValue chat = sdk.getChat("support-42").raw();

// Read a field with a fallback for when it is missing:
String title = chat.get("title").asString("(untitled)");

// A path that does not exist falls through to the default, with no error:
String missing = chat.get("nope").get("deeper").asString("fallback");

// Reach deep in one step with a JSON Pointer:
String plan = chat.at("/metadata/plan").asString("");

// Iterate an array; values() is empty (never null) for a non-array:
for (JsonValue hook : chat.get("webhooks").values()) {
    System.out.println(hook.get("url").asString(""));
}
```

- `get(field)` / `get(index)` step into an object or array; `at("/a/b/0")`
  follows a JSON Pointer. A pointer that does not resolve gives an empty value; a
  malformed pointer (one without a leading `/`) throws `GetChatException`.
- The `as*(default)` readers — `asString(def)`, `asInt(def)`, `asLong(def)`,
  `asDouble(def)`, `asBoolean(def)` — return the default when the value is
  missing or cannot be converted, and never throw. The no-argument `asString()`
  is strict: it throws when the value is not a present string.
- `values()` iterates an array, `fieldNames()` lists an object's keys, and
  `toMap()` / `toList()` convert to plain Java collections. The predicates
  `isMissing()`, `isNull()`, `isObject()`, `isArray()`, `isString()`,
  `isNumber()`, `isBoolean()`, `has(field)` and `size()` inspect the value.

### Calling endpoints the SDK does not wrap

For an endpoint without a typed method, describe the call with an `ApiRequest`
and send it through `requestApi`, which returns a `JsonValue`:

```java
// GET a URL query string:
JsonValue hooks = sdk.requestApi(ApiRequest.get("chats/support-42/webhooks")
        .query("with_disabled", 1)
        .build());

// PUT with a JSON body, a URL query param and a custom header:
sdk.requestApi(ApiRequest.put("chats/support-42/webhook")
        .body(Map.of("url", "https://example.com/hook"))
        .query("dry_run", 1)
        .header("X-Request-Id", "abc-123")
        .build());
```

Start from `ApiRequest.get`, `post`, `put` or `delete` with the path (the part
after `/api/{version}/`), then add `query`, `body`, `header`, `version` or
`control`. For `GET` and `DELETE`, `query` is the URL query string; for `POST`
and `PUT`, `body` is the JSON payload and `query` the URL query string. Setting
a `body` on a `GET` or `DELETE` is rejected at `build()` rather than dropped.

The typed input builders (`ChatsQuery`, `MessagesQuery`, `Chat`, `User`,
`Recipient`, `Rights`, `Button`) each have a `set(key, value)` method for a field
that has no typed setter, so you can send a new field without waiting for the SDK
to add one.

## Errors

| Exception | When it is thrown |
| --- | --- |
| `GetChatApiException` | The server replied with an error status; carries `status()`, `body()` and `rawBody()` |
| `GetChatTimeoutException` | An attempt ran past its timeout |
| `GetChatException` | Bad input, a transport failure, or a JSON failure |

All three are unchecked and share `GetChatException` as their base, so one
`catch` can cover them. Every input check throws `GetChatException` (for
example, a missing chat id, empty message text, or missing signing
credentials). A plain `NullPointerException` is reserved for passing `null`
where a required argument — such as the config — is expected.

`GetChatApiException.body()` returns a `JsonValue`: the parsed error payload when
the response was JSON, or an empty value (`body().isMissing()` is `true`) when it
was not. `rawBody()` always holds the response text exactly as received.

```java
try {
    sdk.getChat("does-not-exist");
} catch (GetChatApiException e) {
    int status = e.status();                           // e.g. 404
    String code = e.body().get("error").asString("");  // safe to read on any body
}
```

## Timeouts and retries

Defaults: 30 seconds per attempt, 2 retries, and a 200 ms base backoff with
jitter between attempts.

`timeout` and `retryDelay` take a `java.time.Duration`; `Duration.ZERO` turns
off the per-attempt timeout. `retries` is a plain `int` (up to 10).

```java
import java.time.Duration;

GetChat sdk = new GetChat(GetChatConfig.builder()
        .apiToken("...")
        .baseUrl("...")
        .options(RequestOptions.builder().timeout(Duration.ofSeconds(5)).retries(3).build())
        .build());

// Override per call — a field left unset keeps the instance default:
sdk.listChats(
        ChatsQuery.builder().limit(20).build(),
        RequestControl.builder().timeout(Duration.ofSeconds(1)).retries(0).build());
```

Set these for the whole instance through `RequestOptions` on the config. To
override them for a single call, `listChats` takes a `RequestControl`, and any
`ApiRequest` carries one through `.control(...)`.

Which calls retry, in plain terms:

- Calls that only **read** (`GET`, `DELETE`) retry on network errors and on
  `5xx` / `429` responses.
- Calls that **change data** (`POST`, `PUT`) retry only when the SDK is certain
  the request never reached the server (a connection that failed before anything
  was sent), plus on a `429` rate-limit reply. So a write is never sent twice.

A `Retry-After` header is respected, capped at 30 seconds. Because a timeout
counts as a network error, a hung read can take up to about
`(retries + 1) × timeout` plus backoff before it gives up.

## Thread safety and lifecycle

`GetChat` is immutable and safe to share — build one and reuse it rather than
creating one per request. It makes its own `HttpClient`; supply your own through
`GetChatConfig.builder().httpClient(...)` for a proxy or custom TLS.

Most applications keep one long-lived instance and never close it. `GetChat` is
`AutoCloseable` for the short-lived case:

```java
try (GetChat sdk = new GetChat(config)) {
    sdk.getChat("support-42");
}
```

`close()` releases **only** an `HttpClient` the SDK created itself; a client you
supplied stays open, since its lifecycle is yours. It can be called more than
once safely and never throws. `HttpClient` became `AutoCloseable` only in
JDK 21, so on JDK 17–20 `close()` does nothing and the garbage collector
reclaims the client instead.

`GetChatConfig` and the value types (`User`, `Chat`, `Button`, the query
builders, …) implement `equals`, `hashCode` and `toString`.
`GetChatConfig.toString()` hides `secret` and `apiToken` (they print as `***`),
so a config is safe to log.

## Development

```bash
./gradlew build    # compile, test, jar, javadoc
./gradlew test     # tests only
./gradlew test --tests '*SignatureVectorTest*'
```

See `CLAUDE.md` for architecture and the rules around changing signing code.

## License

MIT — see [LICENSE](LICENSE).
