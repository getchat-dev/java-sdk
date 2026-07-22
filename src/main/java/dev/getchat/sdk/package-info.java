/**
 * The public GetChat Java SDK: signed embed URLs and a REST API client.
 *
 * <p>Start at {@link dev.getchat.sdk.GetChat}, constructed from a
 * {@link dev.getchat.sdk.GetChatConfig}. The typed input builders
 * ({@link dev.getchat.sdk.User}, {@link dev.getchat.sdk.Chat},
 * {@link dev.getchat.sdk.Recipient}, {@link dev.getchat.sdk.Rights},
 * {@link dev.getchat.sdk.UrlOptions} and the request-shaping builders) produce
 * the wire payloads. Chat and message reads come back as typed models
 * ({@link dev.getchat.sdk.ChatDetails}, {@link dev.getchat.sdk.Message} and the
 * generic {@link dev.getchat.sdk.Page}); other endpoints, {@code requestApi} and
 * every model's {@code raw()} return {@link dev.getchat.sdk.JsonValue}.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked}: every type
 * usage is non-null unless annotated {@link org.jspecify.annotations.Nullable}.
 * The annotations are a contract for callers and their tooling; the SDK wires no
 * runtime null checker of its own.
 */
@NullMarked
package dev.getchat.sdk;

import org.jspecify.annotations.NullMarked;
