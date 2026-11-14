package dev.tgft.appliedintelligence.prototype.transport;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.eclipse.jetty.http.*;
import org.eclipse.jetty.io.*;
import org.eclipse.jetty.io.ssl.*;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.*;
import org.eclipse.jetty.util.ssl.*;
import org.eclipse.jetty.util.thread.*;

/** Test-only loopback listener. HTTP persistence is intentionally disabled in this prototype. */
final class Boundary implements AutoCloseable {
  record Limits(long handshakeMillis, long headerMillis, long bodyMillis) {
    Limits {
      if (handshakeMillis < 100
          || handshakeMillis > 5000
          || headerMillis < 100
          || headerMillis > 3000
          || bodyMillis < 100
          || bodyMillis > 5000) throw new IllegalArgumentException();
    }

    static Limits defaults() {
      return new Limits(5000, 3000, 5000);
    }
  }

  interface Route {
    boolean handle(Request request, Response response, Callback callback) throws Exception;
  }

  final Server server;
  final ServerConnector connector;
  final URI endpoint;
  private final ScheduledExecutorService timer;
  private final Map<SSLEngine, ScheduledFuture<?>> handshakes = new ConcurrentHashMap<>();
  private final Map<EndPoint, ScheduledFuture<?>> headers = new ConcurrentHashMap<>();
  private final Set<SSLEngine> slots = ConcurrentHashMap.newKeySet();
  private final Semaphore admission = new Semaphore(4);

  Boundary(Pki pki, Pki.Identity identity, boolean clientAuth, Limits limits, Route route)
      throws Exception {
    limitsBodyMillis = limits.bodyMillis;
    timer =
        Executors.newScheduledThreadPool(
            2,
            r -> {
              var t = new Thread(r, "prototype-deadline");
              t.setDaemon(true);
              return t;
            });
    var pool = new QueuedThreadPool(16, 4);
    pool.setName("prototype-http");
    server = new Server(pool);
    var http = new HttpConfiguration();
    http.setPersistentConnectionsEnabled(false);
    http.setRequestHeaderSize(8192);
    http.setResponseHeaderSize(8192);
    http.setSendServerVersion(false);
    http.setSendXPoweredBy(false);
    http.addCustomizer(new SecureRequestCustomizer());
    var tls = new SslContextFactory.Server();
    tls.setKeyStore(Pki.keys(identity));
    tls.setKeyStorePassword("");
    tls.setTrustStore(Pki.trust(pki.ca.chain[0]));
    tls.setIncludeProtocols("TLSv1.3");
    tls.setNeedClientAuth(clientAuth);
    var ssl = new SslConnectionFactory(tls, "http/1.1");
    ssl.addEventListener(
        new Connection.Listener() {
          public void onOpened(Connection connection) {
            var c = (SslConnection) connection;
            var e = c.getSSLEngine();
            if (!admission.tryAcquire()) {
              connection.close();
              return;
            }
            slots.add(e);
            handshakes.put(
                e,
                timer.schedule(connection::close, limits.handshakeMillis, TimeUnit.MILLISECONDS));
          }

          public void onClosed(Connection connection) {
            finish(((SslConnection) connection).getSSLEngine());
          }
        });
    ssl.addBean(
        new SslHandshakeListener() {
          public void handshakeSucceeded(Event e) {
            finish(e.getSSLEngine());
            var end = e.getEndPoint();
            headers.put(
                end,
                timer.schedule(
                    () -> {
                      end.close();
                    },
                    limits.headerMillis,
                    TimeUnit.MILLISECONDS));
          }

          public void handshakeFailed(Event e, Throwable failure) {
            finish(e.getSSLEngine());
          }
        });
    var connection = new HttpConnectionFactory(http);
    connection.addEventListener(
        new Connection.Listener() {
          public void onClosed(Connection c) {
            var f = headers.remove(c.getEndPoint());
            if (f != null) f.cancel(false);
          }
        });
    connector = new ServerConnector(server, 1, 1, ssl, connection);
    connector.setHost("127.0.0.1");
    connector.setPort(0);
    connector.setIdleTimeout(10000);
    connector.setAcceptQueueSize(8);
    server.addConnector(connector);
    server.addBean(new NetworkConnectionLimit(32, connector));
    server.setHandler(
        new Handler.Abstract() {
          public boolean handle(Request request, Response response, Callback callback) {
            var deadline =
                headers.remove(request.getConnectionMetaData().getConnection().getEndPoint());
            if (deadline != null) deadline.cancel(false);
            try {
              String host = request.getHeaders().get("Host");
              if (!("127.0.0.1:" + connector.getLocalPort()).equals(host))
                throw new Wire.Fault(400, "invalid_host");
              for (var field : request.getHeaders()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (name.equals("origin")
                    || name.startsWith("sec-fetch-")
                    || name.equals("cookie")
                    || name.equals("authorization"))
                  throw new Wire.Fault(403, "browser_or_legacy_credentials_refused");
              }
              if (request.getHttpURI().getQuery() != null)
                throw new Wire.Fault(400, "query_refused");
              return route.handle(request, response, callback);
            } catch (Wire.Fault e) {
              reply(response, callback, e.status, new Wire.Error(e.code));
            } catch (Exception e) {
              reply(response, callback, 400, new Wire.Error("invalid_request"));
            }
            return true;
          }
        });
    try {
      server.start();
      endpoint = URI.create("https://127.0.0.1:" + connector.getLocalPort());
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  private void finish(SSLEngine engine) {
    var f = handshakes.remove(engine);
    if (f != null) f.cancel(false);
    if (slots.remove(engine)) admission.release();
  }

  byte[] body(Request request, int limit) throws IOException {
    if (!request.getMethod().equals("POST")) throw new Wire.Fault(405, "method_refused");
    String content = request.getHeaders().get("Content-Type");
    if (content == null || !content.equalsIgnoreCase("application/json"))
      throw new Wire.Fault(415, "json_required");
    if (request.getHeaders().contains("Content-Encoding"))
      throw new Wire.Fault(415, "compression_refused");
    if (request.getLength() > limit) throw new Wire.Fault(413, "body_too_large");
    var close =
        timer.schedule(
            request.getConnectionMetaData().getConnection()::close,
            limitsBodyMillis,
            TimeUnit.MILLISECONDS);
    try (var in = Content.Source.asInputStream(request)) {
      byte[] bytes = in.readNBytes(limit + 1);
      if (bytes.length > limit) throw new Wire.Fault(413, "body_too_large");
      return bytes;
    } finally {
      close.cancel(false);
    }
  }

  private final long limitsBodyMillis;

  static Boundary open(
      Pki pki, Pki.Identity identity, boolean clientAuth, Limits limits, Route route)
      throws Exception {
    return new Boundary(pki, identity, clientAuth, limits, route);
  }

  static void reply(Response response, Callback callback, int status, Object body) {
    try {
      byte[] bytes = Wire.bytes(body);
      response.setStatus(status);
      response.getHeaders().put("Content-Type", "application/json");
      response.getHeaders().put("Cache-Control", "no-store");
      response.getHeaders().put("Connection", "close");
      response.write(true, ByteBuffer.wrap(bytes), callback);
    } catch (IOException e) {
      callback.failed(new IOException("response_failed"));
    }
  }

  @Override
  public void close() throws Exception {
    try {
      server.stop();
      server.destroy();
    } finally {
      timer.shutdownNow();
    }
  }
}
