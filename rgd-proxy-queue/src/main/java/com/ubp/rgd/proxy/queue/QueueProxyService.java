package com.ubp.rgd.proxy.queue;

import com.ubp.rgd.proxy.queue.config.QueueConfigLoader;
import com.ubp.rgd.proxy.queue.config.QueueConfiguration;
import com.ubp.rgd.proxy.queue.config.QueueRouteConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The IBM MQ queue proxy: runs a reactive pipeline per consumer of each route of
 * {@code proxy.queue.config-file} when {@code proxy.queue.enabled=true}.
 * <p>
 * A pipeline opens the sessions of the consumer, then repeatedly:
 * <ol>
 *   <li>receives a message from the input queue ({@link QueueListener}),</li>
 *   <li>transforms its payload ({@link QueueMessageTransformer}),</li>
 *   <li>sends the result to the output queue ({@link QueueProducer}),</li>
 *   <li>commits, which removes the message from the input queue.</li>
 * </ol>
 * A message that cannot be transformed goes to the error queue, or is rolled back when the route has
 * none. Any other failure (a queue manager or the engine unreachable) rolls the message back, closes the
 * sessions and opens them again after an exponential backoff, until the infrastructure is back.
 * <p>
 * Each consumer runs on its own thread: a Jakarta Messaging session must not be shared between threads,
 * and the receives, the transformation and the sends are blocking calls that must never run on an event
 * loop. The delays are scheduled on that same thread.
 */
@Startup
@ApplicationScoped
public class QueueProxyService {

    private static final Logger LOG = LoggerFactory.getLogger(QueueProxyService.class);

    /** What happened to a received message. */
    enum Outcome {
        TRANSFORMED, ERROR_QUEUE, ROLLED_BACK
    }

    private record RunningConsumer(ScheduledExecutorService executor, Cancellable subscription) {
    }

    /** A permanent failure of the transformation, as opposed to a failure to send or commit. */
    private static final class NotTransformableException extends RuntimeException {
        NotTransformableException(Throwable cause) {
            super(cause);
        }
    }

