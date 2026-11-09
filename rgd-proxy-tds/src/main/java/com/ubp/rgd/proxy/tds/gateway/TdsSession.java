package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.SqlTransformRewriter.SqlTransformSyntaxException;
import com.ubp.rgd.proxy.tds.kerberos.KerberosAuthenticator;
import com.ubp.rgd.proxy.tds.kerberos.KerberosLoginException;
import com.ubp.rgd.proxy.tds.protocol.Login7;
import com.ubp.rgd.proxy.tds.protocol.MessageInputStream;
import com.ubp.rgd.proxy.tds.protocol.MessageOutputStream;
import com.ubp.rgd.proxy.tds.protocol.PreLogin;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsChannel;
import com.ubp.rgd.proxy.tds.protocol.TdsMessage;
import com.ubp.rgd.proxy.tds.protocol.TdsPacket;
import com.ubp.rgd.proxy.tds.protocol.TdsProtocolException;
import com.ubp.rgd.proxy.tds.protocol.Tokens;
import com.ubp.rgd.proxy.tds.transport.TlsLayer;
import com.ubp.rgd.proxy.transform.TransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client session: the client's TDS connection paired with a connection to the database server.
 * <ol>
 *     <li><b>Pre-login</b>: the gateway pre-logins to the database server first, then answers the
 *     client with the server's version and its own encryption decision, and opens TLS on both legs as
 *     negotiated. A client opening with a TLS record is a TDS 8.0 strict one.</li>
 *     <li><b>Login</b>: the LOGIN7 of the client is re-encoded for the server: feature extensions the
 *     gateway cannot relay are removed, and an integrated login is carried through Kerberos
 *     constrained delegation. The server's answer is relayed.</li>
 *     <li><b>Relay</b>: one thread per direction. Requests have their {@code transform()} calls
 *     rewritten, responses their values detokenized; everything else goes through untouched.</li>
 * </ol>
 */
