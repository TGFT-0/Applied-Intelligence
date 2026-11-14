package dev.tgft.appliedintelligence.prototype.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class TransportTest {
  @TempDir Path directory;
  static Pki pki;
  SecureHost host;
  final List<DeviceClient> clients = new ArrayList<>();
  MutableClock clock;

  static class MutableClock extends Clock {
    long now = System.currentTimeMillis();

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return Instant.ofEpochMilli(now);
    }
  }

  @BeforeAll
  static void identity() throws Exception {
    pki = new Pki();
  }

  @BeforeEach
  void start() throws Exception {
    clock = new MutableClock();
    host = new SecureHost(directory.resolve("private"), pki, clock, Boundary.Limits.defaults());
  }

  @AfterEach
  void stop() throws Exception {
    for (var client : clients) client.close();
    if (host != null) host.close();
  }

  static void await(BooleanSupplier test) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (!test.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(test.getAsBoolean(), "Timed out waiting for prototype state");
  }

  DeviceClient pending() throws Exception {
    var client = new DeviceClient(host.openEnrollment(Duration.ofMinutes(2)));
    clients.add(client);
    var response = client.enroll();
    assertEquals(200, response.statusCode());
    assertEquals("pending", Wire.read(response.body(), SecureHost.EnrollmentReply.class).state());
    return client;
  }

  DeviceClient paired(String... scopes) throws Exception {
    var client = pending();
    host.approve(Pki.fingerprint(client.key.getPublic()), Set.of(scopes));
    client.accept(client.enroll());
    return client;
  }

  @Test
  void realTls13RequiresLocalApprovalAndSeparateClientCertificate() throws Exception {
    var client = pending();
    assertEquals(Pki.fingerprint(client.key.getPublic()), host.pendingFingerprint());
    assertThrows(IllegalArgumentException.class, () -> client.accept(client.enroll()));
    host.approve(host.pendingFingerprint(), Set.of("observe"));
    client.accept(client.enroll());
    var response = client.call("/v2/ping", null);
    assertEquals(200, response.statusCode());
    assertEquals("TLSv1.3", response.session().getProtocol());
    assertEquals(
        Pki.fingerprint(pki.ca.keys.getPublic()),
        Wire.read(response.body(), SecureHost.Ping.class).identity());
    assertFalse(client.invitation.toString().contains(client.invitation.secret()));
  }

  @Test
  void noCertificateUnknownDeviceAndForeignCaCannotAuthenticate() throws Exception {
    try (var noCertificate = Pki.client(pki.ca.chain[0], null)) {
      assertThrows(
          IOException.class,
          () -> DeviceClient.send(noCertificate, URI.create(host.endpoint() + "/v2/ping"), null));
    }
    var unknown =
        pki.leaf(
            Pki.key(), true, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), null);
    try (var client = Pki.client(pki.ca.chain[0], unknown)) {
      assertEquals(
          403,
          DeviceClient.send(client, URI.create(host.endpoint() + "/v2/ping"), null).statusCode());
    }
    var foreign = new Pki();
    var wrong =
        foreign.leaf(
            Pki.key(), true, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), null);
    try (var client = Pki.client(pki.ca.chain[0], wrong)) {
      assertThrows(
          IOException.class,
          () -> DeviceClient.send(client, URI.create(host.endpoint() + "/v2/ping"), null));
    }
  }

  @Test
  void wrongPinnedCaAndWrongServerSanFailBeforeEnrollmentDataIsAccepted() throws Exception {
    var invitation = host.openEnrollment(Duration.ofMinutes(2));
    var impostor = new Pki();
    var wrong =
        new SecureHost.Invitation(
            invitation.version(),
            invitation.enroll(),
            invitation.control(),
            Base64.getEncoder().encodeToString(impostor.ca.chain[0].getEncoded()),
            invitation.secret());
    try (var client = new DeviceClient(wrong)) {
      assertThrows(IOException.class, client::enroll);
      assertEquals("", host.pendingFingerprint());
    }
    var key = Pki.key();
    var serverIdentity =
        pki.leaf(
            key,
            false,
            Instant.now().minusSeconds(60),
            Instant.now().plusSeconds(3600),
            "127.0.0.2");
    try (var server =
            Boundary.open(
                pki,
                serverIdentity,
                false,
                Boundary.Limits.defaults(),
                (q, r, c) -> {
                  Boundary.reply(r, c, 200, new Wire.Error("unexpected"));
                  return true;
                });
        var client = Pki.client(pki.ca.chain[0], null)) {
      assertThrows(IOException.class, () -> DeviceClient.send(client, server.endpoint, null));
    }
  }

  @Test
  void expiredClientCertificateAndServerPurposeCertificateAreRejected() throws Exception {
    var expired =
        pki.leaf(
            Pki.key(),
            true,
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(60),
            null);
    try (var client = Pki.client(pki.ca.chain[0], expired)) {
      assertThrows(
          IOException.class,
          () -> DeviceClient.send(client, URI.create(host.endpoint() + "/v2/ping"), null));
    }
    try (var client = Pki.client(pki.ca.chain[0], pki.server)) {
      assertThrows(
          IOException.class,
          () -> DeviceClient.send(client, URI.create(host.endpoint() + "/v2/ping"), null));
    }
  }

  @Test
  void invitationExpiryWrongSecretAndCsrReplacementCannotGrantAccess() throws Exception {
    var client = pending();
    var bad =
        new SecureHost.Enrollment("A".repeat(43), Base64.getEncoder().encodeToString(client.csr));
    assertEquals(
        403,
        DeviceClient.send(
                client.enrollment, URI.create(client.invitation.enroll() + "/enroll"), bad)
            .statusCode());
    var replacement =
        new SecureHost.Enrollment(
            client.invitation.secret(), Base64.getEncoder().encodeToString(Pki.csr(Pki.key())));
    assertEquals(
        409,
        DeviceClient.send(
                client.enrollment, URI.create(client.invitation.enroll() + "/enroll"), replacement)
            .statusCode());
    assertThrows(Wire.Fault.class, () -> host.approve("wrong-fingerprint", Set.of("observe")));
    clock.now += Duration.ofMinutes(3).toMillis();
    assertEquals(403, client.enroll().statusCode());
    assertThrows(
        Wire.Fault.class, () -> host.approve(host.pendingFingerprint(), Set.of("observe")));
  }

  @Test
  void duplicateEnrollmentAndApprovalDoNotCreateExtraAuthority() throws Exception {
    var client = pending();
    assertEquals(
        "pending", Wire.read(client.enroll().body(), SecureHost.EnrollmentReply.class).state());
    assertThrows(
        Wire.Fault.class,
        () -> host.approve(host.pendingFingerprint(), Set.of("observe", "admin")));
    host.approve(host.pendingFingerprint(), Set.of("observe"));
    var first = client.enroll();
    var second = client.enroll();
    assertArrayEquals(first.body(), second.body());
    assertThrows(
        Wire.Fault.class,
        () -> host.approve(host.pendingFingerprint(), Set.of("observe", "simulate")));
    client.accept(first);
    assertEquals(
        403,
        client
            .call(
                "/v2/simulations", new SecureHost.Simulation(UUID.randomUUID().toString(), "fake"))
            .statusCode());
  }

  @Test
  void clientCertificatesIgnoreCsrSubjectAndDoNotCarryCaAuthority() throws Exception {
    var client = paired("observe");
    var leaf = client.identity.chain[0];
    assertEquals(-1, leaf.getBasicConstraints());
    assertEquals(List.of("1.3.6.1.5.5.7.3.2"), leaf.getExtendedKeyUsage());
    assertFalse(leaf.getSubjectX500Principal().getName().contains("Untrusted"));
    assertEquals(
        403,
        client
            .call(
                "/v2/simulations", new SecureHost.Simulation(UUID.randomUUID().toString(), "fake"))
            .statusCode());
  }

  @Test
  void malformedAndAlteredCsrCannotBeApproved() throws Exception {
    var invitation = host.openEnrollment(Duration.ofMinutes(2));
    try (var client = new DeviceClient(invitation)) {
      byte[] tampered = client.csr.clone();
      tampered[tampered.length - 1] ^= 1;
      var response =
          DeviceClient.send(
              client.enrollment,
              URI.create(invitation.enroll() + "/enroll"),
              new SecureHost.Enrollment(
                  invitation.secret(), Base64.getEncoder().encodeToString(tampered)));
      assertEquals(400, response.statusCode());
      assertEquals("", host.pendingFingerprint());
      assertThrows(Wire.Fault.class, () -> host.approve("any", Set.of("observe")));
    }
  }

  @Test
  void disconnectDuringAcceptedWorkKeepsRunningAndReconnectDoesNotRerun() throws Exception {
    var client = paired("observe", "simulate");
    String id = UUID.randomUUID().toString();
    var job = new SecureHost.Simulation(id, "no model or files");
    byte[] body = Wire.bytes(job);
    var factory = client.control.sslContext().getSocketFactory();
    try (var socket = (SSLSocket) factory.createSocket("127.0.0.1", host.endpoint().getPort())) {
      socket.startHandshake();
      var out = socket.getOutputStream();
      out.write(
          ("POST /v2/simulations HTTP/1.1\r\nHost: 127.0.0.1:"
                  + host.endpoint().getPort()
                  + "\r\nContent-Type: application/json\r\nContent-Length: "
                  + body.length
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      await(
          () -> {
            try {
              return host.store
                  .job(Pki.fingerprint(client.key.getPublic()), id, clock.millis())
                  .state()
                  .equals("running");
            } catch (Exception e) {
              return false;
            }
          });
      socket.setSoLinger(true, 0); // Drop the network before reading the admission acknowledgment.
    }
    client.reconnect(host.endpoint());
    try (var retries = Executors.newVirtualThreadPerTaskExecutor()) {
      var attempts = new ArrayList<Future<DeviceClient.Reply>>();
      for (int i = 0; i < 3; i++)
        attempts.add(retries.submit(() -> client.call("/v2/simulations", job)));
      for (var attempt : attempts) assertEquals(200, attempt.get(3, TimeUnit.SECONDS).statusCode());
    }
    client.control.shutdownNow();
    await(
        () ->
            host.store
                .job(Pki.fingerprint(client.key.getPublic()), id, clock.millis())
                .state()
                .equals("completed"));
    client.reconnect(host.endpoint());
    var retry = client.call("/v2/simulations", job);
    var record = Wire.read(retry.body(), GrantStore.Job.class);
    assertEquals("completed", record.state());
    assertEquals(1, record.executions());
    assertEquals(20, record.ticks());
    assertEquals(1, host.mockWorkerStarts.get());
    assertEquals(
        409,
        client
            .call("/v2/simulations", new SecureHost.Simulation(id, "changed payload"))
            .statusCode());
  }

  @Test
  void deviceCannotReadAnotherDevicesJobAndSameUuidHasSeparateNamespace() throws Exception {
    var first = paired("observe", "simulate");
    String id = UUID.randomUUID().toString();
    first.call("/v2/simulations", new SecureHost.Simulation(id, "first"));
    var second = paired("observe", "simulate");
    assertEquals(404, second.call("/v2/simulations/" + id, null).statusCode());
    await(
        () ->
            host.store
                .job(Pki.fingerprint(first.key.getPublic()), id, clock.millis())
                .state()
                .equals("completed"));
    assertEquals(
        200, second.call("/v2/simulations", new SecureHost.Simulation(id, "second")).statusCode());
  }

  @Test
  void revocationClosesExistingEventStreamAndStopsOnlyOwnedMockWork() throws Exception {
    var client = paired("observe", "simulate");
    String id = UUID.randomUUID().toString();
    client.call("/v2/simulations", new SecureHost.Simulation(id, "fake"));
    var response =
        client.control.send(
            HttpRequest.newBuilder(URI.create(host.endpoint() + "/v2/events"))
                .timeout(Duration.ofSeconds(5))
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    try (var in = response.body()) {
      assertEquals('i', in.read());
      String fp = Pki.fingerprint(client.key.getPublic());
      host.revoke(fp);
      var closed =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  in.readAllBytes();
                  return true;
                } catch (IOException e) {
                  return true;
                }
              });
      assertTrue(closed.get(3, TimeUnit.SECONDS));
      client.reconnect(host.endpoint());
      assertEquals(403, client.call("/v2/ping", null).statusCode());
      assertThrows(Wire.Fault.class, () -> host.store.job(fp, id, clock.millis()));
    }
  }

  @Test
  void grantExpiryAndRenewalNeverWidenPermissions() throws Exception {
    var client = paired("observe");
    String fp = Pki.fingerprint(client.key.getPublic());
    var before = host.store.require(fp, "observe", clock.millis());
    var renew = client.call("/v2/renew", new SecureHost.Empty());
    client.accept(
        new DeviceClient.Reply(
            200,
            Wire.bytes(
                new SecureHost.EnrollmentReply(
                    "approved",
                    fp,
                    Wire.read(renew.body(), SecureHost.EnrollmentReply.class).certificate())),
            renew.session()));
    var after = host.store.require(fp, "observe", clock.millis());
    assertEquals(before.until(), after.until());
    assertEquals(before.scopes(), after.scopes());
    assertEquals(400, raw(client, "/v2/renew", "{\"admin\":true}").statusCode());
    clock.now += Duration.ofDays(31).toMillis();
    assertEquals(403, client.call("/v2/ping", null).statusCode());
  }

  @Test
  void restartRetainsRevocationsAndDoesNotReplayInterruptedJobsOrInvitations() throws Exception {
    var client = paired("observe", "simulate");
    String id = UUID.randomUUID().toString();
    client.call("/v2/simulations", new SecureHost.Simulation(id, "fake"));
    var invitation = host.openEnrollment(Duration.ofMinutes(2));
    host.close();
    host = new SecureHost(directory.resolve("private"), pki, clock, Boundary.Limits.defaults());
    client.reconnect(host.endpoint());
    var job = Wire.read(client.call("/v2/simulations/" + id, null).body(), GrantStore.Job.class);
    assertEquals("interrupted", job.state());
    assertEquals(
        "interrupted",
        Wire.read(
                client.call("/v2/simulations", new SecureHost.Simulation(id, "fake")).body(),
                GrantStore.Job.class)
            .state());
    try (var old = new DeviceClient(invitation)) {
      assertThrows(IOException.class, old::enroll);
    }
    String fp = Pki.fingerprint(client.key.getPublic());
    host.revoke(fp);
    host.close();
    host = new SecureHost(directory.resolve("private"), pki, clock, Boundary.Limits.defaults());
    client.reconnect(host.endpoint());
    assertEquals(403, client.call("/v2/ping", null).statusCode());
  }

  DeviceClient.Reply raw(DeviceClient client, String path, String text) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(host.endpoint() + path))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(text))
            .build();
    var r = client.control.send(request, HttpResponse.BodyHandlers.ofByteArray());
    return new DeviceClient.Reply(r.statusCode(), r.body(), r.sslSession().orElseThrow());
  }

  @Test
  void strictJsonAndRetiredOrArbitraryControlPathsAreRefused() throws Exception {
    var client = paired("observe", "simulate");
    String id = UUID.randomUUID().toString();
    for (String body :
        List.of(
            "{\"id\":\"" + id + "\",\"id\":\"" + id + "\",\"payload\":\"x\"}",
            "{\"id\":\"" + id + "\",\"payload\":\"x\",\"command\":\"anything\"}",
            "{} {}",
            "{\"id\":123,\"payload\":\"x\"}"))
      assertEquals(400, raw(client, "/v2/simulations", body).statusCode());
    for (String path : List.of("/ai/v1/dispatch", "/v2/admin", "/v2/files", "/exec"))
      assertEquals(404, client.call(path, null).statusCode());
    assertEquals(413, raw(client, "/v2/simulations", "x".repeat(4097)).statusCode());
  }

  @Test
  void browserOriginFetchHeadersAndBearerCannotUseEvenACertifiedConnection() throws Exception {
    var client = paired("observe");
    for (String[] header :
        List.of(
            new String[] {"Origin", "https://evil.invalid"},
            new String[] {"Origin", "null"},
            new String[] {"Sec-Fetch-Site", "cross-site"},
            new String[] {"Authorization", "Bearer unused"},
            new String[] {"Cookie", "session=unused"})) {
      var request =
          HttpRequest.newBuilder(URI.create(host.endpoint() + "/v2/ping"))
              .header(header[0], header[1])
              .build();
      assertEquals(
          403, client.control.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
  }

  @Test
  void externalOrAmbiguousInvitationRoutesAreRejectedBeforeNetworkAccess() throws Exception {
    var i = host.openEnrollment(Duration.ofMinutes(2));
    for (String address :
        List.of(
            "http://127.0.0.1:1",
            "https://localhost:1",
            "https://example.com:443",
            "https://127.0.0.1:1/?secret=x",
            "https://user@127.0.0.1:1",
            "https://127.0.0.1:1#x",
            "https://127.0.0.1:1/anything",
            "https://192.168.1.1:443"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new DeviceClient(
                  new SecureHost.Invitation(
                      i.version(), address, i.control(), i.ca(), i.secret())));
  }

  @Test
  void storeRejectsSecondOwnerMismatchedIdentityAndCorruptState() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            new GrantStore(directory.resolve("private"), Pki.fingerprint(pki.ca.keys.getPublic())));
    host.close();
    assertThrows(
        IOException.class, () -> new GrantStore(directory.resolve("private"), "different-host"));
    Files.writeString(directory.resolve("private/state.json"), "{broken");
    assertThrows(
        IOException.class,
        () ->
            new GrantStore(directory.resolve("private"), Pki.fingerprint(pki.ca.keys.getPublic())));
  }

  @Test
  void validEventStreamSurvivesHeaderDeadline() throws Exception {
    host.close();
    host =
        new SecureHost(
            directory.resolve("private"), pki, clock, new Boundary.Limits(2000, 300, 2000));
    var client = paired("observe");
    var response =
        client.control.send(
            HttpRequest.newBuilder(URI.create(host.endpoint() + "/v2/events")).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var in =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
      assertEquals("id: 1", in.readLine());
      Thread.sleep(800);
      boolean later = false;
      for (int i = 0; i < 15; i++) {
        String line = in.readLine();
        if (line != null && line.startsWith("id: ") && !line.equals("id: 1")) {
          later = true;
          break;
        }
      }
      assertTrue(later);
    }
  }

  @Test
  void persistenceFailureDeniesAuthorityUntilRecovery() throws Exception {
    var client = paired("observe", "simulate");
    String fp = Pki.fingerprint(client.key.getPublic());
    Path file = directory.resolve("private/state.json");
    Files.delete(file);
    Files.createDirectory(file);
    Files.writeString(file.resolve("obstruction"), "test only");
    assertThrows(IOException.class, () -> host.revoke(fp));
    assertEquals(503, client.call("/v2/ping", null).statusCode());
    assertEquals(
        503,
        client
            .call(
                "/v2/simulations", new SecureHost.Simulation(UUID.randomUUID().toString(), "fake"))
            .statusCode());
  }

  @Test
  void temporaryFileFailureAlsoDeniesAuthority() throws Exception {
    var client = paired("observe");
    String fp = Pki.fingerprint(client.key.getPublic());
    Path old = directory.resolve("private"), moved = directory.resolve("moved");
    Files.move(old, moved);
    assertThrows(IOException.class, () -> host.revoke(fp));
    assertEquals(503, client.call("/v2/ping", null).statusCode());
  }

  @Test
  void revocationPersistsCancellationAndOtherDevicesRemainUsable() throws Exception {
    var first = paired("observe", "simulate");
    var second = paired("observe", "simulate");
    String firstId = UUID.randomUUID().toString(), secondId = UUID.randomUUID().toString();
    first.call("/v2/simulations", new SecureHost.Simulation(firstId, "fake"));
    host.revoke(Pki.fingerprint(first.key.getPublic()));
    var stored =
        Wire.read(
            Files.readAllBytes(directory.resolve("private/state.json")), GrantStore.State.class);
    var cancelled =
        stored.jobs().values().stream()
            .filter(j -> j.id().equals(firstId))
            .findFirst()
            .orElseThrow();
    assertEquals("cancelled", cancelled.state());
    assertEquals(
        200,
        second.call("/v2/simulations", new SecureHost.Simulation(secondId, "fake")).statusCode());
    await(
        () ->
            host.store
                .job(Pki.fingerprint(second.key.getPublic()), secondId, clock.millis())
                .state()
                .equals("completed"));
    stored =
        Wire.read(
            Files.readAllBytes(directory.resolve("private/state.json")), GrantStore.State.class);
    var stillCancelled =
        stored.jobs().values().stream()
            .filter(j -> j.id().equals(firstId))
            .findFirst()
            .orElseThrow();
    assertEquals(cancelled, stillCancelled);
  }

  @Test
  void capturedTransportDoesNotContainInvitationOrTaskPlaintext() throws Exception {
    var client = pending();
    capturedPost(
        client.enrollment,
        URI.create(client.invitation.enroll()),
        "/enroll",
        new SecureHost.Enrollment(
            client.invitation.secret(), Base64.getEncoder().encodeToString(client.csr)),
        client.invitation.secret());
    host.approve(host.pendingFingerprint(), Set.of("observe", "simulate"));
    client.accept(client.enroll());
    String payload = "FAKE-PRIVATE-RESULT-" + UUID.randomUUID();
    capturedPost(
        client.control,
        host.endpoint(),
        "/v2/simulations",
        new SecureHost.Simulation(UUID.randomUUID().toString(), payload),
        payload);
  }

  private static void capturedPost(
      HttpClient client, URI endpoint, String path, Object body, String secret) throws Exception {
    var recorded = new ByteArrayOutputStream();
    try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      var relay =
          new FutureTask<Void>(
              () -> {
                try (var inbound = listener.accept();
                    var outbound = new Socket("127.0.0.1", endpoint.getPort())) {
                  var forward =
                      Thread.ofVirtual()
                          .start(
                              () -> {
                                try {
                                  byte[] bytes = new byte[4096];
                                  for (int n; (n = inbound.getInputStream().read(bytes)) != -1; ) {
                                    synchronized (recorded) {
                                      recorded.write(bytes, 0, n);
                                    }
                                    outbound.getOutputStream().write(bytes, 0, n);
                                    outbound.getOutputStream().flush();
                                  }
                                } catch (IOException closed) {
                                }
                              });
                  try {
                    byte[] bytes = new byte[4096];
                    for (int n; (n = outbound.getInputStream().read(bytes)) != -1; ) {
                      synchronized (recorded) {
                        recorded.write(bytes, 0, n);
                      }
                      inbound.getOutputStream().write(bytes, 0, n);
                      inbound.getOutputStream().flush();
                    }
                  } finally {
                    inbound.close();
                    forward.join(2000);
                  }
                }
                return null;
              });
      Thread.ofVirtual().start(relay);
      try (var raw = new Socket("127.0.0.1", listener.getLocalPort());
          var tls =
              (SSLSocket)
                  client
                      .sslContext()
                      .getSocketFactory()
                      .createSocket(raw, "127.0.0.1", endpoint.getPort(), true)) {
        tls.setSoTimeout(3000);
        var parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        byte[] bytes = Wire.bytes(body);
        tls.getOutputStream()
            .write(
                ("POST "
                        + path
                        + " HTTP/1.1\r\nHost: 127.0.0.1:"
                        + endpoint.getPort()
                        + "\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length
                        + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        tls.getOutputStream().write(bytes);
        tls.getOutputStream().flush();
        assertTrue(
            new String(tls.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .startsWith("HTTP/1.1 200"));
      }
      relay.get(4, TimeUnit.SECONDS);
    }
    String captured = recorded.toString(StandardCharsets.ISO_8859_1);
    assertTrue(captured.length() > 200);
    assertFalse(captured.contains(secret));
    assertFalse(captured.contains(new String(Wire.bytes(body), StandardCharsets.UTF_8)));
  }
}
