package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChatClient#updateChat(String, Chat, UpdateChatOptions)}.
 *
 * <p>Opt-in flags for the update call, kept in a single readable object so new
 * ones can be added without another overload. Every field is optional; the chat
 * changes are passed to {@code updateChat} directly.
 *
 * <pre>{@code
 * ChatDetails chat = sdk.updateChat("support-42",
 *         Chat.builder().title("Renamed").build(),
 *         UpdateChatOptions.builder().returnResource(true).build());
 * // chat.title() is "Renamed" because a representation was requested.
 * }</pre>
 */
public final class UpdateChatOptions {

    private final boolean returnResource;

    private UpdateChatOptions(boolean returnResource) {
        this.returnResource = returnResource;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessor used by GetChatClient.updateChat ────────────────────

    boolean returnResource() {
        return returnResource;
    }

    /** Equal to another instance carrying the same {@code returnResource} flag. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof UpdateChatOptions other && returnResource == other.returnResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnResource);
    }

    /** Compact dump for logs; carries no secrets, only the update flags. */
    @Override
    public String toString() {
        return "UpdateChatOptions{returnResource=" + returnResource + "}";
    }

    /** Builder for {@link UpdateChatOptions}. */
    public static final class Builder {

        private boolean returnResource;

        private Builder() {}

        /**
         * Ask the backend to echo the updated chat back by sending
         * {@code Prefer: return=representation}, so the returned
         * {@link ChatDetails} is populated instead of an empty view. Named
         * {@code returnResource} uniformly across every options type in this SDK.
         * Defaults to {@code false}.
         */
        public Builder returnResource(boolean returnResource) {
            this.returnResource = returnResource;
            return this;
        }

        public UpdateChatOptions build() {
            return new UpdateChatOptions(returnResource);
        }
    }
}
