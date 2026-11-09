package com.ubp.rgd.proxy.sqlgateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * The TLS material of the SQL gateways: the Flight SQL server and the TDS gateway.
 * <p>
 * Arrow's {@code FlightServer.Builder.useTls(InputStream, InputStream)} wants a PEM certificate chain
 * and a <b>PKCS#8</b> PEM private key, the TDS gateway an {@link SSLContext}, which
 * {@link TlsMaterial#sslContext()} builds from the same material. Two sources are accepted:
 * <ul>
 *     <li>a <b>PKCS12 keystore</b>, the format of the HTTPS keystore, so that the very same file can
 *     serve both. It is converted to PEM <b>in memory</b>: no unencrypted key is ever written to
 *     disk;</li>
 *     <li>a <b>PEM pair</b>, a certificate chain file and a PKCS#8 private key file.</li>
 * </ul>
 * Exactly one of them must be configured when TLS is enabled. Any inconsistency is a
 * {@link TlsConfigurationException}, raised at startup: a server that silently falls back to plaintext
 * would send the database credentials of its clients in clear text.
 */
public final class TlsMaterialLoader {

    private static final Logger LOG = LoggerFactory.getLogger(TlsMaterialLoader.class);

    private static final String KEYSTORE_TYPE = "PKCS12";
    private static final String PKCS8_FORMAT = "PKCS#8";
    private static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
    private static final String BEGIN_PKCS8_KEY = "-----BEGIN PRIVATE KEY-----";

    /**
     * The feature the TLS material is loaded for, which names it in the messages.
     *
     * @param label          such as {@code Flight SQL} or {@code TDS gateway}
     * @param propertyPrefix such as {@code proxy.flight-sql.tls.}, prefixed to the setting names
     */
    public record Feature(String label, String propertyPrefix) {
        String property(String name) {
            return propertyPrefix + name;
        }
    }

    private final Feature feature;

    private TlsMaterialLoader(Feature feature) {
        this.feature = feature;
    }

    /**
     * Load the TLS material described by the settings.
     *
     * @param feature the feature it is loaded for, naming it and its settings in the messages
     * @throws TlsConfigurationException when the settings are inconsistent or the material unusable
     */
    public static TlsMaterial load(Settings settings, Feature feature) {
        return new TlsMaterialLoader(feature).load(settings);
    }

    /**
     * The {@code <prefix>tls.*} settings of a feature. A blank value counts as absent.
     *
     * @param keyStoreAlias optional: when absent, the keystore must hold a single key entry
     */
    public record Settings(Optional<String> keyStoreFile,
                           Optional<String> keyStorePassword,
                           Optional<String> keyStoreAlias,
                           Optional<String> certChainFile,
                           Optional<String> privateKeyFile) {

        public Settings {
            keyStoreFile = clean(keyStoreFile);
            keyStorePassword = keyStorePassword == null ? Optional.empty() : keyStorePassword;
            keyStoreAlias = clean(keyStoreAlias);
            certChainFile = clean(certChainFile);
            privateKeyFile = clean(privateKeyFile);
        }

        private static Optional<String> clean(Optional<String> value) {
            return value == null ? Optional.empty() : value.map(String::trim).filter(v -> !v.isEmpty());
        }
    }

    /**
     * The PEM material handed over to Arrow.
     *
     * @param certificateChain the leaf certificate first, then its issuers
     * @param description where the material comes from, for the logs
     */
    public record TlsMaterial(byte[] certificateChainPem, byte[] privateKeyPem,
                              List<X509Certificate> certificateChain, String description) {

        public InputStream certificateChainStream() {
            return new ByteArrayInputStream(certificateChainPem);
        }

        public InputStream privateKeyStream() {
            return new ByteArrayInputStream(privateKeyPem);
        }

        public X509Certificate leaf() {
            return certificateChain.get(0);
        }

        /**
         * A server side {@link SSLContext} serving this certificate chain and key. The key is only
         * held in memory.
         *
         * @throws TlsConfigurationException when the private key cannot be decoded
         */
        public SSLContext sslContext() {
            try {
                PrivateKey key = decodePkcs8(new String(privateKeyPem, StandardCharsets.US_ASCII));
                KeyStore store = KeyStore.getInstance(KEYSTORE_TYPE);
                store.load(null, null);
                char[] password = new char[0];
                store.setKeyEntry("server", key, password, certificateChain.toArray(new Certificate[0]));
                KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(store, password);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(keyManagers.getKeyManagers(), null, null);
                return context;
            } catch (GeneralSecurityException | IOException e) {
                throw new TlsConfigurationException("Cannot build a TLS context from " + description + ": "
                        + e.getMessage(), e);
            }
        }
    }

    /** Decode an unencrypted PKCS#8 PEM key, whatever its algorithm. */
    private static PrivateKey decodePkcs8(String pem) throws GeneralSecurityException {
        String body = pem.substring(pem.indexOf(BEGIN_PKCS8_KEY) + BEGIN_PKCS8_KEY.length());
        body = body.substring(0, body.indexOf("-----END")).replaceAll("\\s", "");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body));
        GeneralSecurityException last = null;
        for (String algorithm : new String[]{"RSA", "EC", "Ed25519", "EdDSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw last;
    }

    /**
     * Load the TLS material described by the settings.
     *
     * @throws TlsConfigurationException when the settings are inconsistent or the material unusable
     */
    private TlsMaterial load(Settings settings) {
        boolean keyStore = settings.keyStoreFile().isPresent();
        boolean pemPair = settings.certChainFile().isPresent() || settings.privateKeyFile().isPresent();

        if (keyStore && pemPair) {
            throw new TlsConfigurationException(feature.label() + " TLS is configured twice: set either"
                    + " " + feature.property("key-store-file") + ", or " + feature.property("cert-chain-file") + " and"
                    + " " + feature.property("private-key-file") + ", not both.");
        }
        if (!keyStore && !pemPair) {
            throw new TlsConfigurationException(feature.label() + " TLS is enabled but no certificate is"
                    + " configured: set " + feature.property("key-store-file") + ", or"
                    + " " + feature.property("cert-chain-file") + " and " + feature.property("private-key-file") + ".");
        }

        TlsMaterial material = keyStore ? fromKeyStore(settings) : fromPemPair(settings);
        warnAboutValidity(material.leaf());
        return material;
    }

    // ---------------------------------------------------------------------------------------------
    // PKCS12 keystore
    // ---------------------------------------------------------------------------------------------

    private TlsMaterial fromKeyStore(Settings settings) {
        Path file = readableFile(settings.keyStoreFile().get(), feature.property("key-store-file"));
        char[] password = settings.keyStorePassword().orElse("").toCharArray();

        KeyStore store;
        try (InputStream input = Files.newInputStream(file)) {
            store = KeyStore.getInstance(KEYSTORE_TYPE);
            store.load(input, password);
        } catch (IOException | GeneralSecurityException e) {
            // A wrong password surfaces as an IOException whose cause is an UnrecoverableKeyException.
            throw new TlsConfigurationException("Cannot open the " + feature.label() + " TLS keystore " + file
                    + " as " + KEYSTORE_TYPE + ": " + e.getMessage()
                    + ". Check " + feature.property("key-store-password") + ".", e);
        }

        String alias = selectAlias(store, settings.keyStoreAlias(), file);
        try {
            Key key = store.getKey(alias, password);
            if (!(key instanceof PrivateKey privateKey)) {
                throw new TlsConfigurationException("The entry '" + alias + "' of " + file
                        + " holds no private key.");
            }
            if (!PKCS8_FORMAT.equals(privateKey.getFormat())) {
                throw new TlsConfigurationException("The private key '" + alias + "' of " + file
                        + " is encoded as " + privateKey.getFormat() + ", not " + PKCS8_FORMAT + ".");
            }

            Certificate[] chain = store.getCertificateChain(alias);
            if (chain == null || chain.length == 0) {
                throw new TlsConfigurationException("The entry '" + alias + "' of " + file
                        + " holds no certificate chain.");
            }
            List<X509Certificate> certificates = new ArrayList<>();
            StringBuilder chainPem = new StringBuilder();
            for (Certificate certificate : chain) {
                if (!(certificate instanceof X509Certificate x509)) {
                    throw new TlsConfigurationException("The chain of '" + alias + "' in " + file
                            + " holds a non X.509 certificate.");
                }
                certificates.add(x509);
                chainPem.append(pem("CERTIFICATE", x509.getEncoded()));
            }

            return new TlsMaterial(chainPem.toString().getBytes(StandardCharsets.US_ASCII),
                    pem("PRIVATE KEY", privateKey.getEncoded()).getBytes(StandardCharsets.US_ASCII),
                    Collections.unmodifiableList(certificates),
                    "keystore " + file + " (alias '" + alias + "')");
        } catch (GeneralSecurityException e) {
            throw new TlsConfigurationException("Cannot read the key '" + alias + "' of " + file + ": "
                    + e.getMessage() + ". The key must be protected by the keystore password.", e);
        }
    }

    /**
     * The alias to use: the configured one, which must be a key entry, or else the single key entry
     * of the keystore. Picking one of several silently would serve whichever certificate the
     * keystore happens to list first.
     */
    private String selectAlias(KeyStore store, Optional<String> configured, Path file) {
        try {
            if (configured.isPresent()) {
                String alias = configured.get();
                if (!store.containsAlias(alias)) {
                    throw new TlsConfigurationException("The " + feature.label() + " TLS keystore " + file
                            + " has no entry named '" + alias + "' (" + feature.property("key-store-alias") + ").");
                }
                if (!store.isKeyEntry(alias)) {
                    throw new TlsConfigurationException("The entry '" + alias + "' of " + file
                            + " is not a private key entry.");
                }
                return alias;
            }

            List<String> keyEntries = new ArrayList<>();
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias)) {
                    keyEntries.add(alias);
                }
            }
            if (keyEntries.isEmpty()) {
                throw new TlsConfigurationException("The " + feature.label() + " TLS keystore " + file
                        + " holds no private key entry.");
            }
            if (keyEntries.size() > 1) {
                throw new TlsConfigurationException("The " + feature.label() + " TLS keystore " + file
                        + " holds several private keys " + keyEntries
                        + ": choose one with " + feature.property("key-store-alias") + ".");
            }
            return keyEntries.get(0);
        } catch (java.security.KeyStoreException e) {
            throw new TlsConfigurationException("Cannot list the entries of " + file + ": " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PEM pair
    // ---------------------------------------------------------------------------------------------

    private TlsMaterial fromPemPair(Settings settings) {
        if (settings.certChainFile().isEmpty() || settings.privateKeyFile().isEmpty()) {
            throw new TlsConfigurationException(feature.label() + " TLS with PEM files needs both"
                    + " " + feature.property("cert-chain-file") + " and " + feature.property("private-key-file") + ".");
        }
        Path chainFile = readableFile(settings.certChainFile().get(), feature.property("cert-chain-file"));
        Path keyFile = readableFile(settings.privateKeyFile().get(), feature.property("private-key-file"));

        byte[] chainPem = read(chainFile);
        byte[] keyPem = read(keyFile);

        List<X509Certificate> certificates = parseCertificates(chainPem, chainFile);
        checkPkcs8(new String(keyPem, StandardCharsets.US_ASCII), keyFile);

        return new TlsMaterial(chainPem, keyPem, certificates,
                "PEM files " + chainFile + " and " + keyFile);
    }

    private List<X509Certificate> parseCertificates(byte[] pem, Path file) {
        if (!new String(pem, StandardCharsets.US_ASCII).contains(BEGIN_CERTIFICATE)) {
            throw new TlsConfigurationException("The " + feature.label() + " TLS certificate chain " + file
                    + " holds no PEM certificate (" + BEGIN_CERTIFICATE + ").");
        }
        try {
            Collection<? extends Certificate> parsed = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(pem));
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : parsed) {
                certificates.add((X509Certificate) certificate);
            }
            if (certificates.isEmpty()) {
                throw new TlsConfigurationException("The " + feature.label() + " TLS certificate chain " + file
                        + " holds no certificate.");
            }
            return Collections.unmodifiableList(certificates);
        } catch (CertificateException e) {
            throw new TlsConfigurationException("Cannot parse the " + feature.label() + " TLS certificate chain "
                    + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Arrow only accepts an unencrypted PKCS#8 key. The other common PEM forms are recognized so that
     * the error says how to convert them, rather than failing obscurely when the server is built.
     */
    private void checkPkcs8(String keyPem, Path file) {
        if (keyPem.contains(BEGIN_PKCS8_KEY)) {
            return;
        }
        String conversion = " Convert it with: openssl pkcs8 -topk8 -nocrypt -in " + file
                + " -out <pkcs8-key>.pem";
        if (keyPem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----")) {
            throw new TlsConfigurationException("The " + feature.label() + " TLS private key " + file
                    + " is encrypted, which is not supported. Use a PKCS12 keystore instead,"
                    + " or decrypt it." + conversion);
        }
        if (keyPem.contains("-----BEGIN RSA PRIVATE KEY-----")
                || keyPem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new TlsConfigurationException("The " + feature.label() + " TLS private key " + file
                    + " is a PKCS#1/SEC1 key, but a PKCS#8 key is required." + conversion);
        }
        throw new TlsConfigurationException("The " + feature.label() + " TLS private key " + file
                + " holds no PEM private key (" + BEGIN_PKCS8_KEY + ").");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private Path readableFile(String location, String property) {
        Path file = Path.of(location);
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new TlsConfigurationException("The " + feature.label() + " TLS file " + file.toAbsolutePath()
                    + " (" + property + ") does not exist or cannot be read.");
        }
        return file;
    }

    private byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TlsConfigurationException("Cannot read the " + feature.label() + " TLS file " + file + ": "
                    + e.getMessage(), e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /**
     * An expired certificate is not refused here: the clients will refuse it, and saying so loudly is
     * more useful than preventing the whole proxy from starting.
     */
    private void warnAboutValidity(X509Certificate leaf) {
        Date now = new Date();
        if (now.after(leaf.getNotAfter())) {
            LOG.warn("The " + feature.label() + " TLS certificate {} EXPIRED on {}: clients will refuse it.",
                    leaf.getSubjectX500Principal().getName(), leaf.getNotAfter());
        } else if (now.before(leaf.getNotBefore())) {
            LOG.warn("The " + feature.label() + " TLS certificate {} is NOT YET VALID (from {}): clients will"
                    + " refuse it.", leaf.getSubjectX500Principal().getName(), leaf.getNotBefore());
        }
    }

    /** The TLS settings of a SQL gateway cannot be used. */
    public static class TlsConfigurationException extends IllegalStateException {
        public TlsConfigurationException(String message) {
            super(message);
        }

        public TlsConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
