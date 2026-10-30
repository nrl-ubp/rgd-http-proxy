package com.ubp.rgd.proxy.queue;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * What {@code GET /api/queue/routes} tells about a route.
 *
 * @param name               the route name
 * @param state              STARTING, RUNNING, RECONNECTING or STOPPED
 * @param concurrency        the configured consumers
 * @param connectedConsumers the consumers currently connected
 * @param input              the input queue, as {@code QUEUE@QMGR(host:port)}
 * @param output             the output queue
 * @param error              the error queue, {@code null} when messages are rolled back instead
 * @param action             Protect or Unprotect
 * @param transformed        the messages transformed since startup
 * @param sentToErrorQueue   the messages sent to the error queue since startup
 * @param rolledBack         the messages rolled back because they could not be transformed
 * @param lastMessage        when the last message was processed
 * @param lastError          the last failure, of a message or of a connection
 * @param lastErrorTime      when it happened
 */
@RegisterForReflection
public record QueueRouteStatus(String name,
                               String state,
                               int concurrency,
                               int connectedConsumers,
                               String input,
                               String output,
                               String error,
                               String action,
                               long transformed,
                               long sentToErrorQueue,
                               long rolledBack,
                               Instant lastMessage,
                               String lastError,
                               Instant lastErrorTime) {
}
