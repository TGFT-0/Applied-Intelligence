package dev.tgft.appliedintelligence.prototype.transport;

import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.pkcs.*;
import org.bouncycastle.pkcs.jcajce.*;

/** Standard PKI through BC/JCA/JSSE. No global trust changes, no custom trust manager. */
final class Pki {
  static final class Identity {
    final KeyPair keys;
    final X509Certificate[] chain;

    Identity(KeyPair keys, X509Certificate... chain) {
      this.keys = keys;
      this.chain = chain;
    }
  }

  final Identity ca;
  final Identity server;

  Pki() throws Exception {
    KeyPair keys = key();
    Instant now = Instant.now();
    var subject = new X500Name("CN=Applied Intelligence prototype CA");
    var builder =
        new JcaX509v3CertificateBuilder(
            subject,
            serial(),
            Date.from(now.minusSeconds(3600)),
            Date.from(now.plus(Duration.ofDays(365))),
            subject,
            keys.getPublic());
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    X509Certificate cert = convert(builder, keys.getPrivate());
    ca = new Identity(keys, cert);
    server = leaf(key(), false, now.minusSeconds(60), now.plus(Duration.ofDays(30)), "127.0.0.1");
  }

  static KeyPair key() throws Exception {
    var gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    return gen.generateKeyPair();
  }

  static BigInteger serial() {
    return new BigInteger(159, new SecureRandom()).add(BigInteger.ONE);
  }

  private static X509Certificate convert(JcaX509v3CertificateBuilder b, PrivateKey signer)
      throws Exception {
    var cert =
        new JcaX509CertificateConverter()
            .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer)));
    return cert;
  }

  Identity leaf(KeyPair key, boolean client, Instant before, Instant after, String ip)
      throws Exception {
    return new Identity(key, certificate(key.getPublic(), client, before, after, ip), ca.chain[0]);
  }

  X509Certificate certificate(
      PublicKey key, boolean client, Instant before, Instant after, String ip) throws Exception {
    var name = new X500Name(client ? "CN=Prototype device" : "CN=Prototype host");
    var b =
        new JcaX509v3CertificateBuilder(
            ca.chain[0], serial(), Date.from(before), Date.from(after), name, key);
    b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    b.addExtension(
        Extension.extendedKeyUsage,
        false,
        new ExtendedKeyUsage(
            client ? KeyPurposeId.id_kp_clientAuth : KeyPurposeId.id_kp_serverAuth));
    if (!client)
      b.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)));
    return convert(b, ca.keys.getPrivate());
  }

  static byte[] csr(KeyPair key) throws Exception {
    return new JcaPKCS10CertificationRequestBuilder(
            new X500Name("CN=Untrusted supplied name"), key.getPublic())
        .build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate()))
        .getEncoded();
  }

  static PublicKey verifyCsr(byte[] der) throws Exception {
    if (der.length < 1 || der.length > 4096) throw new Wire.Fault(400, "invalid_csr");
    var csr = new PKCS10CertificationRequest(der);
    if (!csr.isSignatureValid(
        new JcaContentVerifierProviderBuilder().build(csr.getSubjectPublicKeyInfo())))
      throw new Wire.Fault(400, "invalid_csr");
    PublicKey key =
        KeyFactory.getInstance("EC")
            .generatePublic(new X509EncodedKeySpec(csr.getSubjectPublicKeyInfo().getEncoded()));
    var parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    var expected = parameters.getParameterSpec(ECParameterSpec.class);
    var actual = ((java.security.interfaces.ECPublicKey) key).getParams();
    if (!expected.getCurve().equals(actual.getCurve())
        || !expected.getGenerator().equals(actual.getGenerator())
        || !expected.getOrder().equals(actual.getOrder())
        || expected.getCofactor() != actual.getCofactor()) throw new Wire.Fault(400, "invalid_csr");
    return key;
  }

  static X509Certificate decode(byte[] der) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new java.io.ByteArrayInputStream(der));
  }

  static String fingerprint(PublicKey key) {
    return Wire.hash(key.getEncoded());
  }

  static KeyStore keys(Identity identity) throws Exception {
    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setKeyEntry("identity", identity.keys.getPrivate(), new char[0], identity.chain);
    return store;
  }

  static KeyStore trust(X509Certificate root) throws Exception {
    var store = KeyStore.getInstance("PKCS12");
    store.load(null, null);
    store.setCertificateEntry("anchor", root);
    return store;
  }

  static SSLContext context(X509Certificate root, Identity identity) throws Exception {
    var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust(root));
    KeyManager[] managers = null;
    if (identity != null) {
      var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keys(identity), new char[0]);
      managers = kmf.getKeyManagers();
    }
    var context = SSLContext.getInstance("TLSv1.3");
    context.init(managers, tmf.getTrustManagers(), new SecureRandom());
    return context;
  }

  static HttpClient client(X509Certificate root, Identity identity) throws Exception {
    var params = new SSLParameters();
    params.setProtocols(new String[] {"TLSv1.3"});
    params.setEndpointIdentificationAlgorithm("HTTPS");
    return HttpClient.newBuilder()
        .sslContext(context(root, identity))
        .sslParameters(params)
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(ProxySelector.of(null))
        .build();
  }
}
