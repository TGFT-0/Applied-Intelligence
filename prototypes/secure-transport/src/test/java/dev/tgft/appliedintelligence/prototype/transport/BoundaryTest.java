package dev.tgft.appliedintelligence.prototype.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;
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
}
