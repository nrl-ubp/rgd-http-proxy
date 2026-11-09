package com.ubp.rgd.proxy.tds.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * The socket an {@link javax.net.ssl.SSLSocket} is layered over: it stands for the TCP connection
 * but its streams can be switched, because the TLS handshake of TDS 7.x travels inside PRELOGIN
 * packets while the records that follow it are sent as they are.
 */
final class ProxySocket extends Socket {

    private final Socket socket;
    private final SwitchableInput input;
    private final SwitchableOutput output;

    ProxySocket(Socket socket, InputStream rawInput, OutputStream rawOutput) {
        this.socket = socket;
        this.input = new SwitchableInput(rawInput);
        this.output = new SwitchableOutput(rawOutput);
    }

    void switchTo(InputStream in, OutputStream out) {
        input.target = in;
        output.target = out;
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isBound() {
        return socket.isBound();
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    @Override
    public InetAddress getInetAddress() {
        return socket.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return socket.getLocalAddress();
    }

    @Override
    public int getPort() {
        return socket.getPort();
    }

    @Override
    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return socket.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return socket.getLocalSocketAddress();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        socket.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return socket.getSoTimeout();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        socket.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return socket.getTcpNoDelay();
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        socket.setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return socket.getKeepAlive();
    }

    @Override
    public void shutdownInput() {
        // The TCP connection outlives the TLS layer when only the login is encrypted.
    }

    @Override
    public void shutdownOutput() {
        // Same as shutdownInput.
    }

    @Override
    public synchronized void close() {
        // The TLS layer is created with autoClose=false; the session closes the TCP socket itself.
    }

    private static final class SwitchableInput extends InputStream {
        volatile InputStream target;

        SwitchableInput(InputStream target) {
            this.target = target;
        }

        @Override
        public int read() throws IOException {
            return target.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return target.read(buffer, offset, length);
        }

        @Override
        public int available() throws IOException {
            return target.available();
        }
    }

    private static final class SwitchableOutput extends OutputStream {
        volatile OutputStream target;

        SwitchableOutput(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int value) throws IOException {
            target.write(value);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            target.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }
    }
}
