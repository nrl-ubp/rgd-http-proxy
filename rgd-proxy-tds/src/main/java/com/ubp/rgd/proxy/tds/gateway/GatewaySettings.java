package com.ubp.rgd.proxy.tds.gateway;

import java.time.Duration;
import java.util.Locale;

/**
 * The settings of the sessions of the gateway.
 *
 * @param backendHost       the database server
 * @param backendPort       its port
 * @param backendEncryption how the leg towards the database server is encrypted
 * @param backendVerifyHost whether the certificate of the database server must name its host
 * @param clientEncryption  what the gateway asks of the clients
 * @param connectTimeout    the connection timeout towards the database server
 */
public record GatewaySettings(String backendHost, int backendPort, BackendEncryption backendEncryption,
                              boolean backendVerifyHost, ClientEncryption clientEncryption, Duration connectTimeout) {

    /** How the leg towards the database server is encrypted. */
    public enum BackendEncryption {
        /** TDS 7.4, all the traffic encrypted. */
        ON,
        /** TDS 7.4, only the login encrypted, unless the server requires more. */
        LOGIN_ONLY,
        /** TDS 8.0 strict: TLS first. */
        STRICT;

        /** @param value {@code true}, {@code false} or {@code strict} */
        public static BackendEncryption parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "true", "on", "mandatory" -> ON;
                case "false", "off", "optional" -> LOGIN_ONLY;
                case "strict" -> STRICT;
                default -> throw new IllegalArgumentException("proxy.tds.backend.encrypt must be true, false or strict: "
                        + value);
            };
        }
    }

    /** What the gateway asks of the clients, with TDS 7.4 negotiated encryption. */
    public enum ClientEncryption {
        /** All the traffic encrypted; a client that cannot encrypt is refused. */
        REQUIRED,
        /** As the client asks; at least the login when the client supports encryption. */
        OPTIONAL,
        /** No encryption, a client requiring it being refused. TDS 8.0 strict clients are still accepted. */
        OFF;

        public static ClientEncryption parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "required" -> REQUIRED;
                case "optional" -> OPTIONAL;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("proxy.tds.encryption must be required, optional or off: "
                        + value);
            };
        }
    }
}
