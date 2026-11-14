package dev.tgft.appliedintelligence.prototype.transport;

import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.Callback;

/** No entry point, real tasks, CLI, filesystem endpoints, or non-loopback bindings. */
final class SecureHost implements AutoCloseable {
  record Invitation(String version, String enroll, String control, String ca, String secret) {
    @Override
    public String toString() {
      return "[redacted invitation]";
    }
  }

  record Enrollment(String secret, String csr) {
    @Override
    public String toString() {
      return "[redacted enrollment]";
    }
  }

  record EnrollmentReply(String state, String fingerprint, String certificate) {}

  record Simulation(String id, String payload) {}

  record Ping(String identity) {}

  record Empty() {}

  private final AtomicBoolean closed = new AtomicBoolean();
  final Pki pki;
  final GrantStore store;
  final Boundary control;
  final Clock clock;
  final Boundary.Limits limits;
  private final ScheduledExecutorService background =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            var t = new Thread(r, "prototype-owned-work");
            t.setDaemon(true);
            return t;
          });
  private final Map<String, Set<EndPoint>> connections = new ConcurrentHashMap<>();
  private final Map<String, Stream> streams = new ConcurrentHashMap<>();
  private final Set<String> started = ConcurrentHashMap.newKeySet();
  final AtomicInteger mockWorkerStarts = new AtomicInteger();
  private volatile EnrollmentWindow window;

  SecureHost(Path state, Pki pki, Clock clock, Boundary.Limits limits) throws Exception {
    this.pki = pki;
    this.clock = clock;
    this.limits = limits;
    store = new GrantStore(state, Pki.fingerprint(pki.ca.keys.getPublic()));
    try {
      control = Boundary.open(pki, pki.server, true, limits, this::route);
    } catch (Exception e) {
      store.close();
      background.shutdownNow();
      throw e;
    }
  }

  URI endpoint() {
    return control.endpoint;
  }

  private String device(Request request) {
    var tls = (EndPoint.SslSessionData) request.getAttribute(EndPoint.SslSessionData.ATTRIBUTE);
    if (tls == null || tls.peerCertificates() == null || tls.peerCertificates().length == 0)
      throw new Wire.Fault(403, "certificate_required");
    String device = Pki.fingerprint(tls.peerCertificates()[0].getPublicKey());
    synchronized (store) {
      store.require(device, "observe", clock.millis());
      var end = request.getConnectionMetaData().getConnection().getEndPoint();
      var set = connections.computeIfAbsent(device, k -> ConcurrentHashMap.newKeySet());
      synchronized (set) {
        if (set.size() >= 4 && !set.contains(end)) throw new Wire.Fault(429, "connection_limit");
        set.add(end);
      }
      Request.addCompletionListener(request, t -> set.remove(end));
      return device;
    }
  }

  private boolean route(Request request, Response response, Callback callback) throws Exception {
    String owner = device(request), path = request.getHttpURI().getPath();
    if (path.equals("/v2/ping") && request.getMethod().equals("GET")) {
      Boundary.reply(response, callback, 200, new Ping(Pki.fingerprint(pki.ca.keys.getPublic())));
      return true;
    }
    if (path.equals("/v2/events") && request.getMethod().equals("GET")) {
      var stream = new Stream(owner, request, response, callback);
      if (streams.putIfAbsent(owner, stream) != null) throw new Wire.Fault(429, "stream_limit");
      stream.send();
      return true;
    }
    if (path.equals("/v2/renew")) {
      Wire.read(control.body(request, 16), Empty.class);
      var tls = (EndPoint.SslSessionData) request.getAttribute(EndPoint.SslSessionData.ATTRIBUTE);
      synchronized (store) {
        var g = store.require(owner, "observe", clock.millis());
        var cert =
            pki.certificate(
                tls.peerCertificates()[0].getPublicKey(),
                true,
                Instant.now().minusSeconds(60),
                Instant.ofEpochMilli(g.until()),
                null);
        String encoded = Base64.getEncoder().encodeToString(cert.getEncoded());
        store.grant(new GrantStore.Grant(owner, encoded, g.scopes(), g.until(), false));
        Boundary.reply(response, callback, 200, new EnrollmentReply("renewed", owner, encoded));
      }
      return true;
    }
    if (path.equals("/v2/simulations")) {
      var input = Wire.read(control.body(request, 4096), Simulation.class);
      GrantStore.Job job = store.submit(owner, input.id, input.payload, clock.millis());
      if (job.state().equals("running") && started.add(owner + ":" + job.id()))
        background.schedule(
            () -> {
              mockWorkerStarts.incrementAndGet();
              tick(owner, job.id());
            },
            50,
            TimeUnit.MILLISECONDS);
      Boundary.reply(response, callback, 200, job);
      return true;
    }
    if (path.startsWith("/v2/simulations/") && request.getMethod().equals("GET")) {
      Boundary.reply(
          response,
          callback,
          200,
          store.job(owner, path.substring("/v2/simulations/".length()), clock.millis()));
      return true;
    }
    throw new Wire.Fault(404, "not_found");
  }

  private void tick(String device, String id) {
    if (closed.get()) return;
    try {
      if (store.tick(device, id, clock.millis()))
        background.schedule(() -> tick(device, id), 50, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      /* Mock job remains non-replayable if persistence fails. */
    }
  }

  synchronized Invitation openEnrollment(Duration ttl) throws Exception {
    if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(2)) > 0)
      throw new IllegalArgumentException();
    if (window != null) window.close();
    window = new EnrollmentWindow(ttl);
    return window.invitation();
  }

  synchronized String pendingFingerprint() {
    return window == null ? "" : window.pendingFingerprint;
  }

  synchronized void approve(String fingerprint, Set<String> scopes) throws Exception {
    if (window == null) throw new Wire.Fault(409, "no_enrollment");
    window.approve(fingerprint, scopes);
  }

  void revoke(String fingerprint) throws Exception {
    Set<EndPoint> ends;
    synchronized (store) {
      store.revoke(fingerprint);
      ends = connections.remove(fingerprint);
    }
    var s = streams.remove(fingerprint);
    if (s != null) s.close();
    if (ends != null) for (var e : ends) e.close();
  }

  final class EnrollmentWindow implements AutoCloseable {
    final Boundary boundary;
    final String secret, secretHash;
    final long expires;
    final ScheduledFuture<?> expiry;
    volatile String pendingFingerprint = "";
    String csrHash = "", certificate = "";
    final AtomicBoolean stopped = new AtomicBoolean();
    PublicKey key;
    boolean approved;
    long deliveredUntil;

    EnrollmentWindow(Duration ttl) throws Exception {
      secret = Wire.random();
      secretHash = Wire.hash(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      expires = clock.millis() + ttl.toMillis();
      boundary = Boundary.open(pki, pki.server, false, limits, this::handle);
      expiry =
          background.schedule(
              () -> {
                try {
                  close();
                } catch (Exception ignored) {
                }
              },
              ttl.toMillis(),
              TimeUnit.MILLISECONDS);
    }

    Invitation invitation() throws Exception {
      return new Invitation(
          "ai-remote-v2-prototype",
          boundary.endpoint.toString(),
          control.endpoint.toString(),
          Base64.getEncoder().encodeToString(pki.ca.chain[0].getEncoded()),
          secret);
    }

    synchronized boolean handle(Request request, Response response, Callback callback)
        throws Exception {
      if (!request.getHttpURI().getPath().equals("/enroll")) throw new Wire.Fault(404, "not_found");
      var enrollment = Wire.read(boundary.body(request, 16384), Enrollment.class);
      if (stopped.get()
          || clock.millis() >= expires
          || approved && clock.millis() >= deliveredUntil
          || enrollment.secret == null
          || enrollment.secret.length() > 100
          || !Wire.same(
              secretHash,
              Wire.hash(enrollment.secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        throw new Wire.Fault(403, "invalid_invitation");
      byte[] der = Base64.getDecoder().decode(enrollment.csr);
      PublicKey candidate = Pki.verifyCsr(der);
      String hash = Wire.hash(der);
      if (!csrHash.isEmpty() && !csrHash.equals(hash))
        throw new Wire.Fault(409, "different_pending_device");
      if (csrHash.isEmpty()) {
        csrHash = hash;
        key = candidate;
        pendingFingerprint = Pki.fingerprint(candidate);
      }
      Boundary.reply(
          response,
          callback,
          200,
          new EnrollmentReply(approved ? "approved" : "pending", pendingFingerprint, certificate));
      return true;
    }

    synchronized void approve(String expected, Set<String> scopes) throws Exception {
      if (stopped.get()
          || approved
          || clock.millis() >= expires
          || key == null
          || !expected.equals(pendingFingerprint)) throw new Wire.Fault(409, "approval_mismatch");
      long until = clock.millis() + Duration.ofDays(30).toMillis();
      var cert =
          pki.certificate(
              key, true, Instant.now().minusSeconds(60), Instant.ofEpochMilli(until), null);
      String encoded = Base64.getEncoder().encodeToString(cert.getEncoded());
      store.grant(
          new GrantStore.Grant(pendingFingerprint, encoded, Set.copyOf(scopes), until, false));
      certificate = encoded;
      approved = true;
      deliveredUntil = Math.min(expires, clock.millis() + 30000);
    }

    @Override
    public void close() throws Exception {
      if (stopped.compareAndSet(false, true)) {
        if (expiry != null) expiry.cancel(false);
        boundary.close();
      }
    }
  }

  private final class Stream {
    final String owner;
    final Request request;
    final Response response;
    final Callback callback;
    final AtomicBoolean finished = new AtomicBoolean();
    final AtomicReference<ScheduledFuture<?>> writeDeadline = new AtomicReference<>();
    int sequence;

    Stream(String owner, Request request, Response response, Callback callback) {
      this.owner = owner;
      this.request = request;
      this.response = response;
      this.callback = callback;
      response.getHeaders().put("Content-Type", "text/event-stream");
      response.getHeaders().put("Cache-Control", "no-store");
      response.getHeaders().put("Connection", "close");
    }

    void send() {
      if (finished.get()) return;
      try {
        synchronized (store) {
          store.require(owner, "observe", clock.millis());
          var bytes =
              ("id: " + (++sequence) + "\ndata: {\"type\":\"heartbeat\"}\n\n")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          writeDeadline.set(background.schedule(this::close, 10, TimeUnit.SECONDS));
          response.write(
              false,
              ByteBuffer.wrap(bytes),
              new Callback() {
                public void succeeded() {
                  cancelWriteDeadline();
                  if (!finished.get())
                    background.schedule(Stream.this::send, 100, TimeUnit.MILLISECONDS);
                }

                public void failed(Throwable t) {
                  close();
                }
              });
        }
      } catch (Exception e) {
        close();
      }
    }

    void cancelWriteDeadline() {
      var deadline = writeDeadline.getAndSet(null);
      if (deadline != null) deadline.cancel(false);
    }

    void close() {
      if (finished.compareAndSet(false, true)) {
        cancelWriteDeadline();
        streams.remove(owner, this);
        request.getConnectionMetaData().getConnection().close();
        callback.failed(new java.io.IOException("stream_closed"));
      }
    }
  }

  @Override
  public void close() throws Exception {
    if (!closed.compareAndSet(false, true)) return;
    synchronized (this) {
      if (window != null) window.close();
    }
    for (var stream : streams.values()) stream.close();
    background.shutdownNow();
    control.close();
    if (!background.awaitTermination(5, TimeUnit.SECONDS))
      throw new java.io.IOException("Mock worker did not stop");
    store.close();
  }
}
