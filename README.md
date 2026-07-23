# GetChat Java SDK

Server-side Java SDK for [GetChat](https://getchat.dev). It does two things:

1. Builds **signed chat URLs** so you can drop the chat UI into an iframe or WebView.
2. Wraps the **GetChat REST API**, authenticating with a `Bearer` token.

Java 17 or newer. Its one runtime dependency, Jackson, is used only inside the
SDK: chat and message reads come back as typed objects
([`ChatDetails`](#chatdetails), [`Message`](#message), [`Page<T>`](#paget), …) and
everything else as the SDK's own [`JsonValue`](#reading-a-jsonvalue), so no
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
  blank value throws [`GetChatException`](#errors) right there, so you can never end up with
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

`url(...)` builds the current, recommended URL. It takes a
[`UrlOptions`](#urloptions) carrying a [`User`](#user) (required) and, usually,
the [`Chat`](#chat) to open; [`Rights`](#rights) says what the user may do and
[`Recipient`](#recipient) adds other participants:

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

Read methods return typed objects — [`ChatDetails`](#chatdetails),
[`Message`](#message), [`UserDetails`](#userdetails), [`Participant`](#participant),
and a [`Page<T>`](#paget) for lists. Create and edit methods return a typed
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
| `listChats(ChatsQuery)` | List chats matching the filters | [`Page`](#paget)<[`ChatDetails`](#chatdetails)> |
| `listChats(ChatsQuery, RequestControl)` | Same, with per-call [timeout/retry overrides](#timeouts-and-retries) | [`Page`](#paget)<[`ChatDetails`](#chatdetails)> |
| `getChat(String chatId)` | Fetch one chat by id | [`ChatDetails`](#chatdetails) |
| `createChat(Chat)` | Create a chat | [`ChatDetails`](#chatdetails) |
| `createChat(Chat, List<Recipient>)` | Create a chat with starting participants | [`ChatDetails`](#chatdetails) |
| `createChat(Chat, List<Recipient>, CreateChatOptions)` | Create a chat; options can ask for the new chat back | [`ChatDetails`](#chatdetails) |
| `updateChat(String chatId, Chat)` | Change a chat's title or metadata | [`ChatDetails`](#chatdetails) |
| `updateChat(String chatId, Chat, UpdateChatOptions)` | Same; options can ask for the updated chat back | [`ChatDetails`](#chatdetails) |
| `deleteChat(String chatId)` | Delete a chat | `boolean` |

Takes: [`ChatsQuery`](#chatsquery), [`Chat`](#chat), [`Recipient`](#recipient),
[`CreateChatOptions`](#createchatoptions), [`UpdateChatOptions`](#updatechatoptions),
[`RequestControl`](#timeouts-and-retries).

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
  [`GetChatException`](#errors). (`MessagesQuery` and `PageQuery` check the same ranges.)
- A private chat needs its participants at creation time.
- `updateChat` changes only a chat's title and metadata; send only the fields
  you want to change.
- `deleteChat` returns once the delete is accepted; the server finishes the
  removal in the background, so the chat may disappear a moment later.

### Messages

| Method | What it does | Returns |
| --- | --- | --- |
| `listMessages(String chatId)` | First page of a chat's messages (up to 50) | [`Page`](#paget)<[`Message`](#message)> |
| `listMessages(String chatId, MessagesQuery)` | Messages with filters and paging | [`Page`](#paget)<[`Message`](#message)> |
| `sendMessage(Chat, User, String text)` | Post a message | [`SentMessages`](#sentmessages) |
| `sendMessage(Chat, User, String text, SendMessageOptions)` | Post a message with participants, extra fields or buttons | [`SentMessages`](#sentmessages) |
| `updateMessage(String chatId, String messageId, String text)` | Edit a message's text | [`UpdatedMessage`](#updatedmessage) |
| `updateMessage(String chatId, String messageId, String text, UpdateMessageOptions)` | Edit text, extra fields and buttons; can ask for the message back | [`UpdatedMessage`](#updatedmessage) |
| `deleteMessage(String chatId, String messageId)` | Delete a message | `boolean` |
| `sendTyping(String chatId, String userId)` | Show a typing indicator | `boolean` |
| `sendTyping(String chatId, String userId, Duration duration)` | Typing indicator for a set time (1–60 whole seconds) | `boolean` |

Takes: [`MessagesQuery`](#messagesquery), [`Chat`](#chat), [`User`](#user),
[`SendMessageOptions`](#sendmessageoptions),
[`UpdateMessageOptions`](#updatemessageoptions).

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
  throws [`GetChatException`](#errors) (it is not silently truncated); a `null` duration
  throws `NullPointerException`. Use the two-argument overload to send no duration
  and let the client default apply.

### Users

| Method | What it does | Returns |
| --- | --- | --- |
| `createUser(User)` | Create a user | [`UserDetails`](#userdetails) |
| `createUser(User, CreateUserOptions)` | Create a user; options can ask for the new user back | [`UserDetails`](#userdetails) |
| `getUser(String userId)` | Fetch a user | [`UserDetails`](#userdetails) |
| `updateUser(String userId, User)` | Change a user's fields | [`UserDetails`](#userdetails) |
| `updateUser(String userId, User, UpdateUserOptions)` | Same; options can ask for the updated user back | [`UserDetails`](#userdetails) |
| `deleteUser(String userId)` | Delete a user | `boolean` |
| `listUserChats(String userId)` | First page of chats a user belongs to (up to 50) | [`Page`](#paget)<[`ChatDetails`](#chatdetails)> |
| `listUserChats(String userId, PageQuery)` | Same, with paging | [`Page`](#paget)<[`ChatDetails`](#chatdetails)> |

Takes: [`User`](#user), [`CreateUserOptions`](#createuseroptions),
[`UpdateUserOptions`](#updateuseroptions), [`PageQuery`](#pagequery).

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
| `listParticipants(String chatId)` | First page of a chat's participants (up to 50) | [`Page`](#paget)<[`Participant`](#participant)> |
| `listParticipants(String chatId, PageQuery)` | Same, with paging | [`Page`](#paget)<[`Participant`](#participant)> |
| `addParticipants(String chatId, List<Recipient>)` | Add participants to a chat | `boolean` |
| `removeParticipant(String chatId, String userId)` | Remove one participant | `boolean` |

Takes: [`PageQuery`](#pagequery), [`Recipient`](#recipient).

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

## Input types

Everything you hand to the SDK is an immutable value object built through a
static `builder()`; the common ones also have a one-line factory (`Chat.of`,
`User.of`, `Recipient.of`, `Button.of`). They all implement `equals`, `hashCode`
and `toString`.

Most carry a `set(key, value)` escape hatch for a field this SDK version has no
typed setter for, so you can send something new without waiting for a release:
`Chat`, `User`, `Recipient`, `Rights`, `Button`, `ChatsQuery`, `MessagesQuery`
and `UpdateMessageOptions` have one.

| Type | What it is | Where it goes |
| --- | --- | --- |
| [`UrlOptions`](#urloptions) | Everything a signed embed URL needs | [`url`](#signed-embed-urls), [`urlByChatId`](#legacy-urls) |
| [`Chat`](#chat) | A chat to open, create or update | `url`, [`createChat`](#chats), `updateChat`, [`sendMessage`](#messages) |
| [`User`](#user) | The person a URL, a message or a user call is about | `url`, [`createUser`](#users), `updateUser`, `sendMessage` |
| [`Recipient`](#recipient) | Somebody else taking part in a chat | `createChat`, [`addParticipants`](#participants), `SendMessageOptions` |
| [`Rights`](#rights) | What a person may do in a chat | `User`, `Recipient` |
| [`Button`](#button) | An inline message button | `SendMessageOptions`, `UpdateMessageOptions` |
| [`ChatsQuery`](#chatsquery) | Filters and paging for the chat list | [`listChats`](#chats) |
| [`MessagesQuery`](#messagesquery) | Filters and paging for the message list | [`listMessages`](#messages) |
| [`PageQuery`](#pagequery) | Paging on its own | [`listUserChats`](#users), [`listParticipants`](#participants) |
| [`SendMessageOptions`](#sendmessageoptions) | Participants, extra fields and buttons for a new message | `sendMessage` |
| [`UpdateMessageOptions`](#updatemessageoptions) | The same for an edit, plus the merge mode | [`updateMessage`](#messages) |
| [`CreateChatOptions`](#createchatoptions) | Ask for the created chat back | `createChat` |
| [`UpdateChatOptions`](#updatechatoptions) | Ask for the updated chat back | `updateChat` |
| [`CreateUserOptions`](#createuseroptions) | Ask for the created user back | `createUser` |
| [`UpdateUserOptions`](#updateuseroptions) | Ask for the updated user back | `updateUser` |

Two more input objects are described where they are used:
[`ApiRequest`](#calling-endpoints-the-sdk-does-not-wrap) for unwrapped endpoints,
and [`RequestOptions` / `RequestControl`](#timeouts-and-retries) for timeouts and
retries.

### `UrlOptions`

Arguments to [`url(...)`](#signed-embed-urls) and to the full form of
[`urlByChatId(...)`](#legacy-urls). A user is always required; `urlByChatId` also
needs a chat.

| Setter | What it sets |
| --- | --- |
| `chat(Chat)` / `chat(String chatId)` | The chat to open |
| `user(User)` | Who the URL is for — required |
| `participant(Recipient)` | Adds one participant; call it again to add more |
| `participants(List<Recipient>)` | Replaces the participant list |
| `extra(String key, Object value)` / `extra(Map<String, Object>)` | Extra query params. **Added after the signature, so they are not signed** — display hints only, never permissions |

### `Chat`

A chat to open, create or update. Shorthand: `Chat.of("support-42")` when the id
is all you have.

| Setter | What it sets |
| --- | --- |
| `id(String)` | Chat id |
| `title(String)` | Chat title |
| `type(Chat.Type)` | `PRIVATE`, `GROUP`, `SUPERGROUP` or `CHANNEL` |
| `create(boolean)` | Create the chat if it does not exist yet |
| `metadata(Map<String, Object>)` | Chat metadata; scalar values |
| `set(String, Object)` | Any field with no typed setter |

`SUPERGROUP` is a group past the 255-participant mark — it behaves less like a
conversation and more like a comment thread.

### `User`

The person a signed URL, a message or a user call is about. Shorthand:
`User.of("u-1")`. A user with **no** `id` is anonymous: the URL builders give it
a random 40-character `session` so the browser stays recognisable across page
loads.

| Setter | What it sets |
| --- | --- |
| `id(String)` | User id |
| `name(String)` | Display name |
| `email(String)` | Email |
| `link(String)` | Profile link |
| `picture(String)` | Avatar URL |
| `session(String)` | Anonymous-session token; used only when no `id` is set |
| `isBot(boolean)` | Marks the user as a bot |
| `rights(Rights)` | What the user may do — see [`Rights`](#rights) |
| `set(String, Object)` | Any field with no typed setter |

### `Recipient`

Somebody other than the main user: a participant of a signed URL, a member added
when a chat is created, or the target of `addParticipants`. Shorthand:
`Recipient.of("u-2", "Bob")`.

| Setter | What it sets |
| --- | --- |
| `id(String)` | User id |
| `name(String)` | Display name |
| `email(String)` / `link(String)` / `picture(String)` | The other identity fields |
| `isBot(boolean)` | Marks the participant as a bot |
| `rights(Rights)` | Per-chat rights — see [`Rights`](#rights) |
| `set(String, Object)` | Any field with no typed setter |

### `Rights`

What a person may do in a chat; attach it to a [`User`](#user) or a
[`Recipient`](#recipient). Insertion order is preserved and shows up in the
generated URL, so the same rights added in a different order give different (both
valid) query strings.

Three rights take an enum, and each has a varargs overload that appends
[params](#enum-rights-can-carry-params):

| Setter | Base values |
| --- | --- |
| `editMessages(Rights.Scope)` | `NONE`, `MY`, `ANY` |
| `deleteMessages(Rights.Scope)` | `NONE`, `MY`, `ANY` |
| `pinMessages(Rights.Pin)` | `NONE`, `FOR_ME`, `FOR_EVERYONE` |

The other sixteen take a `boolean`: `sendMessages`, `reactMessages`,
`canPressButtons`, `sendTyping`, `trackPresence`, `sendPhotos`, `sendVoices`,
`sendAudio`, `sendDocuments`, `sendLocation`, `createPool`, `participatePool`,
`kickUsers`, `trackReadState`, `sendReadState`, `leaveChats`. A right this SDK
version has no setter for goes through `set(key, value)`.

#### Enum rights can carry params

The table above lists the **base** values, not the whole value space a signed
link accepts. There an enum right goes on the wire as `value:param:param…`, and
only the part before the first colon is validated — by this SDK, by the node SDK
and by the backend alike. The full string is what gets signed and sent, and the
chat UI reads the params back off it.

The form in use today is `edit_messages` = `"my:extra"`: scope `my`, plus the
`extra` param, which is what allows editing a message's `extra` payload rather
than its text. Pass params as extra arguments to the enum setter:

```java
Rights consultant = Rights.builder()
        .sendMessages(true)
        .editMessages(Rights.Scope.MY, "extra")   // → "my:extra"
        .deleteMessages(Rights.Scope.MY)          // → "my", no params
        .kickUsers(true)
        .build();
```

A param has to be non-empty and free of both `:` and whitespace. A `:` would
split one param into two, since only the head is validated; whitespace would stop
the chat UI matching it, because it compares each piece for exact equality. The
setter throws [`GetChatException`](#errors) instead of trimming, so a typo fails
at the call site rather than becoming a right that silently never applies.
`set("edit_messages", "my:extra")` still works and is unchecked, if you want to
write the raw string yourself.

**Params belong to the signed link only.** One `Rights` object serves two
destinations, and they do not validate it the same way — the REST side has its
own schema (`ParticipantRights` in the backend's `openapi.yml`):

| | Signed link — `url(...)` | REST — `createChat`, `addParticipants` |
| --- | --- | --- |
| Enum rights | `value:param…`; only the head is checked | strict `none` / `my` / `any` — a params form is rejected |
| Boolean rights | go out as `1` / `0`, and a colon tail is dropped | go out as real JSON booleans |
| A `null` value | a boolean becomes `0`, an explicit *denial*; an enum is dropped | dropped — an override is only *set* here, never cleared |

So `editMessages(Scope.MY, "extra")` belongs on a [`User`](#user) you pass to
`url(...)`. The same rights on a [`Recipient`](#recipient) handed to `createChat`
or `addParticipants` fail validation on the server — this SDK does not check it
for you, so the params overloads are for the signing path.

Clearing a per-chat override — sending `null` and having the participant fall
back to the value baked into the signed link — is what the dedicated
`chat.updateParticipantRights` endpoint does. This SDK does not wrap it; reach it
through [`requestApi`](#calling-endpoints-the-sdk-does-not-wrap).

### `Button`

An inline button under a message — the input counterpart of
[`ButtonDetails`](#buttondetails), with which it shares its enums. Shorthand:
`Button.of(Button.Type.URL, "Open")`.

| Setter | What it sets |
| --- | --- |
| `type(Button.Type)` | `URL`, `CALL`, `LOCAL` or `REMOTE` |
| `label(String)` | Button text |
| `action(String)` | Action payload — what pressing the button does, interpreted per `type` |
| `state(Button.State)` | `DEFAULT`, `LOADING` or `DISABLED` |
| `style(Button.Style)` | `PRIMARY`, `POSITIVE`, `NEGATIVE` or `NEUTRAL` |
| `set(String, Object)` | Any field with no typed setter |

### `ChatsQuery`

Filters and paging for [`listChats`](#chats). Always set a `limit` — without one
the server sends back a single chat.

| Setter | What it sets |
| --- | --- |
| `page(int)` / `limit(int)` | Paging; `page` ≥ 1 and `limit` in `1..1000`, checked at `build()` |
| `type(Chat.Type)` / `type(String)` | Only chats of one kind |
| `owner(String)` | Only chats owned by one user |
| `createdFrom` / `createdTo` | Creation-time window; takes a `LocalDateTime` or the wire string |
| `lastMessageFrom` / `lastMessageTo` | Last-message window, same two forms |
| `withOwner(boolean)` | Embed each chat's owner, which fills [`ChatDetails.owner()`](#chatdetails) |
| `withOwners(boolean)` | Put the owners in a separate `users` map on the raw response instead |
| `metadata(Map<String, Object>)` | Filter by metadata key/value pairs |
| `set(String, Object)` | Any query key with no typed setter |

The date setters format a `LocalDateTime` to the strict
`yyyy-MM-dd'T'HH:mm:ss` the backend requires — no timezone, seconds precision.

### `MessagesQuery`

Filters and paging for [`listMessages`](#messages).

| Setter | What it sets |
| --- | --- |
| `page(int)` / `limit(int)` | Paging, the same ranges as `ChatsQuery` |
| `deleted(boolean)` | `true` — only deleted messages; `false` — only live ones |
| `edited(boolean)` | `true` — only edited messages; `false` — only never-edited ones |
| `withUsers(boolean)` | Include the `users` map alongside the messages; read it through [`Page.raw()`](#paget) |
| `extra(String key, Object value)` / `extra(Map<String, Object>)` | Filter by the message's `extra` fields (scalars) |
| `set(String, Object)` | Any query key with no typed setter |

### `PageQuery`

Paging on its own, for [`listUserChats`](#users) and
[`listParticipants`](#participants), which take no other filters. It has
`page(int)` and `limit(int)`, validated at `build()` like the other two query
builders. Leave it out and the call defaults to page 1 with 50 items.

### `SendMessageOptions`

Everything optional about [`sendMessage`](#messages) — the chat, the user and the
text are passed as arguments, so they are not here.

| Setter | What it sets |
| --- | --- |
| `participant(Recipient)` | Adds one participant; call it again to add more |
| `participants(List<Recipient>)` | Replaces the participant list |
| `extra(String key, Object value)` / `extra(Map<String, Object>)` | Extra fields stored with the message |
| `buttons(Button...)` / `buttons(List<Map<String, Object>>)` | The message's inline buttons — see [`Button`](#button) |

### `UpdateMessageOptions`

The same for [`updateMessage`](#messages), plus how `extra` is applied and
whether the message comes back.

| Setter | What it sets |
| --- | --- |
| `extra(String key, Object value)` / `extra(Map<String, Object>)` | The message's `extra` fields |
| `extraMode(UpdateMessageOptions.ExtraMode)` | `MERGE` (the default) merges into the existing `extra`; `REPLACE` overwrites it wholesale |
| `buttons(Button...)` / `buttons(List<Map<String, Object>>)` | Replaces the message's buttons |
| `returnResource(boolean)` | Ask for the updated message back, which fills [`UpdatedMessage.message()`](#updatedmessage) |
| `set(String, Object)` | A field on the `message` object with no typed setter, such as `set("is_deleted", true)`; applied after the typed ones, so it can override them |

### `CreateChatOptions`

One setter, `returnResource(boolean)`. Turning it on sends
`Prefer: return=representation`, so the [`ChatDetails`](#chatdetails) you get
back is filled in instead of empty. Off by default, and passing `null` options
sends no header either.

### `UpdateChatOptions`

`returnResource(boolean)`, exactly as in [`CreateChatOptions`](#createchatoptions)
— it fills the [`ChatDetails`](#chatdetails) returned by `updateChat`.

### `CreateUserOptions`

`returnResource(boolean)` — fills the [`UserDetails`](#userdetails) returned by
`createUser`.

### `UpdateUserOptions`

`returnResource(boolean)` — fills the [`UserDetails`](#userdetails) returned by
`updateUser`.

## Working with results

### Pages

Every list method returns a [`Page<T>`](#paget). A `Page<T>` is iterable, so you can loop
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

[`Page<T>`](#paget), the models it holds — [`ChatDetails`](#chatdetails),
[`Message`](#message), [`UserDetails`](#userdetails), [`Participant`](#participant) —
and the write results [`SentMessages`](#sentmessages) and
[`UpdatedMessage`](#updatedmessage) are read-only wrappers over the returned JSON. Each accessor reads its field when you call it, every model has a
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
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole response, for fields without a typed accessor |

#### `ChatDetails`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | Chat id; empty string if the server ever omits it |
| `type()` | `Chat.Type` | Chat type, or `null` for a chat with no type or a type this SDK version does not recognise |
| `title()` | `String` | Chat title, or `null` when unset |
| `createdAt()` | `Instant` | When the chat was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the chat last changed, or `null` if the value cannot be read |
| `lastMessageAt()` | `Instant` | Time of the newest message, or `null` when the chat has none |
| `lastMessage()` | [`Message`](#message) | The newest message, or `null` unless it was requested (with `with_last_message`) |
| `ownerId()` | `String` | Owner id, or `null` when the chat has no owner |
| `owner()` | [`UserDetails`](#userdetails) | The chat owner, or `null` unless the chat carries an embedded owner (see the note) |
| `metadata()` | `Map<String, Object>` | Chat metadata as a map of scalar values (`String`, `Number` or `Boolean`); empty map when absent |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole chat object, for fields without a typed accessor |

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
| `extra()` | [`JsonValue`](#reading-a-jsonvalue) | The message's extra fields; empty value when absent |
| `buttons()` | [`List<ButtonDetails>`](#buttondetails) | The message's buttons, in order; empty list when it has none |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole message object, for fields without a typed accessor |

#### `UserDetails`

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | User id; empty string if the server ever omits it |
| `name()` | `String` | Display name; empty string if the server ever omits it |
| `email()` | `String` | Email, or `null` when unset |
| `link()` | `String` | Profile link, or `null` when unset |
| `picture()` | [`Avatar`](#avatar) | The user's avatar, or `null` when absent |
| `createdAt()` | `Instant` | When the user was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the user last changed, or `null` if the value cannot be read |
| `metadata()` | `Map<String, Object>` | User metadata as a map of scalar values (`String`, `Number` or `Boolean`); empty map when absent |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole user object, for fields without a typed accessor |

#### `Participant`

Carries a person's identity fields but, unlike [`UserDetails`](#userdetails), has **no
metadata**, and the participant list does not include a person's per-chat rights.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `id()` | `String` | User id; empty string if the server ever omits it |
| `name()` | `String` | Display name; empty string if the server ever omits it |
| `email()` | `String` | Email, or `null` when unset |
| `link()` | `String` | Profile link, or `null` when unset |
| `picture()` | [`Avatar`](#avatar) | The participant's avatar, or `null` when absent |
| `createdAt()` | `Instant` | When the participant was created, or `null` if the value cannot be read |
| `updatedAt()` | `Instant` | When the participant last changed, or `null` if the value cannot be read |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole participant object, for fields without a typed accessor |

#### `Avatar`

Returned by [`UserDetails.picture()`](#userdetails) and
[`Participant.picture()`](#participant). A person's avatar is one of two shapes: a
plain image URL, or a generated placeholder (initials on a coloured background).
`isUrl()` tells them apart — for a URL only `url()` is set; for a placeholder only
the object fields are.

| Accessor | Type | What it holds |
| --- | --- | --- |
| `isUrl()` | `boolean` | `true` for a URL avatar, `false` for a generated placeholder |
| `url()` | `String` | The image URL, or `null` for a placeholder |
| `kind()` | `String` | Placeholder kind (e.g. `"auto"`), or `null` for a URL |
| `color()` | `String` | Placeholder background colour, or `null` for a URL or when unset |
| `initials()` | `String` | Placeholder initials, or `null` for a URL or when unset |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole avatar value, for fields without a typed accessor |

#### `ButtonDetails`

A message's inline button, returned by [`Message.buttons()`](#message). The
reading counterpart of the `Button` input
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
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole button object, for fields without a typed accessor |

#### `SentMessages`

Returned by [`sendMessage`](#messages).

| Accessor | Type | What it holds |
| --- | --- | --- |
| `messageIds()` | `List<String>` | Ids of the messages just created, in send order; empty list when none |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole response, for fields without a typed accessor |

#### `UpdatedMessage`

Returned by [`updateMessage`](#messages).

| Accessor | Type | What it holds |
| --- | --- | --- |
| `isUpdated()` | `boolean` | Whether the edit actually changed the message |
| `message()` | [`Message`](#message) | The updated message, or `null` unless you asked for it with `returnResource(true)` |
| `raw()` | [`JsonValue`](#reading-a-jsonvalue) | The whole response, for fields without a typed accessor |

### Reading a `JsonValue`

[`requestApi`](#calling-endpoints-the-sdk-does-not-wrap) and every model's `raw()`
return a `JsonValue`: the SDK's own
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
and send it through `requestApi`, which returns a
[`JsonValue`](#reading-a-jsonvalue):

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

The typed input builders ([`ChatsQuery`](#chatsquery),
[`MessagesQuery`](#messagesquery), [`Chat`](#chat), [`User`](#user),
[`Recipient`](#recipient), [`Rights`](#rights), [`Button`](#button)) each have a
`set(key, value)` method for a field that has no typed setter, so you can send a
new field without waiting for the SDK to add one.

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

## Contributing

Building from source, the code formatter, and the rules around changing the
signing code are in [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT — see [LICENSE](LICENSE).
