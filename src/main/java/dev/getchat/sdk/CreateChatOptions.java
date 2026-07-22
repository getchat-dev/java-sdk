package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChat#createChat(Chat, java.util.List, CreateChatOptions)}.
 *
 * <p>Opt-in flags for the create call, kept in a single readable object so new
 * ones can be added without another overload. Every field is optional; the chat
 * and its participants are passed to {@code createChat} directly.
 *
 * <pre>{@code
 * ChatDetails chat = sdk.createChat(
 *         Chat.builder().id("support-42").title("Support").type(Chat.Type.GROUP).build(),
 *         List.of(Recipient.of("u-1", "Alice")),
 *         CreateChatOptions.builder().returnResource(true).build());
 * // chat.id(), chat.title(), … are populated because a representation was requested.
 * }</pre>
 */
public final class CreateChatOptions {

    private final boolean returnResource;

    private CreateChatOptions(boolean returnResource) {
        this.returnResource = returnResource;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessor used by GetChat.createChat ────────────────────

    boolean returnResource() {
        return returnResource;
    }

    /** Equal to another instance carrying the same {@code returnResource} flag. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof CreateChatOptions other && returnResource == other.returnResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnResource);
    }

    /** Compact dump for logs; carries no secrets, only the create flags. */
    @Override
    public String toString() {
        return "CreateChatOptions{returnResource=" + returnResource + "}";
    }

    /** Builder for {@link CreateChatOptions}. */
    public static final class Builder {

        private boolean returnResource;

        private Builder() {}

        /**
         * Ask the backend to echo the created chat back by sending
         * {@code Prefer: return=representation}, so the returned
         * {@link ChatDetails} is populated instead of an empty view. Named
         * {@code returnResource} uniformly across every options type in this SDK.
         * Defaults to {@code false}.
         */
        public Builder returnResource(boolean returnResource) {
            this.returnResource = returnResource;
            return this;
        }

        public CreateChatOptions build() {
            return new CreateChatOptions(returnResource);
        }
    }
}
