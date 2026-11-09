package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.tds.protocol.Login7;
import com.ubp.rgd.proxy.tds.protocol.PreLogin;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsBuffer;
import com.ubp.rgd.proxy.tds.protocol.TdsChannel;
import com.ubp.rgd.proxy.tds.protocol.TdsMessage;
import com.ubp.rgd.proxy.tds.protocol.TdsPacket;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import com.ubp.rgd.proxy.tds.protocol.Tokens;
import com.ubp.rgd.proxy.tds.protocol.TypeInfo;
import com.ubp.rgd.proxy.tds.transport.TlsLayer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A scripted SQL Server standing in for the database in the end to end tests: it answers the
 * pre-login (with TLS when it has a certificate) and the login, records what it receives, and answers
 * the statements with the tokens its responder gives.
 */
final class FakeSqlServer implements AutoCloseable {

    /** A statement to make the server wait for the client's attention. */
    static final String WAIT_FOR_ATTENTION = "WAITFOR DELAY '01:00:00'";

    final List<String> statements = new CopyOnWriteArrayList<>();
    final List<Login7> logins = new CopyOnWriteArrayList<>();
    final List<Integer> encryptionRequests = new CopyOnWriteArrayList<>();
    final List<Integer> attentions = new CopyOnWriteArrayList<>();
    final List<String> sspiBlobs = new CopyOnWriteArrayList<>();
    /** The status of the first packet of each request. */
    final List<Integer> requestStatuses = new CopyOnWriteArrayList<>();

    private final SSLContext tls;
    private final ServerSocket serverSocket;
    private volatile Function<String, byte[]> responder = sql -> new byte[0];

    FakeSqlServer(SSLContext tls) throws IOException {
        this.tls = tls;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** @param responder the tokens answering a statement, without the final DONE */
    void respond(Function<String, byte[]> responder) {
        this.responder = responder;
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            TdsChannel channel = new TdsChannel("fake", socket.getInputStream(), socket.getOutputStream());
            PreLogin request = PreLogin.parse(channel.readMessage().payload());
            int requested = request.encryption();
            encryptionRequests.add(requested);
            int answer = tls == null ? Tds.ENCRYPT_NOT_SUP
                    : requested == Tds.ENCRYPT_OFF ? Tds.ENCRYPT_OFF : Tds.ENCRYPT_ON;
            channel.writeMessage(Tds.PKT_REPLY, new PreLogin().set(Tds.PL_VERSION, new byte[]{16, 0, 0x10, 0x7A, 0, 0})
                    .encryption(answer).set(Tds.PL_INSTOPT, new byte[]{0}).set(Tds.PL_THREADID, new byte[0])
                    .set(Tds.PL_MARS, new byte[]{0}).encode());
            TlsLayer layer = null;
            if (answer != Tds.ENCRYPT_NOT_SUP) {
                layer = TlsLayer.accept(tls, socket, socket.getInputStream(), socket.getOutputStream(), false);
                channel.setStreams(layer.input(), layer.output());
            }
            Login7 login = Login7.parse(channel.readMessage().payload());
            if (answer == Tds.ENCRYPT_OFF) {
                channel.setStreams(layer.rawInput(), layer.rawOutput());
            }
            logins.add(login);
            channel.setSpid(57);
            if ("bad".equals(login.text(Login7.Field.USER_NAME))) {
                channel.writeMessage(Tds.PKT_REPLY, Tokens.errorResponse(18456, 14, "Login failed for user 'bad'."));
                return;
            }
            if (login.integratedSecurity()) {
                // Two legs, as a Kerberos login with mutual authentication may take.
                sspiBlobs.add(new String(login.field(Login7.Field.SSPI), StandardCharsets.US_ASCII));
                channel.writeMessage(Tds.PKT_REPLY, sspi("server-leg-1"));
                TdsMessage next = channel.readMessage();
                if (next == null || next.type() != Tds.PKT_SSPI) {
                    return;
                }
                sspiBlobs.add(new String(next.payload(), StandardCharsets.US_ASCII));
                channel.writeMessage(Tds.PKT_REPLY, ServerTokens.join(sspi("server-leg-2"), loginAck()));
            } else {
                channel.writeMessage(Tds.PKT_REPLY, loginAck());
            }
            channel.setPacketSize(8000);
            while (true) {
                TdsPacket first = channel.readPacket();
                if (first == null) {
                    return;
                }
                if (first.type() == Tds.PKT_ATTENTION) {
                    // As SQL Server: the interrupted request gets its response, then the attention its
                    // acknowledgement, in a message of its own.
                    attentions.add(1);
                    channel.writeMessage(Tds.PKT_REPLY, ServerTokens.done(Tds.DONE_ERROR, 0));
                    channel.writeMessage(Tds.PKT_REPLY, ServerTokens.done(Tds.DONE_ATTN, 0));
                    continue;
                }
                TdsMessage message = channel.readMessage(first);
                requestStatuses.add(message.status());
                if (message.type() == Tds.PKT_SQL_BATCH) {
                    String sql = batchText(message.payload());
                    statements.add(sql);
                    if (sql.equals(WAIT_FOR_ATTENTION)) {
                        // The server works until the client gives up: the attention ends the request.
                        continue;
                    }
                    channel.writeMessage(Tds.PKT_REPLY, ServerTokens.join(responder.apply(sql), ServerTokens.done(0, 0)));
                } else if (message.type() == Tds.PKT_RPC) {
                    String sql = rpcStatement(message.payload());
                    statements.add(sql);
                    channel.writeMessage(Tds.PKT_REPLY, ServerTokens.join(responder.apply(sql),
                            ServerTokens.returnStatus(0), ServerTokens.doneProc(0)));
                }
            }
        } catch (IOException | RuntimeException e) {
            // The connection ends.
        }
    }