    @ConfigProperty(name = "proxy.queue.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "proxy.queue.config-file", defaultValue = "./config/mq_transform_config.json")
    String configFile;

    @ConfigProperty(name = "proxy.queue.receive-timeout", defaultValue = "1s")
    Duration receiveTimeout;

    @ConfigProperty(name = "proxy.queue.reconnect.initial-delay", defaultValue = "1s")
    Duration initialDelay;

    @ConfigProperty(name = "proxy.queue.reconnect.max-delay", defaultValue = "60s")
    Duration maxDelay;

    @Inject
    Config config;

    @Inject
    QueueConnections connections;

    @Inject
    QueueListener listener;

    @Inject
    QueueMessageTransformer transformer;

    @Inject
    QueueProducer producer;

    @Inject
    MeterRegistry registry;

    private QueueConfiguration configuration;
    private final List<QueueRoute> routes = new ArrayList<>();
    private final List<RunningConsumer> consumers = new ArrayList<>();

    @PostConstruct
    void init() {
        if (!enabled) {
            LOG.info("The queue proxy is disabled (proxy.queue.enabled=false).");
            return;
        }
        LOG.info("Loading the queue proxy routes from {}", configFile);
        start(QueueConfigLoader.load(configFile, name -> config.getOptionalValue(name, String.class)));
    }

    /** Starts the consumers of every route. */
    synchronized void start(QueueConfiguration configuration) {
        this.configuration = configuration;
        if (configuration.routes().isEmpty()) {
            LOG.warn("The queue proxy is enabled but {} has no route.", configFile);
        }
        for (QueueRouteConfig routeConfig : configuration.routes()) {
            QueueRoute route = new QueueRoute(routeConfig, initialDelay, maxDelay, registry);
            routes.add(route);
            LOG.info("Queue route '{}': {} -> {} ({}, {} consumer(s), error queue {})", route.name(),
                    routeConfig.getInput().describe(), routeConfig.getOutput().describe(),
                    routeConfig.unprotects() ? "Unprotect" : "Protect", routeConfig.getConcurrency(),
                    routeConfig.getError() == null ? "none: rollback" : routeConfig.getError().describe());
            if (routeConfig.getEntityTransformConfigs().isEmpty()) {
                LOG.warn("Queue route '{}' has no entity-transform-configs: its messages are forwarded unchanged.",
                        route.name());
            }
            for (int consumer = 0; consumer < routeConfig.getConcurrency(); consumer++) {
                ScheduledExecutorService executor = consumerThread(route.name(), consumer);
                int index = consumer;
                Cancellable subscription = pipeline(route, consumer, executor).subscribe().with(
                        outcome -> LOG.debug("Queue route '{}' consumer {}: {}", route.name(), index, outcome),
                        failure -> LOG.error("Queue route '{}' consumer {} stopped", route.name(), index, failure));
                consumers.add(new RunningConsumer(executor, subscription));
            }
        }
    }

    /**
     * The pipeline of one consumer: never completes, retries every failure after a backoff.
     */
    Multi<Outcome> pipeline(QueueRoute route, int consumer, ScheduledExecutorService executor) {
        return Multi.createFrom().resource(
                        () -> open(route, consumer),
                        session -> listener.messages(session, receiveTimeout, () -> route.idle(consumer))
                                .onItem().transformToUniAndConcatenate(
                                        message -> process(route, consumer, session, message, executor)))
                .withFinalizer((RouteSession session) -> closeOn(executor, session))
                // subscribing, and so receiving, happens on the consumer thread
                .runSubscriptionOn(executor)
                .onFailure().retry().when(failures -> failures.onItem().transformToUniAndConcatenate(failure -> {
                    Duration delay = route.failed(consumer, failure);
                    LOG.warn("Queue route '{}' consumer {} failed, reconnecting in {}s: {}",
                            route.name(), consumer, delay.toMillis() / 1000.0, describe(failure));
                    LOG.debug("Queue route '{}' consumer {} failure", route.name(), consumer, failure);
                    return Uni.createFrom().item(delay).onItem().delayIt().onExecutor(executor).by(delay);
                }));
    }

    private RouteSession open(QueueRoute route, int consumer) {
        try {
            RouteSession session = connections.open(route.config(), configuration);
            route.connected(consumer);
            LOG.info("Queue route '{}' consumer {} listening on {}", route.name(), consumer,
                    route.config().getInput().describe());
            return session;
        } catch (JMSException e) {
            throw new UncheckedJmsException(e);
        }
    }

    private Uni<Outcome> process(QueueRoute route, int consumer, RouteSession session, Message message,
                                 ScheduledExecutorService executor) {
        long start = System.nanoTime();
        return Uni.createFrom().<String>emitter(emitter -> {
                    try {
                        emitter.complete(transformer.transform(route.config(), message));
                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                })
                .onFailure(FailureClassifier::isPermanent).transform(NotTransformableException::new)
                .onItem().transformToUni(payload -> Uni.createFrom().<Outcome>emitter(emitter -> {
                    try {
                        Duration transformation = Duration.ofNanos(System.nanoTime() - start);
                        producer.send(session, message, payload);
                        session.commit();
                        route.transformed(consumer, transformation);
                        emitter.complete(Outcome.TRANSFORMED);
                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                }))
                .onFailure(NotTransformableException.class).recoverWithUni(
                        failure -> notTransformed(route, consumer, session, message, failure.getCause(), executor));
    }

    /**
     * The message itself cannot be transformed: to the error queue, or back on the input queue when
     * the route has no error queue (IBM MQ moves it to the backout queue after BOTHRESH attempts).
     */
    private Uni<Outcome> notTransformed(QueueRoute route, int consumer, RouteSession session, Message message,
                                        Throwable failure, ScheduledExecutorService executor) {
        if (session.hasErrorQueue() && producer.canCopyToErrorQueue(message)) {
            return Uni.createFrom().emitter(emitter -> {
                try {
                    producer.sendToErrorQueue(session, message, route.name(), failure);
                    session.commit();
                    route.sentToErrorQueue(consumer, failure);
                    LOG.warn("Queue route '{}': message {} cannot be transformed, sent to {}: {}", route.name(),
                            messageId(message), route.config().getError().describe(), describe(failure));
                    emitter.complete(Outcome.ERROR_QUEUE);
                } catch (Exception e) {
                    emitter.fail(e);
                }
            });
        }
        session.rollback();
        Duration delay = route.rolledBack(consumer, failure);
        LOG.error("Queue route '{}': message {} cannot be transformed and is rolled back (delivery {}), "
                        + "receiving again in {}s: {}", route.name(), messageId(message), deliveryCount(message),
                delay.toMillis() / 1000.0, describe(failure));
        return Uni.createFrom().item(Outcome.ROLLED_BACK).onItem().delayIt().onExecutor(executor).by(delay);
    }

    @PreDestroy
    synchronized void shutdown() {
        if (consumers.isEmpty()) {
            return;
        }
        LOG.info("Stopping the queue proxy");
        consumers.forEach(running -> running.subscription().cancel());
        consumers.forEach(running -> running.executor().shutdown());
        long timeout = receiveTimeout.toMillis() + TimeUnit.SECONDS.toMillis(30);
        for (RunningConsumer running : consumers) {
            try {
                if (!running.executor().awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                    running.executor().shutdownNow();
                }
            } catch (InterruptedException e) {
                running.executor().shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        connections.closeAll();
        routes.forEach(QueueRoute::stopped);
        consumers.clear();
    }

    public boolean enabled() {
        return enabled;
    }

    public synchronized List<QueueRoute> routes() {
        return List.copyOf(routes);
    }

    public synchronized Optional<QueueRoute> route(String name) {
        return routes.stream().filter(route -> route.name().equals(name)).findFirst();
    }

    /** The sessions are closed on the consumer thread, once the message in progress is done with. */
    private static void closeOn(ScheduledExecutorService executor, RouteSession session) {
        try {
            executor.execute(session::close);
        } catch (RejectedExecutionException e) {
            session.close();
        }
    }

    private static ScheduledExecutorService consumerThread(String route, int consumer) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "rgd-queue-" + route + "-" + consumer);
            thread.setDaemon(true);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static String describe(Throwable failure) {
        Throwable root = FailureClassifier.rootCause(failure);
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static String messageId(Message message) {
        try {
            return message.getJMSMessageID();
        } catch (JMSException e) {
            return "?";
        }
    }

    private static String deliveryCount(Message message) {
        try {
            return message.propertyExists("JMSXDeliveryCount")
                    ? String.valueOf(message.getIntProperty("JMSXDeliveryCount")) : "?";
        } catch (JMSException e) {
            return "?";
        }
    }
}
