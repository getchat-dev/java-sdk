package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@link GetChat#createUser(User, CreateUserOptions)}.
 *
 * <p>Opt-in flags for the create call, kept in a single readable object so new
 * ones can be added without another overload. Every field is optional; the user is
 * passed to {@code createUser} directly.
 *
 * <pre>{@code
 * UserDetails user = sdk.createUser(
 *         User.builder().id("u-3").name("Carol").build(),
 *         CreateUserOptions.builder().returnResource(true).build());
 * // user.id(), user.name(), … are populated because a representation was requested.
 * }</pre>
 */
public final class CreateUserOptions {

    private final boolean returnResource;

    private CreateUserOptions(boolean returnResource) {
        this.returnResource = returnResource;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ── Package-private accessor used by GetChat.createUser ─────────────────────

    boolean returnResource() {
        return returnResource;
    }

    /** Equal to another instance carrying the same {@code returnResource} flag. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof CreateUserOptions other && returnResource == other.returnResource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(returnResource);
    }

    /** Compact dump for logs; carries no secrets, only the create flags. */
    @Override
    public String toString() {
        return "CreateUserOptions{returnResource=" + returnResource + "}";
    }

    /** Builder for {@link CreateUserOptions}. */
    public static final class Builder {

        private boolean returnResource;

        private Builder() {}

        /**
         * Ask the backend to echo the created user back by sending
         * {@code Prefer: return=representation}, so the returned
         * {@link UserDetails} is populated instead of an empty view. Named
         * {@code returnResource} uniformly across every options type in this SDK.
         * Defaults to {@code false}.
         */
        public Builder returnResource(boolean returnResource) {
            this.returnResource = returnResource;
            return this;
        }

        public CreateUserOptions build() {
            return new CreateUserOptions(returnResource);
        }
    }
}