    private static byte[] loginAck() {
        TdsBuffer buffer = new TdsBuffer();
        envChange(buffer, 1, new TdsBuffer().writeBVarchar("WDX1").writeBVarchar("master").toByteArray());
        envChange(buffer, 7, new TdsBuffer().writeByte(5).writeBytes(ServerTokens.COLLATION).writeByte(0).toByteArray());
        TdsBuffer ack = new TdsBuffer().writeByte(1).writeByte(0x74).writeByte(0).writeByte(0).writeByte(4)
                .writeBVarchar("Microsoft SQL Server").writeByte(16).writeByte(0).writeByte(0x10).writeByte(0x7A);
        byte[] ackBytes = ack.toByteArray();
        buffer.writeByte(Tds.TOKEN_LOGINACK).writeUShort(ackBytes.length).writeBytes(ackBytes);
        envChange(buffer, Tds.ENV_PACKET_SIZE, new TdsBuffer().writeBVarchar("8000").writeBVarchar("4096").toByteArray());
        buffer.writeBytes(ServerTokens.done(0, 0));
        return buffer.toByteArray();
    }

    private static byte[] sspi(String blob) {
        TdsBuffer buffer = new TdsBuffer();
        Tokens.sspi(buffer, blob.getBytes(StandardCharsets.US_ASCII));
        return buffer.toByteArray();
    }

    private static void envChange(TdsBuffer buffer, int type, byte[] values) {
        buffer.writeByte(Tds.TOKEN_ENVCHANGE).writeUShort(values.length + 1).writeByte(type).writeBytes(values);
    }

    private static String batchText(byte[] payload) {
        int headers = (payload[0] & 0xFF) | (payload[1] & 0xFF) << 8;
        return new String(payload, headers, payload.length - headers, StandardCharsets.UTF_16LE);
    }

    /** The statement of an sp_executesql / sp_prepexec call. */
    private static String rpcStatement(byte[] payload) throws IOException {
        int headers = (payload[0] & 0xFF) | (payload[1] & 0xFF) << 8;
        TdsReader reader = TdsReader.of(payload);
        reader.skip(headers);
        int nameLength = reader.readUShort();
        int procId = nameLength == Tds.RPC_PROC_ID ? reader.readUShort() : -1;
        if (procId < 0) {
            reader.readUcs2(nameLength);
        }
        reader.readUShort();
        int statementIndex = procId == Tds.PROC_EXECUTESQL ? 0 : 2;
        for (int index = 0; ; index++) {
            reader.readBVarchar();
            reader.readByte();
            TypeInfo type = TypeInfo.read(reader);
            TypeInfo.Value value = type.readValue(reader, false);
            if (index == statementIndex) {
                return new String(value.data(), StandardCharsets.UTF_16LE);
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
