package com.ubp.rgd.proxy.rps.deployment;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks that selecting an implementation whose extension is not in the application stops the
 * application at startup, naming the implementations that are available.
 */
class MissingTransformerTest {

    @RegisterExtension
    static final QuarkusUnitTest TEST = new QuarkusUnitTest()
            .overrideConfigKey("proxy.transform.impl", "FPE")
            .overrideConfigKey("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904")
            .overrideConfigKey("proxy.secrets-manager-lookup.mongodb.database", "rgd-test")
            .assertException(failure -> {
                Throwable cause = failure;
                while (cause.getCause() != null && !(cause instanceof IllegalStateException)) {
                    cause = cause.getCause();
                }
                assertInstanceOf(IllegalStateException.class, cause);
                assertTrue(cause.getMessage().contains("'FPE'") && cause.getMessage().contains("accepts RPS."),
                        cause.getMessage());
            });

    @Test
    @DisplayName("Selecting an absent implementation fails at startup")
    void startupFails() {
        fail("the application must not start");
    }
}
