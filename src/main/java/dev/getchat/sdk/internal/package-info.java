/**
 * Internal machinery: <strong>unsupported, and may change without notice.</strong>
 *
 * <p>Nothing here is part of the SDK's public contract. These classes reproduce
 * the node SDK's JavaScript signing semantics byte-for-byte (see the class docs
 * and {@code CLAUDE.md}); they are deliberately un-idiomatic because the bytes
 * they produce feed a signature the backend recomputes. Do not depend on them
 * from outside the SDK.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked}: type usages
 * are non-null unless annotated {@link org.jspecify.annotations.Nullable}. Note
 * that null flows freely through the JS-shaped value model (a JS
 * {@code null}/{@code undefined} is a Java {@code null}), so the {@code Object}
 * inputs and outputs that model JS values are annotated {@code @Nullable} where
 * null is a legal value.
 */
@NullMarked
package dev.getchat.sdk.internal;

import org.jspecify.annotations.NullMarked;
