package dev.tgft.appliedintelligence.prototype.transport;

import java.net.*;
import java.net.http.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Loopback harness client, not a game API. Standard scoped PKIX and hostname verification. */
final class DeviceClient implements AutoCloseable {
  final SecureHost.Invitation invitation;
  final KeyPair key;
  final byte[] csr;
  final java.security.cert.X509Certificate anchor;
  final HttpClient enrollment;
  HttpClient control;
  Pki.Identity identity;
  URI target;

  record Reply(int statusCode, byte[] body, javax.net.ssl.SSLSession session) {}

  DeviceClient(SecureHost.Invitation invitation) throws Exception {
    this(invitation, Pki.key());
  }

  DeviceClient(SecureHost.Invitation invitation, KeyPair key) throws Exception {
    validate(invitation);
    this.invitation = invitation;
    this.key = key;
    csr = Pki.csr(key);
    anchor = Pki.decode(Base64.getDecoder().decode(invitation.ca()));
    if (anchor.getBasicConstraints() < 0 || anchor.getPublicKey().getEncoded().length > 1024)
      throw new IllegalArgumentException("Invalid anchor");
    anchor.checkValidity();
    enrollment = Pki.client(anchor, null);
  }

  static void validate(SecureHost.Invitation invitation) throws Exception {
    if (invitation == null
        || Wire.bytes(invitation).length > 4096
        || !"ai-remote-v2-prototype".equals(invitation.version())
        || invitation.secret() == null
        || !invitation.secret().matches("[A-Za-z0-9_-]{43}")
        || invitation.ca() == null) throw new IllegalArgumentException("Invalid invitation");
    endpoint(invitation.enroll());
    endpoint(invitation.control());
  }

  static URI endpoint(String text) {
    var uri = URI.create(text);
    if (!"https".equals(uri.getScheme())
        || !"127.0.0.1".equals(uri.getHost())
        || uri.getPort() < 1
        || uri.getPort() > 65535
        || uri.getUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getFragment() != null
        || !uri.getRawPath().isEmpty())
      throw new IllegalArgumentException("Prototype requires numeric loopback HTTPS");
    return uri;
  }

  Reply enroll() throws Exception {
    return send(
        enrollment,
        URI.create(invitation.enroll() + "/enroll"),
        new SecureHost.Enrollment(invitation.secret(), Base64.getEncoder().encodeToString(csr)));
  }

  void accept(Reply response) throws Exception {
    if (response.statusCode() != 200) throw new IllegalArgumentException("Enrollment not approved");
    var reply = Wire.read(response.body(), SecureHost.EnrollmentReply.class);
    if (!"approved".equals(reply.state())
        || !Pki.fingerprint(key.getPublic()).equals(reply.fingerprint()))
      throw new IllegalArgumentException("Wrong enrollment identity");
    var leaf = Pki.decode(Base64.getDecoder().decode(reply.certificate()));
    leaf.verify(anchor.getPublicKey());
    leaf.checkValidity();
    if (!Arrays.equals(key.getPublic().getEncoded(), leaf.getPublicKey().getEncoded())
        || leaf.getBasicConstraints() != -1
        || !leaf.getExtendedKeyUsage().equals(List.of("1.3.6.1.5.5.7.3.2")))
      throw new IllegalArgumentException("Invalid client certificate");
    identity = new Pki.Identity(key, leaf, anchor);
    reconnect(endpoint(invitation.control()));
  }

  Reply call(String path, Object body) throws Exception {
    if (control == null) throw new IllegalStateException("Not enrolled");
    return send(control, URI.create(target + path), body);
  }

  void reconnect(URI endpoint) throws Exception {
    target = endpoint(endpoint.toString());
    if (identity == null) throw new IllegalStateException("Not enrolled");
    if (control != null) control.shutdownNow();
    control = Pki.client(anchor, identity);
  }

  static Reply send(HttpClient client, URI uri, Object body) throws Exception {
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6));
    if (body != null)
      request
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(Wire.bytes(body)));
    var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var input = response.body()) {
      var reader = new java.util.concurrent.FutureTask<byte[]>(() -> input.readNBytes(65537));
      Thread.ofVirtual().start(reader);
      byte[] bytes;
      try {
        bytes = reader.get(3, java.util.concurrent.TimeUnit.SECONDS);
      } finally {
        reader.cancel(true);
      }
      if (bytes.length > 65536) throw new java.io.IOException("Response too large");
      return new Reply(response.statusCode(), bytes, response.sslSession().orElseThrow());
    }
  }

  @Override
  public void close() {
    enrollment.shutdownNow();
    if (control != null) control.shutdownNow();
  }
}
