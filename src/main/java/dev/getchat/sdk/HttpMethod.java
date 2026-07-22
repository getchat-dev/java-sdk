package dev.getchat.sdk;

/**
 * The HTTP verbs the GetChat REST API uses, and the verb of an {@link ApiRequest}
 * (see {@link ApiRequest#method()}).
 */
public enum HttpMethod {
    GET,
    POST,
    PUT,
    DELETE;

    /** The verb as it goes on the wire (its {@link #name()}). */
    String wire() {
        return name();
    }

    /** Whether this verb carries a request body ({@code POST}/{@code PUT}). */
    boolean hasBody() {
        return this == POST || this == PUT;
    }
}
