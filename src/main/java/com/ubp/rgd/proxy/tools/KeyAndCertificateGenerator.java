package com.ubp.rgd.proxy.tools;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Use this program to generate a private key and a certificate to use in DEVELOPMENT servers for HTTPS.
 * The program will generate a private_key.pem and a certificate.pem files. Use them to configure
 * the HTTPS server in development environment ONLY.
 */
public class KeyAndCertificateGenerator {

    public static void main(String[] args) throws Exception {
        // Generate a key pair
        KeyPair keyPair = generateKeyPair();

        // Generate a self-signed certificate
        X509Certificate certificate = generateCertificate(keyPair);

        // Save the private key and certificate in PEM format
        saveToPEM("./config/certs/private_key.pem", keyPair.getPrivate());
        saveToPEM("./config/certs/certificate.pem", certificate);

        System.out.println("Private key and certificate generated successfully!");

        File keystorePath = new File("./config/certs/serverkeystore.p12");
        if (!keystorePath.exists()) {
            System.out.println("Now creating key store file...");
            saveKeyStore(certificate, keyPair, keystorePath);
            System.out.println("Keystore saved.");
        } else {
            System.out.println("Server keystore already exsists.");
        }
    }

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048); // 2048-bit RSA key
        return keyPairGenerator.generateKeyPair();
    }

    private static X509Certificate generateCertificate(KeyPair keyPair) throws Exception {
        // Certificate details
        X500Name issuer = new X500Name("CN=Self-Signed Certificate");
        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000)); // 1 year validity

        // Create the certificate
        JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                issuer, serialNumber, notBefore, notAfter, issuer, keyPair.getPublic()
        );

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        X509CertificateHolder certificateHolder = certificateBuilder.build(contentSigner);

        return new JcaX509CertificateConverter().getCertificate(certificateHolder);
    }

    private static void saveToPEM(String fileName, Object object) throws IOException {
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(new FileWriter(fileName))) {
            pemWriter.writeObject(object);
        }
    }

    private static void saveKeyStore(X509Certificate certificate, KeyPair keyPair, File targetFile)
    throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null); //init

        String alias = "server-cert";
        char[] password = "changeit".toCharArray();
        keyStore.setKeyEntry(alias, keyPair.getPrivate(), password, new Certificate[]{certificate});

        try(OutputStream outputStream = new FileOutputStream(targetFile)) {
            keyStore.store(outputStream, password);
        }
    }
}

