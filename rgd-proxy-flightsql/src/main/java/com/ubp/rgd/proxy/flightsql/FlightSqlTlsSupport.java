package com.ubp.rgd.proxy.flightsql;

import com.ubp.rgd.proxy.sqlgateway.TlsMaterialLoader;
import com.ubp.rgd.proxy.sqlgateway.TlsMaterialLoader.Settings;
import com.ubp.rgd.proxy.sqlgateway.TlsMaterialLoader.TlsMaterial;

/**
 * The TLS material of the Flight SQL server, read from the {@code proxy.flight-sql.tls.*} settings by
 * the {@link TlsMaterialLoader} shared with the TDS gateway.
 */
public final class FlightSqlTlsSupport {

    /** Names the Flight SQL server and its settings in the messages. */
    public static final TlsMaterialLoader.Feature FEATURE =
            new TlsMaterialLoader.Feature("Flight SQL", "proxy.flight-sql.tls.");

    private FlightSqlTlsSupport() {
    }

    /**
     * @throws TlsMaterialLoader.TlsConfigurationException when the settings are inconsistent or the
     *         material unusable
     */
    public static TlsMaterial load(Settings settings) {
        return TlsMaterialLoader.load(settings, FEATURE);
    }
}
