package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.kerberos.KerberosAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The TDS gateway server: accepts the clients and runs a {@link TdsSession} for each of them on a
 * virtual thread, within a connection limit. Idle sessions are closed by a watchdog.
 */
public final class TdsGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TdsGateway.class);

    private final GatewaySettings settings;
    private final SSLContext serverTls;
    private final SSLContext backendTls;
    private final KerberosAuthenticator kerberos;
    private final RequestRewriter requestRewriter;
    private final Supplier<ResponseProcessor> responseProcessors;
    private final int maxConnections;
    private final Duration idleTimeout;

    private final Map<Long, TdsSession> sessions = new ConcurrentHashMap<>();
    private Semaphore permits;
    private ServerSocket serverSocket;
    private Thread acceptor;
    private ScheduledExecutorService watchdog;

    /**
     * @param serverTls          the TLS context presented to the clients, {@code null} when none
     * @param kerberos           the integrated login, {@code null} when disabled
     * @param responseProcessors one processor per session
     * @param idleTimeout        zero or negative to never close idle sessions
     */
    public TdsGateway(GatewaySettings settings, SSLContext serverTls, SSLContext backendTls,
                      KerberosAuthenticator kerberos, RequestRewriter requestRewriter,
                      Supplier<ResponseProcessor> responseProcessors, int maxConnections, Duration idleTimeout) {
        this.settings = settings;
        this.serverTls = serverTls;
        this.backendTls = backendTls;
        this.kerberos = kerberos;
        this.requestRewriter = requestRewriter;
        this.responseProcessors = responseProcessors;
        this.maxConnections = maxConnections;
        this.idleTimeout = idleTimeout;
    }

    /**
     * Start listening.
     *
     * @param port 0 to pick a free port
     * @return the port listened on
     */
    public synchronized int start(String host, int port) throws IOException {
        permits = new Semaphore(Math.max(1, maxConnections));
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, port), 128);
        acceptor = Thread.ofPlatform().daemon().name("tds-gateway-acceptor").start(this::acceptLoop);
        if (idleTimeout != null && !idleTimeout.isZero() && !idleTimeout.isNegative()) {
            watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                    .name("tds-gateway-watchdog").factory());
            long period = Math.max(1, Math.min(60, idleTimeout.toSeconds() / 2));
            watchdog.scheduleAtFixedRate(this::closeIdleSessions, period, period, TimeUnit.SECONDS);
        }
        return serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        ServerSocket socket = serverSocket;
        return socket != null && !socket.isClosed();
    }

    public int activeSessions() {
        return sessions.size();
    }

    private void acceptLoop() {
        while (isRunning()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException e) {
                break;
            } catch (IOException e) {
                LOG.warn("TDS gateway accept failed: {}", e.getMessage());
                continue;
            }
            if (!permits.tryAcquire()) {
                LOG.warn("TDS gateway connection from {} refused: {} connections already open (proxy.tds.max-connections)",
                        socket.getRemoteSocketAddress(), maxConnections);
                closeQuietly(socket);
                continue;
            }
            Long[] key = new Long[1];
            TdsSession session = new TdsSession(socket, settings, serverTls, backendTls, kerberos, requestRewriter,
                    responseProcessors.get(), () -> {
                        if (key[0] != null) {
                            sessions.remove(key[0]);
                        }
                        permits.release();
                    });
            key[0] = session.id();
            sessions.put(session.id(), session);
            LOG.debug("TDS session {} accepted from {}", session.id(), socket.getRemoteSocketAddress());
            Thread.ofVirtual().name("tds-session-" + session.id()).start(session);
        }
    }

    private void closeIdleSessions() {
        long limit = idleTimeout.toNanos();
        sessions.values().stream().filter(session -> session.idleFor(limit)).forEach(session -> {
            LOG.info("TDS session {} closed after {} of inactivity (proxy.tds.idle-timeout)", session.id(), idleTimeout);
            session.close();
        });
    }

    @Override
    public synchronized void close() {
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        if (serverSocket != null) {
            closeQuietly(serverSocket);
        }
        sessions.values().forEach(TdsSession::close);
        sessions.clear();
        if (acceptor != null) {
            try {
                acceptor.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            acceptor = null;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // Closing anyway.
        }
    }
}
