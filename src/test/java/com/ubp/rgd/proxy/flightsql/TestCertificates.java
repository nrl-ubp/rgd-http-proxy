package com.ubp.rgd.proxy.flightsql;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * Self-signed certificates for the TLS tests, valid for {@code localhost} and {@code 127.0.0.1},
 * written in every form the Flight SQL TLS settings accept or must refuse.
 */
final class TestCertificates {

    static final String PASSWORD = "changeit";

    final KeyPair keyPair;
    final X509Certificate certificate;

    private TestCertificates(KeyPair keyPair, X509Certificate certificate) {
        this.keyPair = keyPair;
        this.certificate = certificate;
    }

    static TestCertificates generate(String commonName) throws Exception {
        return generate(commonName, Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(30)));
    }

    static TestCertificates generate(String commonName, Instant notBefore, Instant notAfter)
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        X500Name subject = new X500Name("CN=" + commonName + ",O=UBP Test");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                BigInteger.valueOf(System.nanoTime()), Date.from(notBefore), Date.from(notAfter),
                subject, keyPair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
        return new TestCertificates(keyPair, certificate);
    }

    /** A PKCS12 keystore holding this key under {@code alias}, plus the extra entries given. */
    Path writeKeyStore(Path file, String alias, TestCertificates... others) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, keyPair.getPrivate(), PASSWORD.toCharArray(),
                new Certificate[]{certificate});
        for (int i = 0; i < others.length; i++) {
            store.setKeyEntry("other" + i, others[i].keyPair.getPrivate(), PASSWORD.toCharArray(),
                    new Certificate[]{others[i].certificate});
        }
        try (OutputStream output = Files.newOutputStream(file)) {
            store.store(output, PASSWORD.toCharArray());
        }
        return file;
    }

    /** A PKCS12 keystore holding only this certificate, as a trusted entry: no private key. */
    Path writeTrustStore(Path file) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("trusted", certificate);
        try (OutputStream output = Files.newOutputStream(file)) {
            store.store(output, PASSWORD.toCharArray());
        }
        return file;
    }

    Path writeCertificatePem(Path file) throws Exception {
        return Files.writeString(file, pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.US_ASCII);
    }

    Path writePkcs8KeyPem(Path file) throws Exception {
        return Files.writeString(file, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                StandardCharsets.US_ASCII);
    }

    /** The traditional OpenSSL form, {@code -----BEGIN RSA PRIVATE KEY-----}. */
    Path writePkcs1KeyPem(Path file) throws Exception {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(keyPair);
        }
        return Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    }

    byte[] certificatePem() throws Exception {
        return pem("CERTIFICATE", certificate.getEncoded()).getBytes(StandardCharsets.US_ASCII);
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
