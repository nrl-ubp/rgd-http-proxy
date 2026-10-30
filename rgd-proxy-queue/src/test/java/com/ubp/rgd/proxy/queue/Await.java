package com.ubp.rgd.proxy.queue;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

final class Await {

    private Await() {
    }

    static void until(String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting until " + what);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted waiting until " + what);
            }
        }
    }
}
