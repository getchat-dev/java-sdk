/**
 * The GetChat Java SDK: signed embed URLs and a REST API client.
 *
 * <p>Only the public API package {@code dev.getchat.sdk} is exported. The
 * {@code dev.getchat.sdk.internal} package holds the signing, query-string and
 * transport machinery; it is deliberately <strong>not</strong> exported, so on the
 * module path it is unreachable to consumers and free to change without notice.
 */
module dev.getchat.sdk {
    // Transitive because HttpClient appears on the public API (the client builder's
    // httpClient(...) setter), so consumers that read this module read java.net.http too.
    requires transitive java.net.http;
    // Jackson is an internal detail — it never appears on a public signature — so it
    // is a plain (non-transitive) requires.
    requires com.fasterxml.jackson.databind;
    // Annotation-only, optional at runtime, and re-exported so consumers that read
    // this module also read the JSpecify nullability annotations on its API.
    requires static transitive org.jspecify;

    exports dev.getchat.sdk;
    // dev.getchat.sdk.internal is deliberately NOT exported.
}
