package dev.getchat.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Pagination for the plain list endpoints — {@link GetChatClient#listParticipants} and
 * {@link GetChatClient#listUserChats} — a {@code page}/{@code limit} pair and nothing
 * else.
 *
 * <p>Both fields are optional. An unset field falls back to the calling method's
 * default (page 1, 50 items per page), so
 * {@code PageQuery.builder().limit(100).build()} keeps the default page. The
 * endpoint then clamps the values it receives — {@code page} up to at least 1,
 * {@code limit} down to at most 1000.
 *
 * <p>Unlike the richer query builders ({@link ChatsQuery}, {@link MessagesQuery})
 * this type carries <strong>no {@code set(key, value)} escape hatch</strong>, by
 * design: it is deliberately narrow — exactly {@code page} and {@code limit}. An
 * endpoint that needs more filters has its own typed query object; for anything
 * the SDK does not wrap, reach the transport through
 * {@link GetChatClient#requestApi(ApiRequest)}.
 */
public final class PageQuery {

    private final @Nullable Integer page;
    private final @Nullable Integer limit;

    private PageQuery(@Nullable Integer page, @Nullable Integer limit) {
        this.page = page;
        this.limit = limit;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The requested page, or {@code null} when unset (the method default applies). */
    public @Nullable Integer page() {
        return page;
    }

    /** The requested page size, or {@code null} when unset (the method default applies). */
    public @Nullable Integer limit() {
        return limit;
    }

    /** Equal to another {@code PageQuery} carrying the same page/limit (nulls included). */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o
                || (o instanceof PageQuery other
                        && Objects.equals(page, other.page)
                        && Objects.equals(limit, other.limit));
    }

    @Override
    public int hashCode() {
        return Objects.hash(page, limit);
    }

    /** Compact dump for logs; an unset field shows as {@code null}. */
    @Override
    public String toString() {
        return "PageQuery{page=" + page + ", limit=" + limit + "}";
    }

    /** Builder for {@link PageQuery}. */
    public static final class Builder {

        private @Nullable Integer page;
        private @Nullable Integer limit;

        private Builder() {}

        /** Page number; must be at least 1, checked at {@link #build()}. Unset uses the method default. */
        public Builder page(int page) {
            this.page = page;
            return this;
        }

        /** Items per page; must be in {@code 1..1000}, checked at {@link #build()}. Unset uses the method default. */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public PageQuery build() {
            // Validate the explicitly-set page/limit. PageQuery has no set() escape
            // hatch, so an out-of-range value can only be a caller mistake; the
            // GetChatClient clamps stay as a uniform second-line defence anyway.
            if (page != null && page < 1) {
                throw new GetChatException("page must be at least 1");
            }
            if (limit != null && (limit < 1 || limit > 1000)) {
                throw new GetChatException("limit must be between 1 and 1000");
            }
            return new PageQuery(page, limit);
        }
    }
}
