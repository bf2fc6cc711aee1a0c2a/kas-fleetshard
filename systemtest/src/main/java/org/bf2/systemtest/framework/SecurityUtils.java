package org.bf2.systemtest.framework;

import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public class SecurityUtils {

    public static class TlsConfig {
        private String cert;
        private String key;
        private ByteArrayOutputStream truststore;

        public TlsConfig(String cert, String key, ByteArrayOutputStream truststore) {
            super();
            this.cert = cert;
            this.key = key;
            this.truststore = truststore;
        }

        public String getCert() {
            return cert;
        }

        public String getKey() {
            return key;
        }

        public byte[] getTruststore() {
            return truststore.toByteArray();
        }
    }

    public static final String TRUSTSTORE_PASSWORD = "changeit";

    public static void main(String[] args) throws Exception {
        var config = getTLSConfig("example.com");
        System.out.println(config);
    }

    public static TlsConfig getTLSConfig(String domain) throws Exception {
        java.security.KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        PrivateKey privateKey = keyPair.getPrivate();
        X509Certificate selfCert = generate(keyPair, "SHA256WITHRSA", domain, 1);

        StringWriter keyWriter = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(keyWriter)) {
            writer.writeObject(privateKey);
        }

        StringWriter certWriter = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(certWriter)) {
            writer.writeObject(selfCert);
        }
        KeyStore ks = KeyStore.getInstance("jks");
        char[] password = TRUSTSTORE_PASSWORD.toCharArray();
        ks.load(null, password);
        ks.setCertificateEntry("server", selfCert);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ks.store(baos, password);
        baos.close();

        return new TlsConfig(certWriter.toString(), keyWriter.toString(), baos);
    }

    private static X509Certificate generate(final KeyPair keyPair,
                                           final String hashAlgorithm,
                                           final String domain,
                                           final int days)
            throws OperatorCreationException, CertificateException, CertIOException {

        final Instant now = Instant.now();
        final Date notBefore = Date.from(now);
        final Date notAfter = Date.from(now.plus(Duration.ofDays(days)));
        final X500Name x500Issuer = new X500Name("CN=" + domain);
        final X500Name x500Name = new X500Name("CN=*." + domain);
        final SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());
        final DigestCalculator digCalc = new BcDigestCalculatorProvider().get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));
        final SubjectKeyIdentifier subject = new X509ExtensionUtils(digCalc).createSubjectKeyIdentifier(publicKeyInfo);
        final AuthorityKeyIdentifier authority = new X509ExtensionUtils(digCalc).createAuthorityKeyIdentifier(publicKeyInfo);
        final X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(x500Issuer,
                                                                                            BigInteger.valueOf(now.toEpochMilli()),
                                                                                            notBefore,
                                                                                            notAfter,
                                                                                            x500Name,
                                                                                            keyPair.getPublic());
        certificateBuilder.addExtension(Extension.subjectKeyIdentifier, false, subject);
        certificateBuilder.addExtension(Extension.authorityKeyIdentifier, false, authority);
        certificateBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        final ContentSigner contentSigner = new JcaContentSignerBuilder(hashAlgorithm).build(keyPair.getPrivate());

        return new JcaX509CertificateConverter()
                                                .setProvider(new BouncyCastleProvider())
                                                .getCertificate(certificateBuilder.build(contentSigner));
    }

}
