package dev.tgft.appliedintelligence.prototype.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.*;

class BoundaryTest {
  static Pki pki;
  static final Boundary.Limits SHORT = new Boundary.Limits(300, 300, 300);

  @BeforeAll
  static void setup() throws Exception {
    pki = new Pki();
  }

  private static void write(Socket socket, String text) throws IOException {
    socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  private static void closesWithinDeadline(Socket socket) throws Exception {
    socket.setSoTimeout(2500);
    long start = System.nanoTime();
    try {
      socket.getInputStream().readAllBytes();
    } catch (SocketTimeoutException failure) {
      throw failure;
    } catch (SocketException closed) {
      /* An explicit reset also releases the resource. */
    }
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
  }

  private static Thread drip(Socket socket) {
    return Thread.ofVirtual()
        .start(
            () -> {
              try {
                while (!Thread.currentThread().isInterrupted()) {
                  write(socket, "x");
                  Thread.sleep(40);
                }
              } catch (IOException | InterruptedException closed) {
              }
            });
  }

  @Test
  void literalNullJsonIsAnInvalidRequest() {
    var failure = assertThrows(Wire.Fault.class,
        () -> Wire.read("null".getBytes(StandardCharsets.UTF_8), SecureHost.Enrollment.class));
    assertEquals(400, failure.status);
    assertEquals("invalid_request", failure.code);
  }

  @Test
  void clientRejectsMissingCertificateUsagesAndInvalidAnchorsWithoutNullPointer() throws Exception {
    var invitation = new SecureHost.Invitation("ai-remote-v2-prototype",
        "https://127.0.0.1:1", "https://127.0.0.1:2",
        Base64.getEncoder().encodeToString(pki.ca.chain[0].getEncoded()), Wire.random());
    try (var client = new DeviceClient(invitation)) {
      for (String missing : List.of("eku", "usage", "constraints")) {
        var builder = new JcaX509v3CertificateBuilder(
            pki.ca.chain[0], Pki.serial(), Date.from(Instant.now().minusSeconds(60)),
            Date.from(Instant.now().plusSeconds(3600)),
            new X500Name("CN=device"), client.key.getPublic());
        if (!missing.equals("constraints")) builder.addExtension(
            Extension.basicConstraints, true,
            new BasicConstraints(false));
        if (!missing.equals("usage")) builder.addExtension(
            Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature));
        if (!missing.equals("eku")) builder.addExtension(
            Extension.extendedKeyUsage, false,
            new ExtendedKeyUsage(
                KeyPurposeId.id_kp_clientAuth));
        var certificate = builder.build(new JcaContentSignerBuilder(
            "SHA256withECDSA").build(pki.ca.keys.getPrivate()));
        byte[] reply = Wire.bytes(new SecureHost.EnrollmentReply("approved",
            Pki.fingerprint(client.key.getPublic()),
            Base64.getEncoder().encodeToString(certificate.getEncoded())));
        assertThrows(IllegalArgumentException.class,
            () -> client.accept(new DeviceClient.Reply(200, reply, null)));
      }
    }
    for (String invalid : List.of("signature", "usage", "curve")) {
      var keys = Pki.key();
      if (invalid.equals("curve")) {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        keys = generator.generateKeyPair();
      }
      var subject = new X500Name("CN=anchor");
      var builder = new JcaX509v3CertificateBuilder(
          subject, Pki.serial(), Date.from(Instant.now().minusSeconds(60)),
          Date.from(Instant.now().plusSeconds(3600)), subject, keys.getPublic());
      builder.addExtension(Extension.basicConstraints, true,
          new BasicConstraints(0));
      builder.addExtension(Extension.keyUsage, true,
          new KeyUsage(invalid.equals("usage")
              ? KeyUsage.digitalSignature
              : KeyUsage.keyCertSign));
      var certificate = builder.build(new JcaContentSignerBuilder(
          "SHA256withECDSA").build(invalid.equals("signature")
              ? Pki.key().getPrivate() : keys.getPrivate()));
      var bad = new SecureHost.Invitation(invitation.version(), invitation.enroll(), invitation.control(),
          Base64.getEncoder().encodeToString(certificate.getEncoded()), invitation.secret());
      var failure = assertThrows(Exception.class, () -> new DeviceClient(bad));
      assertFalse(failure instanceof NullPointerException);
    }
  }

