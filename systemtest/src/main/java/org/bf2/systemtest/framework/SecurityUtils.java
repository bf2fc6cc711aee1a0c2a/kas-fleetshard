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

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class SecurityUtils {

    public static final String CERT = "cert";
    public static final String KEY = "key";

    public static void main(String[] args) throws Exception {
        var config = getTLSConfig("example.com");
        System.out.println(config);
    }

    public static Map<String, String> getTLSConfig(String domain) throws Exception {
        Map<String, String> config = new HashMap<>(2);

        try {
            java.security.KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            PrivateKey privateKey = keyPair.getPrivate();
            X509Certificate selfCert = generate(keyPair, "SHA256WITHRSA", domain, 1);

            StringWriter keyWriter = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(keyWriter)) {
                writer.writeObject(privateKey);
            }
            config.put(KEY, keyWriter.toString());

            StringWriter certWriter = new StringWriter();
            try (JcaPEMWriter writer = new JcaPEMWriter(certWriter)) {
                writer.writeObject(selfCert);
            }
            config.put(CERT, certWriter.toString());

        } catch (Exception e) {
            e.printStackTrace();
            throw new AssertionError(e.getMessage());
        }

        return config;
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
