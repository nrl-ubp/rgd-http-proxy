package com.ubp.rgd.proxy.flightsql.deployment;

import io.quarkus.test.QuarkusUnitTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * An application made of the Flight SQL extension and the FPE transformer, which needs no remote service.
 */
final class FlightSqlWiringTestSupport {

    private FlightSqlWiringTestSupport() {
    }

    static QuarkusUnitTest application(boolean enabled, int port) {
        return new QuarkusUnitTest()
                // the tests run in the application class loader, which must see this helper too
                .withApplicationRoot(jar -> jar.addClass(FlightSqlWiringTestSupport.class))
                .overrideConfigKey("proxy.transform.impl", "FPE")
                .overrideConfigKey("proxy.transform.fpe.key", "2b7e151628aed2a6abf7158809cf4f3c")
                .overrideConfigKey("proxy.transform.config-file", "src/test/resources/empty_transform_config.json")
                // read by FlightSqlDetokenizeService whatever the transformer
                .overrideConfigKey("proxy.transform.client-id", "3150a79e-3084-4946-aeb5-e7e73aea3904")
                .overrideConfigKey("proxy.flight-sql.enabled", Boolean.toString(enabled))
                .overrideConfigKey("proxy.flight-sql.host", "localhost")
                .overrideConfigKey("proxy.flight-sql.port", Integer.toString(port))
                .overrideConfigKey("proxy.flight-sql.jdbc-url", "jdbc:h2:mem:flightsql-wiring")
                .overrideConfigKey("proxy.flight-sql.mapping-config-file",
                        "src/test/resources/flight_sql_mapping_config.json");
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