public final class TdsSession implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(TdsSession.class);
    private static final AtomicLong IDS = new AtomicLong();

    /** The feature extensions relayed to the database server; the others are removed from the login. */
    static final Set<Integer> RELAYED_FEATURES = Set.of(Tds.FEATURE_UTF8_SUPPORT, Tds.FEATURE_GLOBALTRANSACTIONS);

    private static final byte[] GATEWAY_VERSION = {0x0C, 0x00, 0x00, 0x00, 0x00, 0x00};

    static final int ERROR_LOGIN_FAILED = 18456;
    static final int ERROR_UNTRUSTED_DOMAIN = 18452;
    static final int ERROR_GATEWAY = 50000;
    static final int ERROR_TRANSFORM = 50001;
    static final int ERROR_FATAL = 50002;

    private final long id = IDS.incrementAndGet();
    private final Socket clientSocket;
    private final GatewaySettings settings;
    private final SSLContext serverTls;
    private final SSLContext backendTls;
    private final KerberosAuthenticator kerberos;
    private final RequestRewriter requestRewriter;
    private final ResponseProcessor responseProcessor;
    private final Runnable onClose;

    private Socket backendSocket;
    private TdsChannel client;
    private TdsChannel backend;
    private TlsLayer clientTls;
    private TlsLayer backendTlsLayer;
    private boolean clientLoginOnly;
    private boolean backendLoginOnly;

    private volatile RequestRewriter.RequestInfo currentRequest = RequestRewriter.RequestInfo.NONE;
    private volatile long lastActivity = System.nanoTime();
    /** Requests sent to the server, and how many of them were sent before the last completed response began. */
    private final AtomicLong requestsSent = new AtomicLong();
    private volatile long requestsAnswered;
    /** RESETCONNECTION bits of a request the gateway answered itself, still to carry to the server. */
    private int pendingReset;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param serverTls    the TLS context presented to the clients, {@code null} when none is configured
     * @param backendTls   the TLS context towards the database server
     * @param kerberos     the integrated login, {@code null} when disabled
     * @param onClose      called once the session is closed
     */
    public TdsSession(Socket clientSocket, GatewaySettings settings, SSLContext serverTls, SSLContext backendTls,
                      KerberosAuthenticator kerberos, RequestRewriter requestRewriter,
                      ResponseProcessor responseProcessor, Runnable onClose) {
        this.clientSocket = clientSocket;
        this.settings = settings;
        this.serverTls = serverTls;
        this.backendTls = backendTls;
        this.kerberos = kerberos;
        this.requestRewriter = requestRewriter;
        this.responseProcessor = responseProcessor;
        this.onClose = onClose;
    }

    public long id() {
        return id;
    }

    /** @return whether the session has been idle, no response pending, for longer than the given time */
    public boolean idleFor(long nanos) {
        return requestsAnswered >= requestsSent.get() && System.nanoTime() - lastActivity > nanos;
    }

    private void touch() {
        lastActivity = System.nanoTime();
    }

    @Override
    public void run() {
        Thread.currentThread().setName("tds-session-" + id);
        try {
            clientSocket.setTcpNoDelay(true);
            if (preLogin() && login()) {
                relay();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.info("TDS session {} ended: {}", id, e.getMessage());
                LOG.debug("TDS session {} failure", id, e);
            }
        } catch (RuntimeException e) {
            LOG.error("TDS session {} failed", id, e);
        } finally {
            close();
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Pre-login
    // -------------------------------------------------------------------------------------------------

    private boolean preLogin() throws IOException {
        PushbackInputStream rawInput = new PushbackInputStream(clientSocket.getInputStream(), 1);
        OutputStream rawOutput = clientSocket.getOutputStream();
        int first = rawInput.read();
        if (first < 0) {
            return false;
        }
        rawInput.unread(first);
        client = new TdsChannel("client", rawInput, rawOutput);
        boolean strict = first == Tds.TLS_HANDSHAKE_RECORD;
        if (strict) {
            if (serverTls == null) {
                LOG.warn("TDS session {}: a TDS 8.0 strict client was refused, no TLS certificate is configured"
                        + " (proxy.tds.tls.*)", id);
                return false;
            }
            clientTls = TlsLayer.accept(serverTls, clientSocket, rawInput, rawOutput, true);
            client.setStreams(clientTls.input(), clientTls.output());
            LOG.debug("TDS session {}: TDS 8.0 strict, {}", id, clientTls.protocol());
        }

        TdsMessage message = client.readMessage();
        if (message == null) {
            return false;
        }
        if (message.type() != Tds.PKT_PRELOGIN) {
            throw new TdsProtocolException(String.format("Expected a PRELOGIN message, got type 0x%02X", message.type()));
        }
        PreLogin request = PreLogin.parse(message.payload());
        PreLogin serverAnswer = connectBackend();

        int requested = request.encryption() & ~Tds.ENCRYPT_CLIENT_CERT;
        int answer = strict ? Tds.ENCRYPT_NOT_SUP
                : decideEncryption(requested, serverTls != null, settings.clientEncryption());
        byte[] version = serverAnswer.get(Tds.PL_VERSION);
        PreLogin response = new PreLogin()
                .set(Tds.PL_VERSION, version != null ? version : GATEWAY_VERSION)
                .encryption(answer)
                .set(Tds.PL_INSTOPT, new byte[]{0})
                .set(Tds.PL_THREADID, new byte[0])
                .set(Tds.PL_MARS, new byte[]{0});
        client.writeMessage(Tds.PKT_REPLY, response.encode());

        if (!strict) {
            if (answer == Tds.ENCRYPT_REQ && requested == Tds.ENCRYPT_NOT_SUP) {
                LOG.info("TDS session {}: refused, the client cannot encrypt and proxy.tds.encryption=required", id);
                return false;
            }
            if (answer == Tds.ENCRYPT_ON || answer == Tds.ENCRYPT_REQ || answer == Tds.ENCRYPT_OFF) {
                clientTls = TlsLayer.accept(serverTls, clientSocket, rawInput, rawOutput, false);
                client.setStreams(clientTls.input(), clientTls.output());
                clientLoginOnly = answer == Tds.ENCRYPT_OFF;
                LOG.debug("TDS session {}: {} encryption, {}", id, clientLoginOnly ? "login only" : "full",
                        clientTls.protocol());
            }
        }
        return true;
    }

    /**
     * The ENCRYPTION the gateway answers a TDS 7.x client with, see [MS-TDS] 2.2.6.5.
     *
     * @param requested    what the client asked
     * @param tlsAvailable whether the gateway has a certificate
     */
    static int decideEncryption(int requested, boolean tlsAvailable, GatewaySettings.ClientEncryption policy) {
        if (!tlsAvailable || policy == GatewaySettings.ClientEncryption.OFF) {
            return Tds.ENCRYPT_NOT_SUP;
        }
        return switch (requested) {
            case Tds.ENCRYPT_NOT_SUP -> policy == GatewaySettings.ClientEncryption.REQUIRED
                    ? Tds.ENCRYPT_REQ : Tds.ENCRYPT_NOT_SUP;
            case Tds.ENCRYPT_OFF -> policy == GatewaySettings.ClientEncryption.REQUIRED
                    ? Tds.ENCRYPT_REQ : Tds.ENCRYPT_OFF;
            default -> Tds.ENCRYPT_ON;
        };
    }

    /** Connect and pre-login to the database server, opening TLS as configured. */
    private PreLogin connectBackend() throws IOException {
        backendSocket = new Socket();
        backendSocket.connect(new InetSocketAddress(settings.backendHost(), settings.backendPort()),
                (int) settings.connectTimeout().toMillis());
        backendSocket.setTcpNoDelay(true);
        backendSocket.setKeepAlive(true);
        backend = new TdsChannel("server", backendSocket.getInputStream(), backendSocket.getOutputStream());
        GatewaySettings.BackendEncryption mode = settings.backendEncryption();
        if (mode == GatewaySettings.BackendEncryption.STRICT) {
            backendTlsLayer = TlsLayer.connect(backendTls, backendSocket, settings.backendHost(), settings.backendPort(),
                    true, settings.backendVerifyHost());
            String alpn = backendTlsLayer.applicationProtocol();
            if (!alpn.isEmpty() && !Tds.ALPN_TDS_8.equals(alpn)) {
                throw new TdsProtocolException("The database server negotiated the ALPN protocol " + alpn);
            }
            backend.setStreams(backendTlsLayer.input(), backendTlsLayer.output());
        }
        int wanted = switch (mode) {
            case ON -> Tds.ENCRYPT_ON;
            case LOGIN_ONLY -> Tds.ENCRYPT_OFF;
            case STRICT -> Tds.ENCRYPT_NOT_SUP;
        };
        PreLogin request = new PreLogin()
                .set(Tds.PL_VERSION, GATEWAY_VERSION)
                .encryption(wanted)
                .set(Tds.PL_INSTOPT, new byte[]{0})
                .set(Tds.PL_THREADID, new byte[4])
                .set(Tds.PL_MARS, new byte[]{0});
        backend.writeMessage(Tds.PKT_PRELOGIN, request.encode());
        TdsMessage message = backend.readMessage();
        if (message == null) {
            throw new IOException("The database server closed the connection during the pre-login");
        }
        PreLogin answer = PreLogin.parse(message.payload());
        if (mode != GatewaySettings.BackendEncryption.STRICT) {
            int negotiated = answer.encryption() & ~Tds.ENCRYPT_CLIENT_CERT;
            boolean tls = switch (negotiated) {
                case Tds.ENCRYPT_ON, Tds.ENCRYPT_REQ -> true;
                case Tds.ENCRYPT_OFF -> mode == GatewaySettings.BackendEncryption.LOGIN_ONLY;
                default -> {
                    if (mode == GatewaySettings.BackendEncryption.ON) {
                        throw new IOException("The database server does not support encryption, which"
                                + " proxy.tds.backend.encrypt=true requires");
                    }
                    LOG.warn("TDS session {}: the database server does not support encryption, even the login"
                            + " travels in clear", id);
                    yield false;
                }
            };
            if (tls) {
                backendTlsLayer = TlsLayer.connect(backendTls, backendSocket, settings.backendHost(),
                        settings.backendPort(), false, settings.backendVerifyHost());
                backend.setStreams(backendTlsLayer.input(), backendTlsLayer.output());
                backendLoginOnly = negotiated == Tds.ENCRYPT_OFF;
            }
        }
        return answer;
    }

    // -------------------------------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------------------------------

    private boolean login() throws IOException {
        TdsMessage message = client.readMessage();
        if (message == null) {
            return false;
        }
        if (clientLoginOnly) {
            client.setStreams(clientTls.rawInput(), clientTls.rawOutput());
        }
        if (message.type() != Tds.PKT_LOGIN7) {
            throw new TdsProtocolException(String.format("Expected a LOGIN7 message, got type 0x%02X", message.type()));
        }
        Login7 login;
        try {
            login = Login7.parse(message.payload());
        } catch (TdsProtocolException e) {
            return refuseLogin(ERROR_LOGIN_FAILED, "Login failed: " + e.getMessage());
        }
        boolean clientStrict = login.tdsVersion() == Tds.VERSION_8_0;
        boolean backendStrict = settings.backendEncryption() == GatewaySettings.BackendEncryption.STRICT;
        if (backendStrict) {
            login.setTdsVersion(Tds.VERSION_8_0);
        } else if (clientStrict) {
            login.setTdsVersion(Tds.VERSION_7_4);
        }
        List<Login7.Feature> removed = login.keepFeatures(RELAYED_FEATURES);
        if (removed.stream().anyMatch(feature -> feature.id() == Tds.FEATURE_FEDAUTH)) {
            return refuseLogin(ERROR_LOGIN_FAILED, "Login failed: the TDS gateway does not support Azure Active"
                    + " Directory / federated authentication. Use a SQL Server login or Windows (Kerberos)"
                    + " authentication.");
        }
        if (removed.stream().anyMatch(feature -> feature.id() == Tds.FEATURE_COLUMNENCRYPTION)) {
            LOG.info("TDS session {}: Always Encrypted is not supported through the gateway, it is disabled", id);
        }
        login.setText(Login7.Field.SERVER_NAME, settings.backendHost());

        KerberosAuthenticator.Exchange exchange = null;
        byte[] clientToken = new byte[0];
        try {
            if (login.integratedSecurity()) {
                if (kerberos == null) {
                    return refuseLogin(ERROR_UNTRUSTED_DOMAIN, "Login failed. The login is from an untrusted domain"
                            + " and cannot be used with Integrated authentication: integrated authentication is"
                            + " not enabled on the TDS gateway (proxy.tds.kerberos.enabled).");
                }
                try {
                    exchange = kerberos.start();
                    clientToken = exchange.accept(login.field(Login7.Field.SSPI));
                    login.setField(Login7.Field.SSPI, exchange.initiateBackend());
                    LOG.info("TDS session {}: integrated login of {} delegated to the database server", id,
                            exchange.clientName());
                } catch (KerberosLoginException e) {
                    return refuseLogin(ERROR_UNTRUSTED_DOMAIN, "Login failed. The login is from an untrusted"
                            + " domain and cannot be used with Integrated authentication: " + e.getMessage());
                }
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("TDS session {}: SQL Server login of {}", id, login.text(Login7.Field.USER_NAME));
            }

            backend.writeMessage(Tds.PKT_LOGIN7, login.encode());
            if (backendLoginOnly) {
                backend.setStreams(backendTlsLayer.rawInput(), backendTlsLayer.rawOutput());
            }
            return relayLoginResponse(exchange, clientToken);
        } finally {
            if (exchange != null) {
                exchange.close();
            }
        }
    }

    private boolean relayLoginResponse(KerberosAuthenticator.Exchange exchange, byte[] clientToken)
            throws IOException {
        while (true) {
            TdsPacket first = backend.readPacket();
            if (first == null) {
                throw new IOException("The database server closed the connection during the login");
            }
            TdsMessage message = backend.readMessage(first);
            LoginResponse response = LoginResponse.parse(message.payload());
            if (response.routing) {
                return refuseLogin(ERROR_LOGIN_FAILED, "Login failed: the database server redirects the connection"
                        + " (routing / read-only routing), which the TDS gateway does not support. Point"
                        + " proxy.tds.backend.host at the server itself.");
            }
            if (response.sspi != null && exchange != null) {
                try {
                    byte[] next = exchange.continueBackend(response.sspi);
                    if (!response.loginAck) {
                        if (next.length == 0) {
                            return refuseLogin(ERROR_UNTRUSTED_DOMAIN, "Login failed: the Kerberos exchange with"
                                    + " the database server did not complete");
                        }
                        backend.writeMessage(Tds.PKT_SSPI, next);
                        continue;
                    }
                } catch (KerberosLoginException e) {
                    return refuseLogin(ERROR_UNTRUSTED_DOMAIN, "Login failed: " + e.getMessage());
                }
            }
            TdsBuffer relayed = new TdsBuffer(512);
            if (response.loginAck && clientToken.length > 0) {
                Tokens.sspi(relayed, clientToken);
            }
            relayed.writeBytes(response.relayed.toByteArray());
            client.setSpid(first.spid());
            client.writeMessage(Tds.PKT_REPLY, relayed.toByteArray());
            if (!response.loginAck) {
                LOG.info("TDS session {}: login refused by the database server", id);
                return false;
            }
            if (response.packetSize > 0) {
                client.setPacketSize(response.packetSize);
                backend.setPacketSize(response.packetSize);
            }
            backend.setSpid(0);
            LOG.debug("TDS session {}: logged in, packet size {}", id, client.packetSize());
            return true;
        }
    }

    private boolean refuseLogin(int number, String message) throws IOException {
        LOG.info("TDS session {}: {}", id, message);
        client.writeMessage(Tds.PKT_REPLY, Tokens.errorResponse(number, 14, message));
        return false;
    }

    // -------------------------------------------------------------------------------------------------
    // Relay
    // -------------------------------------------------------------------------------------------------

    private void relay() throws IOException {
        Thread responses = Thread.ofVirtual().name("tds-session-" + id + "-responses").start(this::relayResponses);
        try {
            relayRequests();
        } finally {
            close();
            try {
                responses.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void relayRequests() throws IOException {
        while (!closed.get()) {
            TdsPacket first = client.readPacket();
            if (first == null) {
                return;
            }
            touch();
            switch (first.type()) {
                case Tds.PKT_SQL_BATCH, Tds.PKT_RPC -> request(client.readMessage(first));
                case Tds.PKT_ATTENTION -> backend.writePacket(first);
                case Tds.PKT_SSPI -> client.readMessage(first);
                case Tds.PKT_PRELOGIN, Tds.PKT_LOGIN7 ->
                        throw new TdsProtocolException("Unexpected login message after the login");
                default -> {
                    // Bulk load, transaction manager requests...: relayed packet by packet.
                    requestsSent.incrementAndGet();
                    TdsPacket packet = withPendingReset(first);
                    backend.writePacket(packet);
                    while (!packet.isEndOfMessage()) {
                        packet = client.readPacket();
                        if (packet == null) {
                            return;
                        }
                        backend.writePacket(packet);
                    }
                }
            }
        }
    }

    private void request(TdsMessage message) throws IOException {
        byte[] payload = null;
        RequestRewriter.RequestInfo info = RequestRewriter.RequestInfo.NONE;
        if (!message.isIgnored()) {
            try {
                if (message.type() == Tds.PKT_SQL_BATCH) {
                    payload = requestRewriter.rewriteBatch(message.payload());
                } else {
                    RequestRewriter.Result result = requestRewriter.rewriteRpc(message.payload());
                    payload = result.payload();
                    info = result.info();
                }
            } catch (SqlTransformSyntaxException e) {
                answerLocally(message, ERROR_GATEWAY, e.getMessage());
                return;
            } catch (TransformException e) {
                LOG.error("TDS session {}: the transformation of a request failed", id, e);
                answerLocally(message, ERROR_TRANSFORM, "The TDS gateway could not transform the values of the"
                        + " query: " + e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                // A request the gateway cannot read (an unsupported parameter...) is SQL Server's to judge.
                LOG.warn("TDS session {}: request relayed untouched, the gateway cannot read it: {}", id,
                        e.toString());
                payload = null;
                info = RequestRewriter.RequestInfo.NONE;
            }
        }
        currentRequest = info;
        requestsSent.incrementAndGet();
        int reset = message.status() & RESET_BITS;
        if (payload == null && pendingReset == 0) {
            backend.writePackets(message.packets());
        } else {
            backend.writeMessage(message.type(), reset | takePendingReset(),
                    payload != null ? payload : message.payload());
        }
    }

    private static final int RESET_BITS = Tds.STATUS_RESETCONNECTION | Tds.STATUS_RESETCONNECTION_SKIPTRAN;

    /**
     * Answer a request with an error without sending it to the server. A connection reset it carries,
     * which a pooling driver sends only once, is kept for the next request sent to the server.
     */
    private void answerLocally(TdsMessage message, int number, String text) throws IOException {
        pendingReset |= message.status() & RESET_BITS;
        answerError(number, text);
    }

    private int takePendingReset() {
        int reset = pendingReset;
        pendingReset = 0;
        return reset;
    }

    private TdsPacket withPendingReset(TdsPacket packet) {
        int reset = takePendingReset();
        return reset == 0 ? packet : new TdsPacket(packet.type(), packet.status() | reset, packet.spid(),
                packet.packetId(), packet.window(), packet.payload());
    }

    private void answerError(int number, String message) throws IOException {
        client.writeMessage(Tds.PKT_REPLY, Tokens.errorResponse(number, 16, message));
    }

    private void relayResponses() {
        try {
            while (!closed.get()) {
                TdsPacket first = backend.readPacket();
                if (first == null) {
                    break;
                }
                touch();
                // The client only sends its next request once it has this response: what was sent
                // until now is what this response may complete.
                long answering = requestsSent.get();
                if (!responseProcessor.isActive()) {
                    relayPackets(first);
                } else {
                    processResponse(first);
                }
                requestsAnswered = answering;
                touch();
            }
        } catch (IOException e) {
            if (!closed.get()) {
                LOG.info("TDS session {}: database server connection ended: {}", id, e.getMessage());
            }
        } catch (RuntimeException e) {
            LOG.error("TDS session {}: response relay failed", id, e);
        } finally {
            close();
        }
    }

    private void relayPackets(TdsPacket first) throws IOException {
        TdsPacket packet = first;
        client.writePacket(packet);
        while (!packet.isEndOfMessage()) {
            packet = backend.readPacket();
            if (packet == null) {
                throw new IOException("The database server closed the connection inside a response");
            }
            client.writePacket(packet);
        }
    }

    private void processResponse(TdsPacket first) throws IOException {
        MessageInputStream in = backend.messageInput(first);
        client.setSpid(first.spid());
        MessageOutputStream out = client.openMessage(first.type(), 0);
        try {
            responseProcessor.process(in, out, currentRequest);
            out.close();
        } catch (TransformException | TdsProtocolException e) {
            LOG.error("TDS session {}: the response cannot be relayed, the session is closed", id, e);
            TdsBuffer error = new TdsBuffer(256);
            String reason = e instanceof TransformException
                    ? "the detokenization of the result failed: " + e.getMessage()
                    : "the response of the database server cannot be read: " + e.getMessage();
            Tokens.error(error, ERROR_FATAL, 1, 20, "The TDS gateway closes the connection, " + reason);
            Tokens.done(error, Tds.DONE_ERROR, 0);
            out.write(error.toByteArray());
            out.close();
            throw new IOException(reason, e);
        } catch (IOException | RuntimeException e) {
            out.abandon();
            throw e;
        }
    }

    /** Close both connections; idempotent. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(clientSocket);
        closeQuietly(backendSocket);
        LOG.debug("TDS session {} closed", id);
        if (onClose != null) {
            onClose.run();
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Closing anyway.
            }
        }
    }
}
