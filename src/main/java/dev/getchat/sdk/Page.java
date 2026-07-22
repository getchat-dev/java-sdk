package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a paginated list response, in one of the two shapes
 * the backend uses for a list:
 *
 * <ul>
 *   <li>a <strong>map keyed by id</strong> ({@code chats} / {@code messages}) plus an
 *       ordered array of ids ({@code chats_sort} / {@code messages_sort}) — how
 *       {@code GET /chats} and {@code GET /chats/{id}/messages} serialise; or</li>
 *   <li>a plain <strong>array</strong> of elements ({@code participants} /
 *       {@code chats}) — how {@code GET /chats/{id}/participants} and
 *       {@code GET /users/{id}/chats} serialise (do not assume list endpoints are
 *       symmetric — these two differ from {@code GET /chats}).</li>
 * </ul>
 *
 * <p>{@link #items()} rebuilds the list in the server's order (from the {@code *_sort}
 * array for the map shape, from element order for the array shape), mapping each
 * element to the typed model {@code T}. Like the element models this is a lazy view —
 * nothing is deserialised until an accessor is called, and {@link #raw()} exposes the
 * whole envelope, including sibling maps such as {@code users} that stay on the raw
 * channel for now.
 *
 * <p>The pagination accessors expose exactly the fields the spec returns — there is
 * no {@code has_more} or {@code last_page}, and {@link #nextPageUrl()} /
 * {@link #prevPageUrl()} are {@code null} on the endpoints that omit them (the
 * participant list). Note the two distinct totals: {@link #pageCount()} is the number
 * of pages (the {@code pagination.total} field), while {@link #totalCount()} is the
 * number of matching items across all pages (the {@code meta.total} field). Beware
 * {@link #outputCount()} on the participant list — the backend never fills
 * {@code meta.output} there (it is always 0); count {@link #items()} instead.
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 *
 * @param <T> the element model, e.g. {@link ChatDetails}, {@link Message} or
 *            {@link Participant}
 */
public final class Page<T> {

    private final JsonValue raw;
    // Non-null for the id-map + id-array shape; null for the plain-array shape, in
    // which case listKey names the array of elements directly.
    private final @Nullable String sortKey;
    private final String listKey;
    private final Function<JsonValue, T> mapper;

    private Page(JsonValue raw, @Nullable String sortKey, String listKey, Function<JsonValue, T> mapper) {
        this.raw = raw;
        this.sortKey = sortKey;
        this.listKey = listKey;
        this.mapper = mapper;
    }

    /**
     * Build a page from the <em>map + sort-array</em> list envelope. Package-private:
     * pages are produced by the client's list methods, which supply the id-array key,
     * the id-map key and the element mapper for the specific endpoint.
     */
    static <T> Page<T> of(JsonValue raw, String sortKey, String mapKey, Function<JsonValue, T> mapper) {
        return new Page<>(raw, sortKey, mapKey, mapper);
    }

    /**
     * Build a page from a <em>plain-array</em> list envelope (as
     * {@code GET /chats/{id}/participants} and {@code GET /users/{id}/chats} return):
     * {@code arrayKey} names the array of elements, already in server order.
     */
    static <T> Page<T> ofArray(JsonValue raw, String arrayKey, Function<JsonValue, T> mapper) {
        return new Page<>(raw, null, arrayKey, mapper);
    }

    /** The whole list envelope, for fields without a typed accessor (e.g. {@code users}). */
    public JsonValue raw() {
        return raw;
    }

    /**
     * The page's elements as typed models, in the server's order. Unmodifiable and
     * never {@code null} — an empty list for a response that carries none. Rebuilt
     * on each call (the view holds no cached list).
     */
    public List<T> items() {
        if (sortKey == null) {
            // Plain-array shape: map each element in place, already in server order.
            List<JsonValue> elements = raw.get(listKey).values();
            List<T> out = new ArrayList<>(elements.size());
            for (JsonValue element : elements) {
                out.add(mapper.apply(element));
            }
            return Collections.unmodifiableList(out);
        }
        // Map + sort-array shape: walk the id order, looking each id up in the map.
        List<JsonValue> ids = raw.get(sortKey).values();
        JsonValue map = raw.get(listKey);
        List<T> out = new ArrayList<>(ids.size());
        for (JsonValue idNode : ids) {
            out.add(mapper.apply(map.get(idNode.asString(""))));
        }
        return Collections.unmodifiableList(out);
    }

    /** Items requested per page ({@code pagination.items_per_page}); 0 if absent. */
    public int itemsPerPage() {
        return raw.get("pagination").get("items_per_page").asInt(0);
    }

    /** The current page number ({@code pagination.current}); 0 when there are no results. */
    public int currentPage() {
        return raw.get("pagination").get("current").asInt(0);
    }

    /** The total number of pages ({@code pagination.total}); 0 if absent. */
    public int pageCount() {
        return raw.get("pagination").get("total").asInt(0);
    }

    /** The total number of matching items across all pages ({@code meta.total}); 0 if absent. */
    public int totalCount() {
        return raw.get("meta").get("total").asInt(0);
    }

    /** The number of items returned on this page ({@code meta.output}); 0 if absent. */
    public int outputCount() {
        return raw.get("meta").get("output").asInt(0);
    }

    /** URL of the next page, or {@code null} when this is the last page. */
    public @Nullable String nextPageUrl() {
        return pageUrl("next_page_url");
    }

    /** URL of the previous page, or {@code null} when this is the first page. */
    public @Nullable String prevPageUrl() {
        return pageUrl("prev_page_url");
    }

    private @Nullable String pageUrl(String field) {
        JsonValue v = raw.get("pagination").get(field);
        return v.isString() ? v.asString() : null;
    }

    /** Equal when the underlying JSON is equal. */
    @Override
    public boolean equals(@Nullable Object o) {
        return this == o || (o instanceof Page<?> other && raw.equals(other.raw));
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /** Compact summary for logs, e.g. {@code Page{items=20, page=1/3, total=57}}. */
    @Override
    public String toString() {
        // Element count from whichever channel carries the list for this shape.
        int itemCount = raw.get(sortKey == null ? listKey : sortKey).size();
        return "Page{items=" + itemCount
                + ", page=" + currentPage() + "/" + pageCount()
                + ", total=" + totalCount() + "}";
    }
}
