package dev.getchat.sdk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
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
 * <p>{@link #items()} returns the elements in the server's order (from the
 * {@code *_sort} array for the map shape, from element order for the array shape),
 * each mapped to the typed model {@code T}. The ordered list is assembled once, when
 * the page is built, and reused by {@link #items()}, {@link #iterator()},
 * {@link #stream()}, {@link #size()} and {@link #isEmpty()}. Assembling it only wraps
 * each element in its typed model, so the models themselves stay lazy views — a model
 * reads its fields when you call an accessor, not before — and {@link #raw()} exposes
 * the whole envelope, including sibling maps such as {@code users} that stay on the
 * raw channel for now.
 *
 * <p>A page is {@link Iterable}, so you can loop over it directly:
 * {@code for (ChatDetails c : page) { ... }}.
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
public final class Page<T> implements Iterable<T> {

    private final JsonValue raw;
    // The typed elements in server order, assembled once at construction (the
    // underlying JsonValue is immutable, so this list is safe to compute eagerly and
    // share). items()/iterator()/stream()/size()/isEmpty() all serve it.
    private final List<T> items;

    private Page(JsonValue raw, List<T> items) {
        this.raw = raw;
        this.items = items;
    }

    /**
     * Build a page from the <em>map + sort-array</em> list envelope. Package-private:
     * pages are produced by the client's list methods, which supply the id-array key,
     * the id-map key and the element mapper for the specific endpoint.
     */
    static <T> Page<T> of(JsonValue raw, String sortKey, String mapKey, Function<JsonValue, T> mapper) {
        // Walk the id order, looking each id up in the map, and map to the model.
        List<JsonValue> ids = raw.get(sortKey).values();
        JsonValue map = raw.get(mapKey);
        List<T> out = new ArrayList<>(ids.size());
        for (JsonValue idNode : ids) {
            out.add(mapper.apply(map.get(idNode.asString(""))));
        }
        return new Page<>(raw, Collections.unmodifiableList(out));
    }

    /**
     * Build a page from a <em>plain-array</em> list envelope (as
     * {@code GET /chats/{id}/participants} and {@code GET /users/{id}/chats} return):
     * {@code arrayKey} names the array of elements, already in server order.
     */
    static <T> Page<T> ofArray(JsonValue raw, String arrayKey, Function<JsonValue, T> mapper) {
        // Map each element in place, already in server order.
        List<JsonValue> elements = raw.get(arrayKey).values();
        List<T> out = new ArrayList<>(elements.size());
        for (JsonValue element : elements) {
            out.add(mapper.apply(element));
        }
        return new Page<>(raw, Collections.unmodifiableList(out));
    }

    /** The whole list envelope, for fields without a typed accessor (e.g. {@code users}). */
    public JsonValue raw() {
        return raw;
    }

    /**
     * The page's elements as typed models, in the server's order. Unmodifiable and
     * never {@code null} — an empty list for a response that carries none. The same
     * list every call (it is assembled once when the page is built).
     */
    public List<T> items() {
        return items;
    }

    /**
     * Iterates the page's elements in the server's order, the same order and elements
     * as {@link #items()}. Lets a page be used directly in a for-each loop, e.g.
     * {@code for (ChatDetails c : page) { ... }}.
     */
    @Override
    public Iterator<T> iterator() {
        return items.iterator();
    }

    /** A sequential {@link Stream} over the page's elements, in {@link #items()} order. */
    public Stream<T> stream() {
        return items.stream();
    }

    /** {@code true} when this page carries no elements ({@link #size()} is 0). */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * The number of elements on <strong>this</strong> page. Not the same as
     * {@link #totalCount()} (matching items across all pages) or {@link #pageCount()}
     * (the number of pages) — this is just {@code items().size()}.
     */
    public int size() {
        return items.size();
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
        return "Page{items=" + items.size()
                + ", page=" + currentPage() + "/" + pageCount()
                + ", total=" + totalCount() + "}";
    }
}
