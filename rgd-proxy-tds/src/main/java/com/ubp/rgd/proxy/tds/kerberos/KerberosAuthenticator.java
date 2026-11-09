package com.ubp.rgd.proxy.tds.kerberos;

/**
 * The Kerberos side of an integrated login: the gateway accepts the ticket of the client for its own
 * SPN, then logs in to the database server as the client through constrained delegation (S4U2Proxy).
 */
public interface KerberosAuthenticator {

    /** Start the exchange of one login. */
    Exchange start() throws KerberosLoginException;

    /** The security contexts of one login: the client's, accepted, and the database server's, initiated. */
    interface Exchange extends AutoCloseable {

        /**
         * Accept the SSPI blob of the client's LOGIN7.
         *
         * @return the token to send back to the client, possibly empty
         * @throws KerberosLoginException when the blob is not a Kerberos ticket of the gateway's SPN
         *                                (NTLM is refused), or needs more than one round trip
         */
        byte[] accept(byte[] clientToken) throws KerberosLoginException;

        /** @return the name of the authenticated client, once accepted */
        String clientName();

        /**
         * Initiate the context towards the database server with the delegated credential of the client.
         *
         * @return the SSPI blob of the LOGIN7 sent to the database server
         * @throws KerberosLoginException when no credential was delegated, or the KDC refuses S4U2Proxy
         */
        byte[] initiateBackend() throws KerberosLoginException;

        /**
         * Process a token of the database server.
         *
         * @return the token to send back to it, empty when there is none
         */
        byte[] continueBackend(byte[] serverToken) throws KerberosLoginException;

        @Override
        void close();
    }
}
