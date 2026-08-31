package com.ubp.rgd.proxy.flightsql;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.auth2.BasicCallHeaderAuthenticator;
import org.apache.arrow.flight.auth2.CallHeaderAuthenticator;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the credentials presented by the Flight SQL clients and hands out JDBC connections to the
 * proxied database on their behalf.
 * <p>
 * Clients authenticate with basic credentials that are validated by actually opening a connection to
 * the proxied database. The credentials are then kept, keyed by the Flight peer identity (the
 * username), so that every subsequent query runs under the caller's own database identity.
 */
@ApplicationScoped
public class FlightSqlConnectionManager implements BasicCallHeaderAuthenticator.CredentialValidator {

    private static final Logger LOG = Logger.getLogger(FlightSqlConnectionManager.class);

    @ConfigProperty(name = "proxy.flight-sql.jdbc-url", defaultValue = "")
    String jdbcUrl;

    private final Map<String, String> credentials = new ConcurrentHashMap<>();

    /**
     * Validate the basic credentials by opening a connection to the proxied database. On success the
     * credentials are memorized for the subsequent calls of that peer.
     *
     * @param username the database username
     * @param password the database password
     * @return the Flight authentication result carrying the username as peer identity
     */
    @Override
    public CallHeaderAuthenticator.AuthResult validate(String username, String password) {
        if (username == null || username.isBlank()) {
            throw CallStatus.UNAUTHENTICATED.withDescription("Missing username.").toRuntimeException();
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            if (!connection.isValid(5)) {
                throw CallStatus.UNAUTHENTICATED
                        .withDescription("Could not validate the database connection.").toRuntimeException();
            }
        } catch (SQLException e) {
            LOG.warnf("Flight SQL authentication failed for user %s: %s", username, e.getMessage());
            throw CallStatus.UNAUTHENTICATED
                    .withDescription("Database authentication failed: " + e.getMessage())
                    .toRuntimeException();
        }
        credentials.put(username, password == null ? "" : password);
        LOG.infof("Flight SQL client authenticated as %s", username);
        return () -> username;
    }

    /**
     * Open a new JDBC connection to the proxied database using the credentials of the given peer.
     *
     * @param peerIdentity the Flight peer identity (the database username)
     * @return a new connection, to be closed by the caller
     * @throws SQLException when the connection cannot be established
     */
    public Connection openConnection(String peerIdentity) throws SQLException {
        String password = peerIdentity == null ? null : credentials.get(peerIdentity);
        if (password == null) {
            throw CallStatus.UNAUTHENTICATED
                    .withDescription("No database credentials for the caller. Please re-authenticate.")
                    .toRuntimeException();
        }
        return DriverManager.getConnection(jdbcUrl, peerIdentity, password);
    }

    /**
     * Forget the credentials of a peer.
     * @param peerIdentity the Flight peer identity
     */
    public void forget(String peerIdentity) {
        if (peerIdentity != null) {
            credentials.remove(peerIdentity);
        }
    }
}
