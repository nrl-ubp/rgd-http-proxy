package com.ubp.rgd.proxy.tds.gateway;

import com.ubp.rgd.proxy.sqlgateway.config.MappingConfig;
import com.ubp.rgd.proxy.tds.TdsServerService;
import com.ubp.rgd.proxy.tds.kerberos.KerberosAuthenticator;
import com.ubp.rgd.proxy.tds.kerberos.KerberosLoginException;
import com.ubp.rgd.proxy.tds.protocol.Login7;
import com.ubp.rgd.proxy.tds.protocol.PreLogin;
import com.ubp.rgd.proxy.tds.protocol.ServerTokens;
import com.ubp.rgd.proxy.tds.protocol.Tds;
import com.ubp.rgd.proxy.tds.protocol.TdsChannel;
import com.ubp.rgd.proxy.tds.protocol.TdsMessage;
import com.ubp.rgd.proxy.tds.protocol.TdsReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The integrated login through the gateway, with the GSS layer mocked: the real one needs a KDC, the
 * SPNs and the constrained delegation of a domain.
 */
class KerberosLoginTest {

    private final List<AutoCloseable> resources = new ArrayList<>();
    private final List<String> backendTokens = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable resource : resources) {
            resource.close();
        }
    }

    /** Accepts "client-ticket" only, as a Kerberos acceptor would accept a ticket for its SPN. */
    private final KerberosAuthenticator kerberos = () -> new KerberosAuthenticator.Exchange() {
        private boolean accepted;

        @Override
        public byte[] accept(byte[] clientToken) throws KerberosLoginException {
            if (!"client-ticket".equals(new String(clientToken, StandardCharsets.US_ASCII))) {
                throw new KerberosLoginException("only Kerberos is supported (NTLM is refused)");
            }
            accepted = true;
            return "gateway-ap-rep".getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public String clientName() {
            return "alice@EXAMPLE.COM";
        }

        @Override
        public byte[] initiateBackend() {
            assertTrue(accepted);
            return "delegated-ticket".getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] continueBackend(byte[] serverToken) {
            String token = new String(serverToken, StandardCharsets.US_ASCII);
            backendTokens.add(token);
            return "server-leg-1".equals(token) ? "client-leg-2".getBytes(StandardCharsets.US_ASCII) : new byte[0];
        }

        @Override
        public void close() {
            // Nothing to dispose.
        }
    };

    private int gateway(FakeSqlServer server) throws Exception {
        MappingConfig config = new MappingConfig();
        TdsGateway gateway = new TdsGateway(new GatewaySettings("127.0.0.1", server.port(),
                GatewaySettings.BackendEncryption.LOGIN_ONLY, false, GatewaySettings.ClientEncryption.OPTIONAL,
                Duration.ofSeconds(5)), null, TdsServerService.backendTls(true, Optional.empty(), Optional.empty()),
                kerberos, Fpe.requestRewriter(config), () -> Fpe.responseProcessor(config, 10, new ArrayList<>()), 10,
                Duration.ZERO);
        resources.add(gateway);
        return gateway.start("127.0.0.1", 0);
    }

    /** Pre-login then log in with an SSPI blob, as an integrated-security client does. @return the answer */
    private static byte[] integratedLogin(int port, String blob) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            TdsChannel channel = new TdsChannel("client", socket.getInputStream(), socket.getOutputStream());
            channel.writeMessage(Tds.PKT_PRELOGIN, new PreLogin().set(Tds.PL_VERSION, new byte[6])
                    .encryption(Tds.ENCRYPT_NOT_SUP).encode());
            assertEquals(Tds.ENCRYPT_NOT_SUP, PreLogin.parse(channel.readMessage().payload()).encryption());
            Login7 login = Login7.parse(ServerTokens.login7("", new byte[0], "app", "gw", "db", new int[0]));
            login.setIntegratedSecurity(true);
            login.setField(Login7.Field.SSPI, blob.getBytes(StandardCharsets.US_ASCII));
            channel.writeMessage(Tds.PKT_LOGIN7, login.encode());
            TdsMessage answer = channel.readMessage();
            return answer.payload();
        }
    }

    @Test
    void theClientIsLoggedInThroughConstrainedDelegation() throws Exception {
        FakeSqlServer server = new FakeSqlServer(null);
        resources.add(server);
        byte[] answer = integratedLogin(gateway(server), "client-ticket");

        assertEquals(List.of("delegated-ticket", "client-leg-2"), server.sspiBlobs,
                "the server gets the delegated ticket, then the second leg of the gateway's own context");
        assertEquals(List.of("server-leg-1", "server-leg-2"), backendTokens);
        TdsReader reader = TdsReader.of(answer);
        assertEquals(Tds.TOKEN_SSPI, reader.readByte(), "the client first gets the gateway's answer to its ticket");
        assertEquals("gateway-ap-rep", new String(reader.readBytes(reader.readUShort()), StandardCharsets.US_ASCII));
        assertTrue(contains(answer, Tds.TOKEN_LOGINACK));
        assertFalse(new String(answer, StandardCharsets.US_ASCII).contains("server-leg"),
                "the server's SSPI tokens belong to the gateway");
    }

    @Test
    void ntlmIsRefused() throws Exception {
        FakeSqlServer server = new FakeSqlServer(null);
        resources.add(server);
        byte[] answer = integratedLogin(gateway(server), "NTLMSSP");

        TdsReader reader = TdsReader.of(answer);
        assertEquals(Tds.TOKEN_ERROR, reader.readByte());
        reader.readUShort();
        assertEquals(TdsSession.ERROR_UNTRUSTED_DOMAIN, reader.readInt());
        reader.readByte();
        reader.readByte();
        assertTrue(reader.readUsVarchar().contains("NTLM is refused"));
        assertTrue(server.logins.isEmpty(), "nothing reached the server");
    }

    private static boolean contains(byte[] payload, int token) throws Exception {
        TdsReader reader = TdsReader.of(payload);
        int next;
        while ((next = reader.readByteOrEnd()) >= 0) {
            if (next == token) {
                return true;
            }
            TokenLengths.skipSimpleToken(next, reader);
        }
        return false;
    }
}
