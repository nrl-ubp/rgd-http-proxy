package com.ubp.rgd.proxy.tds.transport;

import com.ubp.rgd.proxy.tds.protocol.Tds;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * TLS on one leg of a TDS connection, in the three ways TDS uses it:
 * <ul>
 *     <li><b>TDS 7.x</b>: the handshake records travel inside PRELOGIN packets, the records that follow
 *     go on the TCP connection as they are;</li>
 *     <li><b>login only</b>: the same, but TLS stops once the LOGIN7 message went through it;</li>
 *     <li><b>TDS 8.0 strict</b>: TLS from the first byte, the ALPN protocol being {@code tds/8.0}.</li>
 * </ul>
 * The TDS 7.x handshake is limited to TLS 1.2, as SQL Server does: a TLS 1.3 server sends its session
 * tickets after the handshake, which the client would read outside of the PRELOGIN packets.
 */
public final class TlsLayer {

    private static final String[] TLS_12 = {"TLSv1.2"};

    private final ProxySocket proxy;
    private final SSLSocket ssl;
    private final InputStream rawInput;
    private final OutputStream rawOutput;

    private TlsLayer(ProxySocket proxy, SSLSocket ssl, InputStream rawInput, OutputStream rawOutput) {
        this.proxy = proxy;
        this.ssl = ssl;
        this.rawInput = rawInput;
        this.rawOutput = rawOutput;
    }

    /**
     * Accept the TLS handshake of a client.
     *
     * @param socket    the TCP connection
     * @param rawInput  its input, which may have been peeked at
     * @param rawOutput its output
     * @param strict    whether the client opened a TDS 8.0 strict connection
     */
    public static TlsLayer accept(SSLContext context, Socket socket, InputStream rawInput, OutputStream rawOutput,
                                  boolean strict) throws IOException {
        ProxySocket proxy = new ProxySocket(socket, rawInput, rawOutput);
        PreLoginWrappedStreams.Output wrappedOutput = null;
        if (!strict) {
            wrappedOutput = new PreLoginWrappedStreams.Output(rawOutput, Tds.DEFAULT_PACKET_SIZE);
            proxy.switchTo(new PreLoginWrappedStreams.Input(rawInput, wrappedOutput), wrappedOutput);
        }
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket(proxy, null, false);
        ssl.setUseClientMode(false);
        SSLParameters parameters = ssl.getSSLParameters();
        if (strict) {
            parameters.setApplicationProtocols(new String[]{Tds.ALPN_TDS_8});
        } else {
            parameters.setProtocols(TLS_12);
        }
        ssl.setSSLParameters(parameters);
        return handshake(proxy, ssl, rawInput, rawOutput, wrappedOutput);
    }

    /**
     * Open TLS towards the database server.
     *
     * @param host   the server host, checked against its certificate unless {@code verifyHost} is false
     * @param strict whether the connection is a TDS 8.0 strict one
     */
    public static TlsLayer connect(SSLContext context, Socket socket, String host, int port, boolean strict,
                                   boolean verifyHost) throws IOException {
        InputStream rawInput = socket.getInputStream();
        OutputStream rawOutput = socket.getOutputStream();
        ProxySocket proxy = new ProxySocket(socket, rawInput, rawOutput);
        PreLoginWrappedStreams.Output wrappedOutput = null;
        if (!strict) {
            wrappedOutput = new PreLoginWrappedStreams.Output(rawOutput, Tds.DEFAULT_PACKET_SIZE);
            proxy.switchTo(new PreLoginWrappedStreams.Input(rawInput, wrappedOutput), wrappedOutput);
        }
        SSLSocket ssl = (SSLSocket) context.getSocketFactory().createSocket(proxy, host, port, false);
        ssl.setUseClientMode(true);
        SSLParameters parameters = ssl.getSSLParameters();
        if (verifyHost) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        if (strict) {
            parameters.setApplicationProtocols(new String[]{Tds.ALPN_TDS_8});
        } else {
            parameters.setProtocols(TLS_12);
        }
        ssl.setSSLParameters(parameters);
        return handshake(proxy, ssl, rawInput, rawOutput, wrappedOutput);
    }

    private static TlsLayer handshake(ProxySocket proxy, SSLSocket ssl, InputStream rawInput, OutputStream rawOutput,
                                      PreLoginWrappedStreams.Output wrappedOutput) throws IOException {
        ssl.startHandshake();
        if (wrappedOutput != null) {
            // The last flight of the handshake, which nothing is read after.
            wrappedOutput.send();
        }
        // The records that follow the handshake go on the TCP connection as they are.
        proxy.switchTo(rawInput, rawOutput);
        return new TlsLayer(proxy, ssl, rawInput, rawOutput);
    }

    public InputStream input() throws IOException {
        return ssl.getInputStream();
    }

    public OutputStream output() throws IOException {
        return ssl.getOutputStream();
    }

    /** @return the negotiated ALPN protocol, empty when none */
    public String applicationProtocol() {
        String protocol = ssl.getApplicationProtocol();
        return protocol == null ? "" : protocol;
    }

    public String protocol() {
        return ssl.getSession().getProtocol();
    }

    /** The plaintext streams of the TCP connection, once TLS stopped after the login. */
    public InputStream rawInput() {
        return rawInput;
    }

    public OutputStream rawOutput() {
        return rawOutput;
    }
}
