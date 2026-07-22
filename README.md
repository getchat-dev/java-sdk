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
    implementation("dev.getchat:getchat-java-sdk:1.0.0")
}
```

On the classpath the SDK is a plain library and needs nothing more. If your
application is a Java module, the SDK is the module `dev.getchat.sdk` — add
`requires dev.getchat.sdk;` to your `module-info.java`. Only the public package
`dev.getchat.sdk` is exported; the signing and transport internals stay private
to the module.

## Setup

The SDK has two entry points, one for each job. Build each one, then reuse it —
both are safe to share across threads.

```java
// Signs embed URLs. Needs a client id, a client secret and a base URL.
GetChatUrlSigner signer = GetChatUrlSigner.builder()
        .clientId("your-client-id")
        .secret("your-client-secret")
        .baseUrl("https://chat.example.com/embed")
        .build();

// Calls the REST API. Needs the API URL and an API token.
GetChatClient client = GetChatClient.builder()
        .apiUrl("https://chat.example.com")
        .apiToken("your-api-token")
        .build();
```

- Build only the one you need: a program that just signs URLs never creates a
  `GetChatClient`, and the URL signer opens no network resources.
- Each builder checks at `build()` that everything it needs is set. A missing or
  blank value throws `GetChatException` right there, so you can never end up with
  a half-configured object that fails later — no URL that comes out as
  `null?nonce=...`, no REST call fired without a token.
- `build()` also checks the URL: `baseUrl` / `apiUrl` must be an absolute
  `http`/`https` URL, or it throws `GetChatException`. A relative path, a
  `ftp:` scheme, or a malformed string is caught up front.
- Both `baseUrl(...)` and `apiUrl(...)` accept a `java.net.URI` as well as a
  `String` — pass whichever form you have; they behave identically.
- The two are independent. The API often lives on a different host than the
  embed URL, which is why the client takes its own `apiUrl`.

## Signed embed URLs

`url(...)` builds the current, recommended URL. Pass a user (required) and,
usually, the chat to open:

```java
String url = signer.url(UrlOptions.builder()
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
String a = signer.urlByChatId("support-42", User.of("u-1"));

// A Chat object and a user:
String b = signer.urlByChatId(Chat.builder().id("support-42").title("Support").build(), User.of("u-1"));

// Full options, when you also need participants or extra query params:
String c = signer.urlByChatId(UrlOptions.builder()
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
Page<ChatDetails> chats = client.listChats(ChatsQuery.builder()
        .type(Chat.Type.GROUP)
        .withOwners(true)
        .page(1).limit(20)
        .build());
for (ChatDetails c : chats) {   // a Page is iterable — loop over it directly
    System.out.println(c.id() + " " + c.title() + " (" + c.type() + ")");
}

// Ask for the created chat back so its getters are filled in:
ChatDetails created = client.createChat(
        Chat.builder().id("support-42").title("Support").type(Chat.Type.GROUP).build(),
        List.of(Recipient.of("u-1", "Alice")),
        CreateChatOptions.builder().returnResource(true).build());
System.out.println(created.id() + " " + created.title());
```

Notes:

- Always pass a `limit`. Without one, `listChats` returns just **one** chat.
- The date filters (`createdFrom`, `createdTo`, `lastMessageFrom`, `lastMessageTo`)
  accept a `java.time.LocalDateTime` as well as the wire string. It is formatted
  to the strict form `yyyy-MM-dd'T'HH:mm:ss` — no timezone, seconds precision (any
  nanoseconds are dropped). The backend rejects anything else, which is why the
  type is `LocalDateTime` and not `Instant`/`OffsetDateTime`.
- `page` and `limit` are checked when you `build()` the query: `page` must be at
  least 1 and `limit` must be in `1..1000`, otherwise `build()` throws
  `GetChatException`. (`MessagesQuery` and `PageQuery` check the same ranges.)
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
| `sendTyping(String chatId, String userId, Duration duration)` | Typing indicator for a set time (1–60 whole seconds) | `boolean` |

```java
SentMessages sent = client.sendMessage(
        Chat.of("support-42"),
        User.builder().id("u-1").name("Alice").build(),
        "Pick one",
        SendMessageOptions.builder()
                .buttons(Button.of(Button.Type.URL, "Open"),
                         Button.builder().type(Button.Type.LOCAL).label("Dismiss")
                                 .style(Button.Style.NEGATIVE).build())
                .build());
System.out.println("sent " + sent.messageIds());

Page<Message> messages = client.listMessages("support-42",
        MessagesQuery.builder().withUsers(true).page(1).limit(50).build());
for (Message m : messages.items()) {
    System.out.println(m.userId() + ": " + m.text());   // text() is null for a deleted message
}

client.updateMessage("support-42", "m-1", "Edited text");
```

Notes:

- `sendMessage` returns only the **ids** of the messages it created
  (`sent.messageIds()`), not the stored messages — the server does not send
  those back.
- For `sendMessage`, the chat only needs its id; any other chat fields you set
  create or update the chat. The user is required and the text must be non-empty.
- In `updateMessage`, a `null` or empty `text` leaves the text unchanged.
- The text of a deleted message is `null`.
- `sendTyping(chatId, userId, Duration)` takes a `java.time.Duration` of 1 to 60
  **whole** seconds. A duration with a sub-second part, or one outside that range,
  throws `GetChatException` (it is not silently truncated); a `null` duration
  throws `NullPointerException`. Use the two-argument overload to send no duration
  and let the client default apply.

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
UserDetails created = client.createUser(
        User.builder().id("u-3").name("Carol").email("carol@example.com").build(),
        CreateUserOptions.builder().returnResource(true).build());
System.out.println(created.id() + " " + created.name());

UserDetails user = client.getUser("u-3");
System.out.println(user.name() + " joined " + user.createdAt());   // createdAt() is an Instant

Page<ChatDetails> userChats = client.listUserChats("u-3", PageQuery.builder().page(1).limit(20).build());
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
client.addParticipants("support-42", List.of(
        Recipient.of("u-2", "Bob"),
        Recipient.builder().id("u-3").name("Carol")
                .rights(Rights.builder().sendMessages(true).build())
                .build()));

Page<Participant> participants = client.listParticipants("support-42");
for (Participant p : participants.items()) {
    System.out.println(p.id() + " " + p.name());
}

client.removeParticipant("support-42", "u-2");
```

Notes:

- `addParticipants` and `removeParticipant` do not send the participants back;
  read them with `listParticipants`.
- A `Participant` carries a person's identity fields but no metadata, and the
  participant list does not include a person's per-chat rights.

## Working with results

### Pages

Every list method returns a `Page<T>`. A `Page<T>` is iterable, so you can loop
over it directly with a for-each (`for (ChatDetails c : chats)`) or call
`stream()` to process its items — both go over `items()` in the same order:

- `items()` — the elements as typed objects, in the server's order. Always a
  list, never `null`; empty when the page carries none.
- `size()` — how many items are on this page (the same as `items().size()`).
  Not the same as `totalCount()` (all pages) or `pageCount()` (number of pages).
- `isEmpty()` — `true` when this page carries no items.
- `stream()` — a stream over the items, in `items()` order.
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

`Page<T>`, the models it holds — `ChatDetails`, `Message`, `UserDetails`,
`Participant` — and the write results `SentMessages` and `UpdatedMessage` are
read-only wrappers over the returned JSON. Each accessor reads its field when you call it, every model has a
`raw()` for anything without a typed accessor, and one rule covers absent data:
**if the server did not send a field the accessor gives back `null`** (or an
empty string / empty list where noted, and a `JsonValue` you can read with the
[accessors below](#reading-a-jsonvalue)). Dates arrive as `java.time.Instant`.

#### `Page<T>`

A `Page<T>` is `Iterable<T>`, so you can use it directly in a for-each loop
(`for (ChatDetails c : chats)`) or call `stream()`; both walk `items()` in order.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `items()` | `List<T>` | The page's elements as typed models, in the server's order; empty list, never `null` |
| `size()` | `int` | How many items are on this page; 0 when empty. Not `totalCount()` (all pages) or `pageCount()` (number of pages) |
| `isEmpty()` | `boolean` | `true` when this page carries no items |
| `stream()` | `Stream<T>` | A stream over the items, in `items()` order |
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
| `owner()` | `UserDetails` | The chat owner, or `null` unless the chat carries an embedded owner (see the note) |
| `metadata()` | `Map<String, Object>` | Chat metadata as a map of scalar values (`String`, `Number` or `Boolean`); empty map when absent |
| `raw()` | `JsonValue` | The whole chat object, for fields without a typed accessor |

`owner()` is filled only when the server embeds the owner in the chat — reading
one chat with `getChat`, or a listing requested with `ChatsQuery.withOwner(true)`
(the singular `with_owner` flag). It is `null` otherwise. Note that
`ChatsQuery.withOwners(true)` is a different, plural flag: it attaches the owners
to a separate `users` map on the raw list response rather than to each chat, so it
does **not** populate `owner()` (reach that map through the page's `raw()`).

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
| `buttons()` | `List<ButtonDetails>` | The message's buttons, in order; empty list when it has none |
| `raw()` | `JsonValue` | The whole message object, for fields without a typed accessor |

#### `UserDetails`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | User id; empty string if the server ever omits it |
| `name()` | `String` | Display name; empty string if the server ever omits it |
| `email()` | `String` | Email, or `null` when unset |
| `link()` | `String` | Profile link, or `null` when unset |
| `picture()` | `Avatar` | The user's avatar, or `null` when absent |
| `createdAt()` | `Instant` | When the user was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the user last changed, or `null` if the value cannot be read |
| `metadata()` | `Map<String, Object>` | User metadata as a map of scalar values (`String`, `Number` or `Boolean`); empty map when absent |
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
| `picture()` | `Avatar` | The participant's avatar, or `null` when absent |
| `createdAt()` | `Instant` | When the participant was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the participant last changed, or `null` if the value cannot be read |
| `raw()` | `JsonValue` | The whole participant object, for fields without a typed accessor |

#### `Avatar` (from `UserDetails.picture()` / `Participant.picture()`)

A person's avatar is one of two shapes: a plain image URL, or a generated
placeholder (initials on a coloured background). `isUrl()` tells them apart —
for a URL only `url()` is set; for a placeholder only the object fields are.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `isUrl()` | `boolean` | `true` for a URL avatar, `false` for a generated placeholder |
| `url()` | `String` | The image URL, or `null` for a placeholder |
| `kind()` | `String` | Placeholder kind (e.g. `"auto"`), or `null` for a URL |
| `color()` | `String` | Placeholder background colour, or `null` for a URL or when unset |
| `initials()` | `String` | Placeholder initials, or `null` for a URL or when unset |
| `raw()` | `JsonValue` | The whole avatar value, for fields without a typed accessor |

#### `ButtonDetails` (from `Message.buttons()`)

A message's inline button. The reading counterpart of the `Button` input
builder, sharing its `Button.Type` / `Button.State` / `Button.Style` enums. An
enum accessor returns `null` when the value is absent or is one this SDK version
does not recognise, so an unknown future value never throws.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `type()` | `Button.Type` | Button behaviour, or `null` when absent or unrecognised |
| `label()` | `String` | Button label; empty string if the server ever omits it |
| `action()` | `String` | Button action payload, or `null` when unset |
| `state()` | `Button.State` | Interaction state, or `null` when absent or unrecognised |
| `style()` | `Button.Style` | Colour treatment, or `null` when absent or unrecognised |
| `raw()` | `JsonValue` | The whole button object, for fields without a typed accessor |

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
JsonValue chat = client.getChat("support-42").raw();

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
JsonValue hooks = client.requestApi(ApiRequest.get("chats/support-42/webhooks")
        .query("with_disabled", 1)
        .build());

// PUT with a JSON body, a URL query param and a custom header:
client.requestApi(ApiRequest.put("chats/support-42/webhook")
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

Every error the SDK throws is unchecked and shares `GetChatException` as its
base, so a single `catch (GetChatException e)` still covers all of them. Below
that root there are now separate types for the different kinds of failure:

```
GetChatException                  the base; thrown on its own only for a mistake in your code
├── GetChatApiException           the server replied with an error status
├── GetChatTransportException     the request never completed (could not connect, DNS
│   │                             failure, or the connection broke)
│   └── GetChatTimeoutException   an attempt ran past its timeout
├── GetChatSerializationException a JSON body could not be written or read back
└── GetChatInterruptedException   the thread was interrupted while the call was in progress
```

| Exception | When it is thrown |
| --- | --- |
| `GetChatApiException` | The server replied with an error status; carries `status()`, `body()`, `rawBody()`, `method()`, `uri()` and `requestId()` |
| `GetChatTransportException` | The request never reached a reply — a failed connection, a DNS failure, or a broken connection |
| `GetChatTimeoutException` | An attempt ran past its timeout (a kind of transport failure, so it extends `GetChatTransportException`) |
| `GetChatSerializationException` | A JSON body could not be written (the request) or read (the response) |
| `GetChatInterruptedException` | The calling thread was interrupted mid-request |
| `GetChatException` | Thrown on its own only for a mistake in your code: bad input, bad configuration, or misuse of a `JsonValue` |

Which to catch, in one line each:

- **transport (including timeout)** — safe to try again.
- **api** — look at `status()` to decide what to do.
- **serialization** — the server sent something that is not the agreed shape.
- **the base `GetChatException` on its own** — fix the calling code.

Every input check throws the base `GetChatException` (for example, a missing
chat id, empty message text, or a builder missing a required field at
`build()`). A plain `NullPointerException` is reserved for passing `null` where a
required argument — such as the `ApiRequest` given to `requestApi` — is expected.

`GetChatApiException` describes both the failed request and the server's reply:

- `status()` — the HTTP status code.
- `body()` — the parsed error payload as a `JsonValue` when the response was
  JSON, or an empty value (`body().isMissing()` is `true`) when it was not.
- `rawBody()` — the response text exactly as received.
- `method()` — the HTTP method of the request, such as `"GET"`.
- `uri()` — the request URI (a `java.net.URI`).
- `requestId()` — the server's request id, taken from the `X-Request-Id`
  response header. It is `null` when the server did not send one; include it when
  you report a problem to support.

```java
try {
    client.getChat("does-not-exist");
} catch (GetChatApiException e) {
    int status = e.status();                           // e.g. 404
    String code = e.body().get("error").asString("");  // safe to read on any body
    String id = e.requestId();                          // may be null
}
```

## Timeouts and retries

Defaults: 30 seconds per attempt, 2 retries, and a 200 ms base backoff with
jitter between attempts.

`timeout` and `retryDelay` take a `java.time.Duration`; `Duration.ZERO` turns
off the per-attempt timeout. `retries` is a plain `int` (up to 10).

```java
import java.time.Duration;

GetChatClient client = GetChatClient.builder()
        .apiUrl("...")
        .apiToken("...")
        .options(RequestOptions.builder().timeout(Duration.ofSeconds(5)).retries(3).build())
        .build();

// Override per call — a field left unset keeps the instance default:
client.listChats(
        ChatsQuery.builder().limit(20).build(),
        RequestControl.builder().timeout(Duration.ofSeconds(1)).retries(0).build());
```

Set these for the whole client through `RequestOptions` on the builder. To
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

Both entry points are immutable and safe to share — build one of each and reuse
them rather than creating one per request. `GetChatUrlSigner` holds no network
resources at all. `GetChatClient` makes its own `HttpClient`; supply your own
through `GetChatClient.builder().httpClient(...)` for a proxy or custom TLS.

Most applications keep one long-lived client and never close it. `GetChatClient`
is `AutoCloseable` for the short-lived case:

```java
try (GetChatClient client = GetChatClient.builder()
        .apiUrl("https://chat.example.com")
        .apiToken("your-api-token")
        .build()) {
    client.getChat("support-42");
}
```

`close()` releases **only** an `HttpClient` the SDK created itself; a client you
supplied stays open, since its lifecycle is yours. It can be called more than
once safely and never throws. `HttpClient` became `AutoCloseable` only in
JDK 21, so on JDK 17–20 `close()` does nothing and the garbage collector
reclaims the client instead.

The value types (`User`, `Chat`, `Button`, the query builders, …) implement
`equals`, `hashCode` and `toString`. The two entry points are not value types,
but both give a safe-to-log `toString`: `GetChatUrlSigner.toString()` hides
`secret` and `GetChatClient.toString()` hides `apiToken` (each prints as `***`).

## Development

```bash
./gradlew build    # compile, test, jar, javadoc
./gradlew test     # tests only
./gradlew test --tests '*SignatureVectorTest*'
```

See `CLAUDE.md` for architecture and the rules around changing signing code.

## License

MIT — see [LICENSE](LICENSE).
