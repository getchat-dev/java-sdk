package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A typed, read-only view over a paginated list response — the shape both
 * {@code GET /chats} and {@code GET /chats/{id}/messages} return.
 *
 * <p>The backend serialises these lists as a <em>map</em> keyed by id
 * ({@code chats} / {@code messages}) plus an ordered array of ids
 * ({@code chats_sort} / {@code messages_sort}); {@link #items()} rebuilds the list
 * in the server's order, mapping each element to the typed model {@code T}. Like the
 * element models this is a lazy view — nothing is deserialised until an accessor is
 * called, and {@link #raw()} exposes the whole envelope, including sibling maps such
 * as {@code users} that stay on the raw channel for now.
 *
 * <p>The pagination accessors expose exactly the fields the spec returns — there is
 * no {@code has_more} or {@code last_page}. Note the two distinct totals:
 * {@link #pageCount()} is the number of pages (the {@code pagination.total} field),
 * while {@link #totalCount()} is the number of matching items across all pages (the
 * {@code meta.total} field).
 *
 * <p>Two instances are equal when their underlying JSON is equal.
 *
 * @param <T> the element model, {@link ChatDetails} or {@link Message}
 */
public final class Page<T> {

    private final JsonValue raw;
    private final String sortKey;
    private final String mapKey;
    private final Function<JsonValue, T> mapper;

    private Page(JsonValue raw, String sortKey, String mapKey, Function<JsonValue, T> mapper) {
        this.raw = raw;
        this.sortKey = sortKey;
        this.mapKey = mapKey;
        this.mapper = mapper;
    }

    /**
     * Build a page from a list envelope. Package-private: pages are produced by the
     * client's list methods, which supply the id-array key, the id-map key and the
     * element mapper for the specific endpoint.
     */
    static <T> Page<T> of(JsonValue raw, String sortKey, String mapKey, Function<JsonValue, T> mapper) {
        return new Page<>(raw, sortKey, mapKey, mapper);
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
        List<JsonValue> ids = raw.get(sortKey).values();
        JsonValue map = raw.get(mapKey);
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
        return "Page{items=" + raw.get(sortKey).size()
                + ", page=" + currentPage() + "/" + pageCount()
                + ", total=" + totalCount() + "}";
    }
}