  @Test
  void partialTlsHandshakeHasAnAbsoluteDeadline() throws Exception {
    try (var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                SHORT,
                (q, r, c) -> {
                  fail("No HTTP before TLS");
                  return true;
                });
        var socket = new Socket("127.0.0.1", boundary.endpoint.getPort())) {
      write(socket, "\u0016\u0003\u0003\u0000\u0064");
      var writer = drip(socket);
      try {
        closesWithinDeadline(socket);
      } finally {
        writer.interrupt();
        writer.join(1000);
      }
    }
  }

  @Test
  void headersHaveAnAbsoluteDeadlineEvenWithContinuousTraffic() throws Exception {
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                SHORT,
                (q, r, c) -> {
                  fail("Incomplete headers");
                  return true;
                });
        var socket =
            (SSLSocket)
                client
                    .sslContext()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
      socket.startHandshake();
      write(socket, "GET /v2/ping HTTP/1.1\r\nX-Drip: ");
      var writer = drip(socket);
      try {
        closesWithinDeadline(socket);
      } finally {
        writer.interrupt();
        writer.join(1000);
      }
    }
  }

  @Test
  void bodyHasAnAbsoluteDeadlineEvenWithContinuousTraffic() throws Exception {
    var ref = new AtomicReference<Boundary>();
    var complete = new AtomicBoolean();
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                new Boundary.Limits(2000, 2000, 300),
                (q, r, c) -> {
                  var bytes = ref.get().body(q, 20000);
                  complete.set(bytes.length == 10000);
                  Boundary.reply(r, c, 200, new Wire.Error("done"));
                  return true;
                });
        var socket =
            (SSLSocket)
                client
                    .sslContext()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
      ref.set(boundary);
      socket.startHandshake();
      write(
          socket,
          "POST /v2/simulations HTTP/1.1\r\nHost: 127.0.0.1:"
              + boundary.endpoint.getPort()
              + "\r\nContent-Type: application/json\r\nContent-Length: 10000\r\n\r\nx");
      var writer = drip(socket);
      try {
        closesWithinDeadline(socket);
        assertFalse(complete.get());
      } finally {
        writer.interrupt();
        writer.join(1000);
      }
    }
  }

  @Test
  void wrongHostPlaintextAndOldTlsAreRejected() throws Exception {
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                SHORT,
                (q, r, c) -> {
                  Boundary.reply(r, c, 200, new Wire.Error("ok"));
                  return true;
                })) {
      try (var socket =
          (SSLSocket)
              client
                  .sslContext()
                  .getSocketFactory()
                  .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
        socket.startHandshake();
        socket.setSoTimeout(2000);
        write(socket, "GET / HTTP/1.1\r\nHost: attacker.invalid\r\n\r\n");
        assertTrue(
            new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII)
                .startsWith("HTTP/1.1 400"));
      }
      try (var socket = new Socket("127.0.0.1", boundary.endpoint.getPort())) {
        write(socket, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        closesWithinDeadline(socket);
      }
      try (var socket =
          (SSLSocket)
              client
                  .sslContext()
                  .getSocketFactory()
                  .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
        socket.setEnabledProtocols(new String[] {"TLSv1.2"});
        socket.setSoTimeout(2000);
        assertThrows(IOException.class, socket::startHandshake);
      }
    }
  }

  @Test
  void clientDoesNotFollowRedirectsAndBoundsResponseBytes() throws Exception {
    var reached = new AtomicInteger();
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                Boundary.Limits.defaults(),
                (q, r, c) -> {
                  if (q.getHttpURI().getPath().equals("/redirect")) {
                    r.getHeaders().put("Location", "/target");
                    Boundary.reply(r, c, 302, new Wire.Error("redirect"));
                  } else if (q.getHttpURI().getPath().equals("/target")) {
                    reached.incrementAndGet();
                    Boundary.reply(r, c, 200, new Wire.Error("target"));
                  } else Boundary.reply(r, c, 200, new Wire.Error("x".repeat(65537)));
                  return true;
                })) {
      assertEquals(
          302,
          DeviceClient.send(client, URI.create(boundary.endpoint + "/redirect"), null)
              .statusCode());
      assertEquals(0, reached.get());
      assertThrows(
          IOException.class,
          () -> DeviceClient.send(client, URI.create(boundary.endpoint + "/oversize"), null));
    }
  }

  @Test
  void conflictingHttpFramingAndOversizedHeadersNeverReachRoutes() throws Exception {
    var reached = new AtomicInteger();
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                Boundary.Limits.defaults(),
                (q, r, c) -> {
                  reached.incrementAndGet();
                  Boundary.reply(r, c, 200, new Wire.Error("unexpected"));
                  return true;
                })) {
      String host = "Host: 127.0.0.1:" + boundary.endpoint.getPort() + "\r\n";
      for (String malformed :
          java.util.List.of(
              "POST / HTTP/1.1\r\n" + host + "Content-Length: 1\r\nContent-Length: 2\r\n\r\nxx",
              "POST / HTTP/1.1\r\n"
                  + host
                  + "Content-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
              "GET / HTTP/1.1\r\n" + host + "X-Large: " + "x".repeat(9000) + "\r\n\r\n")) {
        try (var socket =
            (SSLSocket)
                client
                    .sslContext()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
          socket.startHandshake();
          socket.setSoTimeout(3000);
          write(socket, malformed);
          String status =
              new BufferedReader(
                      new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                  .readLine();
          assertTrue(status.startsWith("HTTP/1.1 400") || status.startsWith("HTTP/1.1 431"));
        }
      }
      assertEquals(0, reached.get());
    }
  }

  @Test
  void rejectsAuthorityOverridePathsInvalidLeafUsageUpgradeHeadersAndGetBodies() throws Exception {
    var invitation =
        new SecureHost.Invitation(
            "ai-remote-v2-prototype",
            "https://127.0.0.1:1",
            "https://127.0.0.1:2",
            Base64.getEncoder().encodeToString(pki.ca.chain[0].getEncoded()),
            Wire.random());
    try (var client = new DeviceClient(invitation)) {
      byte[] nullCertReply =
          Wire.bytes(
              new SecureHost.EnrollmentReply(
                  "approved", Pki.fingerprint(client.key.getPublic()), null));
      assertThrows(
          IllegalArgumentException.class,
          () -> client.accept(new DeviceClient.Reply(200, nullCertReply, null)));

      var caLeafBuilder =
          new JcaX509v3CertificateBuilder(
              pki.ca.chain[0],
              Pki.serial(),
              Date.from(Instant.now().minusSeconds(60)),
              Date.from(Instant.now().plusSeconds(3600)),
              new X500Name("CN=device"),
              client.key.getPublic());
      caLeafBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
      caLeafBuilder.addExtension(
          Extension.keyUsage,
          true,
          new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign));
      caLeafBuilder.addExtension(
          Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
      var caLeaf =
          caLeafBuilder.build(
              new JcaContentSignerBuilder("SHA256withECDSA").build(pki.ca.keys.getPrivate()));
      byte[] badUsageReply =
          Wire.bytes(
              new SecureHost.EnrollmentReply(
                  "approved",
                  Pki.fingerprint(client.key.getPublic()),
                  Base64.getEncoder().encodeToString(caLeaf.getEncoded())));
      assertThrows(
          IllegalArgumentException.class,
          () -> client.accept(new DeviceClient.Reply(200, badUsageReply, null)));
    }

    var reached = new AtomicInteger();
    var ref = new AtomicReference<Boundary>();
    try (var client = Pki.client(pki.ca.chain[0], null);
        var boundary =
            Boundary.open(
                pki,
                pki.server,
                false,
                Boundary.Limits.defaults(),
                (q, r, c) -> {
                  if (q.getMethod().equals("POST")) ref.get().body(q, 1024);
                  reached.incrementAndGet();
                  Boundary.reply(r, c, 200, new Wire.Error("ok"));
                  return true;
                })) {
      ref.set(boundary);
      var device =
          new DeviceClient(
              new SecureHost.Invitation(
                  "ai-remote-v2-prototype",
                  boundary.endpoint.toString(),
                  boundary.endpoint.toString(),
                  Base64.getEncoder().encodeToString(pki.ca.chain[0].getEncoded()),
                  Wire.random()));
      var leaf =
          pki.leaf(
              device.key,
              true,
              Instant.now().minusSeconds(60),
              Instant.now().plusSeconds(3600),
              null);
      device.identity = new Pki.Identity(device.key, leaf.chain[0], pki.ca.chain[0]);
      device.reconnect(boundary.endpoint);
      try (device) {
        for (String badPath :
            List.of("@evil.invalid/v2/ping", "//evil.invalid/v2/ping", "/v2/ping?x=1", "/v2/ping#f"))
          assertThrows(IllegalArgumentException.class, () -> device.call(badPath, null));
      }

      String host = "Host: 127.0.0.1:" + boundary.endpoint.getPort() + "\r\n";
      for (String rejected :
          List.of(
              "GET /v2/ping HTTP/1.1\r\n" + host + "Upgrade: websocket\r\n\r\n",
              "GET /v2/ping HTTP/1.1\r\n" + host + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n",
              "GET /v2/ping HTTP/1.1\r\n" + host + "Proxy-Authorization: Basic YWJj\r\n\r\n",
              "GET /v2/ping HTTP/1.1\r\n" + host + "Content-Length: 4\r\n\r\ntest",
              "POST /v2/ping HTTP/1.1\r\n"
                  + host
                  + "Content-Type: application/json\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}")) {
        try (var socket =
            (SSLSocket)
                client
                    .sslContext()
                    .getSocketFactory()
                    .createSocket("127.0.0.1", boundary.endpoint.getPort())) {
          socket.startHandshake();
          socket.setSoTimeout(3000);
          write(socket, rejected);
          String rawResponse =
              new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
          assertTrue(
              rawResponse.startsWith("HTTP/1.1 400")
                  || rawResponse.startsWith("HTTP/1.1 403")
                  || rawResponse.startsWith("HTTP/1.1 415"));
          assertTrue(
              rawResponse.lines()
                  .skip(1)
                  .takeWhile(line -> !line.isEmpty())
                  .anyMatch("X-Content-Type-Options: nosniff"::equalsIgnoreCase),
              rawResponse);
        }
      }
      assertEquals(0, reached.get());
    }
  }
}
