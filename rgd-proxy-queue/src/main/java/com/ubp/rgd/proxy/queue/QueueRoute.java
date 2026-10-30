package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.queue.config.QueueRouteConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runtime state of a route: the state of each of its consumers, its counters and its meters.
 * <p>
 * Meters, tagged with the route name:
 * <ul>
 *   <li>{@code ubp_proxy_queue_messages{outcome=transformed|error_queue|rolled_back}}</li>
 *   <li>{@code ubp_proxy_queue_transform}: the duration of the transformations</li>
 *   <li>{@code ubp_proxy_queue_route_up}: 1 while every consumer is connected</li>
 * </ul>
 */
public final class QueueRoute {

    /** The state of a consumer, or of the route as a whole. */
    public enum State {
        /** Connecting for the first time. */
        STARTING,
        /** Connected, consuming the input queue. */
        RUNNING,
        /** Failed, waiting before connecting again. */
        RECONNECTING,
        /** Stopped with the application. */
        STOPPED
    }

    static final String MESSAGES_METER = "ubp_proxy_queue_messages";
    static final String TRANSFORM_METER = "ubp_proxy_queue_transform";
    static final String UP_METER = "ubp_proxy_queue_route_up";

    private final QueueRouteConfig config;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final State[] states;
    private final int[] consecutiveFailures;

    private final AtomicLong transformed = new AtomicLong();
    private final AtomicLong sentToErrorQueue = new AtomicLong();
    private final AtomicLong rolledBack = new AtomicLong();
    private volatile Instant lastMessage;
    private volatile String lastError;
    private volatile Instant lastErrorTime;

    private final Counter transformedCounter;
    private final Counter errorQueueCounter;
    private final Counter rolledBackCounter;
    private final Timer transformTimer;

    /**
     * @param config       the route
     * @param initialDelay the first delay before reconnecting
     * @param maxDelay     the longest delay before reconnecting
     * @param registry     where the meters are registered, {@code null} for none
     */
    public QueueRoute(QueueRouteConfig config, Duration initialDelay, Duration maxDelay, MeterRegistry registry) {
        this.config = config;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.states = new State[config.getConcurrency()];
        Arrays.fill(states, State.STARTING);
        this.consecutiveFailures = new int[config.getConcurrency()];

        if (registry != null) {
            Tags tags = Tags.of("route", config.getName());
            transformedCounter = registry.counter(MESSAGES_METER, tags.and("outcome", "transformed"));
            errorQueueCounter = registry.counter(MESSAGES_METER, tags.and("outcome", "error_queue"));
            rolledBackCounter = registry.counter(MESSAGES_METER, tags.and("outcome", "rolled_back"));
            transformTimer = registry.timer(TRANSFORM_METER, tags);
            Gauge.builder(UP_METER, this, route -> route.state() == State.RUNNING ? 1 : 0)
                    .tags(tags).register(registry);
        } else {
            transformedCounter = null;
            errorQueueCounter = null;
            rolledBackCounter = null;
            transformTimer = null;
        }
    }

    public QueueRouteConfig config() {
        return config;
    }

    public String name() {
        return config.getName();
    }

    /** @return the worst state of the consumers */
    public synchronized State state() {
        if (Arrays.stream(states).allMatch(s -> s == State.STOPPED)) {
            return State.STOPPED;
        }
        if (Arrays.stream(states).anyMatch(s -> s == State.RECONNECTING)) {
            return State.RECONNECTING;
        }
        if (Arrays.stream(states).anyMatch(s -> s == State.STARTING)) {
            return State.STARTING;
        }
        return State.RUNNING;
    }

    /** @return how many consumers are connected */
    public synchronized int connectedConsumers() {
        return (int) Arrays.stream(states).filter(s -> s == State.RUNNING).count();
    }

    synchronized void connected(int consumer) {
        states[consumer] = State.RUNNING;
    }

    /** A receive found no message: the queue manager answers, the next failure starts a new backoff. */
    synchronized void idle(int consumer) {
        consecutiveFailures[consumer] = 0;
    }

    synchronized void transformed(int consumer, Duration transformation) {
        consecutiveFailures[consumer] = 0;
        transformed.incrementAndGet();
        lastMessage = Instant.now();
        if (transformedCounter != null) {
            transformedCounter.increment();
            transformTimer.record(transformation);
        }
    }

    synchronized void sentToErrorQueue(int consumer, Throwable failure) {
        consecutiveFailures[consumer] = 0;
        sentToErrorQueue.incrementAndGet();
        lastMessage = Instant.now();
        recordError(failure);
        if (errorQueueCounter != null) {
            errorQueueCounter.increment();
        }
    }

    /**
     * A message that cannot be transformed was rolled back: it comes back at once, so the consumer
     * waits like after a failure before receiving again.
     *
     * @return how long to wait
     */
    synchronized Duration rolledBack(int consumer, Throwable failure) {
        rolledBack.incrementAndGet();
        lastMessage = Instant.now();
        recordError(failure);
        if (rolledBackCounter != null) {
            rolledBackCounter.increment();
        }
        return backoff(++consecutiveFailures[consumer]);
    }

    /**
     * A consumer failed: it rolls back, disconnects and waits before reconnecting.
     *
     * @return how long to wait
     */
    synchronized Duration failed(int consumer, Throwable failure) {
        if (states[consumer] != State.STOPPED) {
            states[consumer] = State.RECONNECTING;
        }
        recordError(failure);
        return backoff(++consecutiveFailures[consumer]);
    }

    synchronized void stopped() {
        Arrays.fill(states, State.STOPPED);
    }

    /** @return {@code initialDelay * 2^(failures - 1)}, at most {@code maxDelay} */
    Duration backoff(int failures) {
        int exponent = Math.min(Math.max(failures - 1, 0), 30);
        long millis = initialDelay.toMillis() << exponent;
        return millis <= 0 || millis > maxDelay.toMillis() ? maxDelay : Duration.ofMillis(millis);
    }

    private void recordError(Throwable failure) {
        Throwable root = FailureClassifier.rootCause(failure);
        lastError = root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
        lastErrorTime = Instant.now();
    }

    /** @return the state and counters of the route, without any credentials */
    public synchronized QueueRouteStatus status() {
        return new QueueRouteStatus(
                config.getName(),
                state().name(),
                config.getConcurrency(),
                connectedConsumers(),
                config.getInput().describe(),
                config.getOutput().describe(),
                config.getError() == null ? null : config.getError().describe(),
                config.unprotects() ? "Unprotect" : "Protect",
                transformed.get(),
                sentToErrorQueue.get(),
                rolledBack.get(),
                lastMessage,
                lastError,
                lastErrorTime);
    }
}
