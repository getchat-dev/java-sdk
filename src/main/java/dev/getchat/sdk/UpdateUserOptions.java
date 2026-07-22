package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChatClient#updateUser(String, User, UpdateUserOptions)}.
 *
 * <p>Opt-in flags for the update call, kept in a single readable object so new
 * ones can be added without another overload. Every field is optional; the user
 * changes are passed to {@code updateUser} directly.
 *
 * <pre>{@code
 * UserDetails user = sdk.updateUser("u-3",
 *         User.builder().name("Caroline").build(),
 *         UpdateUserOptions.builder().returnResource(true).build());
 * // user.name() is "Caroline" because a representation was requested.
 * }</pre>
 */
public final class UpdateUserOptions {

    private final boolean returnResource;

    private UpdateUserOptions(boolean returnResource) {
        this.returnResource = returnResource;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessor used by GetChatClient.updateUser ─────────────────────

    boolean returnResource() {
        return returnResource;
    }

    /** Equal to another instance carrying the same {@code returnResource} flag. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof UpdateUserOptions other && returnResource == other.returnResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnResource);
    }

    /** Compact dump for logs; carries no secrets, only the update flags. */
    @Override
    public String toString() {
        return "UpdateUserOptions{returnResource=" + returnResource + "}";
    }

    /** Builder for {@link UpdateUserOptions}. */
    public static final class Builder {

        private boolean returnResource;

        private Builder() {}

        /**
         * Ask the backend to echo the updated user back by sending
         * {@code Prefer: return=representation}, so the returned
         * {@link UserDetails} is populated instead of an empty view. Named
         * {@code returnResource} uniformly across every options type in this SDK.
         * Defaults to {@code false}.
         */
        public Builder returnResource(boolean returnResource) {
            this.returnResource = returnResource;
            return this;
        }

        public UpdateUserOptions build() {
            return new UpdateUserOptions(returnResource);
        }
    }
}
