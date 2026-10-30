package com.ubp.rgd.proxy.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static com.ubp.rgd.proxy.queue.TestBrokers.endpoint;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueueRouteTest {

    private final QueueRoute route = new QueueRoute(
            QueueProxyServiceTest.route("r", "Protect", endpoint("QM1", "IN"), endpoint("QM1", "OUT"), null),
            Duration.ofSeconds(1), Duration.ofSeconds(60), null);

    @Test
    @DisplayName("The reconnection delay doubles up to the maximum, and starts over once the route works")
    void backoffGrowsAndResets() {
        IOException down = new IOException("down");
        assertEquals(Duration.ofSeconds(1), route.failed(0, down));
        assertEquals(Duration.ofSeconds(2), route.failed(0, down));
        assertEquals(Duration.ofSeconds(4), route.failed(0, down));
        for (int i = 0; i < 40; i++) {
            route.failed(0, down);
        }
        assertEquals(Duration.ofSeconds(60), route.failed(0, down));

        route.idle(0);
        assertEquals(Duration.ofSeconds(1), route.failed(0, down));
    }

    @Test
    @DisplayName("The route is running only once every consumer is connected")
    void stateIsTheWorstOfTheConsumers() {
        assertEquals(QueueRoute.State.STARTING, route.state());
        route.connected(0);
        assertEquals(QueueRoute.State.RUNNING, route.state());
        route.failed(0, new IOException("down"));
        assertEquals(QueueRoute.State.RECONNECTING, route.state());
        route.stopped();
        assertEquals(QueueRoute.State.STOPPED, route.state());
    }
}
