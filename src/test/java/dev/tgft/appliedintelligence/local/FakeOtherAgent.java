package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Official protocol shapes over real stdio or authenticated loopback HTTP. No model traffic. */
public final class FakeOtherAgent {
  private static final Path ROOT = Path.of(System.getProperty("ai.test.root"));
  private static final String BACKEND = System.getProperty("ai.test.backend");

  private static JsonObject obj(String raw) {
    return JsonParser.parseString(raw).getAsJsonObject();
  }

  private static void send(String raw) {
    System.out.println(raw);
    System.out.flush();
  }

  public static void main(String[] args) throws Exception {
    if (args[0].equals("--version")) {
      send(BACKEND.equals("opencode") ? "2.1.0" : BACKEND + " 1.0");
      return;
    }
    if (System.getenv().keySet().stream().anyMatch(k -> k.startsWith("AI_BRIDGE_"))) System.exit(9);
    Files.writeString(ROOT.resolve("launch.json"), new Gson().toJson(List.of(args)));
    if (BACKEND.equals("pi")) pi(args);
    else if (BACKEND.equals("deepseek")) acp(args);
    else http(args);
  }

  private static void pi(String[] args) throws Exception {
    var flags = List.of(args);
    if (!flags.containsAll(
        List.of(
            "--mode",
            "rpc",
            "--no-session",
            "--tools",
            "read,grep,find,ls",
            "--no-extensions",
            "--no-mcp"))) System.exit(10);
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      var frame = obj(line);
      String type = frame.get("type").getAsString();
      String id = frame.has("id") ? frame.get("id").getAsString() : "";
      if (type.equals("get_state"))
        send(
            "{\"type\":\"response\",\"id\":\"state\",\"success\":true,\"data\":{\"model\":{\"contextWindow\":131072}}}");
      if (type.equals("prompt")) {
        send(
            "{\"type\":\"response\",\"id\":\"prompt\",\"success\":true,\"data\":{\"disposition\":\"started\"}}");
        send(
            "{\"type\":\"tool_execution_start\",\"toolCallId\":\"read-1\",\"toolName\":\"read\",\"args\":{\"path\":\"PRIVATE_PATH\"}}");
        send(
            "{\"type\":\"tool_execution_end\",\"toolCallId\":\"read-1\",\"isError\":false,\"result\":{\"content\":[]}}");
        Files.writeString(ROOT.resolve("running"), "yes");
        if (!frame.get("message").getAsString().contains("HOLD")) {
          piEnd();
          return;
        }
      }
      if (type.equals("steer")) {
        Files.writeString(ROOT.resolve("steer"), frame.get("message").getAsString());
        var reply =
            obj("{\"type\":\"response\",\"success\":true,\"data\":{\"disposition\":\"queued\"}}");
        reply.addProperty("id", id);
        reply.addProperty("command", "steer");
        send(reply.toString());
        if (frame.get("message").getAsString().contains("finish")) {
          piEnd();
          return;
        }
      }
    }
  }

  private static void piEnd() {
    String event =
        "{\"type\":\"message_end\",\"message\":{\"id\":\"final\",\"role\":\"assistant\",\"stopReason\":\"stop\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"PRIVATE_REASONING\"},{\"type\":\"text\",\"text\":\"Pi"
            + " result"
            + " 中文\"}],\"usage\":{\"input\":10,\"cacheRead\":20,\"cacheWrite\":2,\"output\":5}}}";
    var frame = obj(event);
    var content = frame.getAsJsonObject("message").getAsJsonArray("content");
    if (Files.exists(ROOT.resolve("workspace/figure.png")))
      content.get(1).getAsJsonObject().addProperty("text", "Pi result 中文 ![结果](figure.png)");
    event = frame.toString();
    send(event);
    send(event); // repeated authoritative message must not double count
    send("{\"type\":\"agent_end\",\"messages\":[]}");
    send("{\"type\":\"agent_settled\",\"aborted\":false}");
  }

  private static void acp(String[] args) throws Exception {
    var flags = List.of(args);
    if (!flags.getFirst().equals("acp") || !flags.contains("--patch")) System.exit(10);
    String path = flags.get(flags.indexOf("--patch") + 1);
    String overlay = Files.readString(Path.of(path));
    Files.writeString(ROOT.resolve("overlay.json"), overlay);
    if (!overlay.contains("read-only")
        || !overlay.contains("never")
        || !"read-only".equals(System.getenv("DSH_PERMISSION_MODE"))) System.exit(10);
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      var frame = obj(line);
      String method = SessionEvents.string(frame, "method");
      if (method.equals("initialize"))
        send(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}");
      if (method.equals("session/new")) {
        if (!frame.getAsJsonObject("params").getAsJsonArray("mcpServers").isEmpty())
          System.exit(10);
        send("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"sessionId\":\"dsh-fixture\"}}");
      }
      if (method.equals("session/prompt")) {
        send(
            "{\"jsonrpc\":\"2.0\",\"id\":\"deny-1\",\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"dsh-fixture\",\"options\":[{\"optionId\":\"allow\",\"kind\":\"allow_once\"}]}}");
        var denied = obj(Objects.requireNonNull(reader.readLine()));
        if (!denied
            .getAsJsonObject("result")
            .getAsJsonObject("outcome")
            .get("outcome")
            .getAsString()
            .equals("cancelled")) System.exit(10);
        acpEvent(
            "{\"sessionUpdate\":\"agent_thought_chunk\",\"content\":{\"type\":\"text\",\"text\":\"PRIVATE_REASONING\"}}");
        acpEvent(
            "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call-1\",\"status\":\"in_progress\",\"rawInput\":\"PRIVATE_ARGS\"}");
        acpEvent(
            "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call-1\",\"status\":\"completed\"}");
        acpEvent("{\"sessionUpdate\":\"usage_update\",\"used\":999,\"size\":128000}");
        acpEvent(
            "{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"DeepSeek"
                + " result 中文\"}}");
        send("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}");
        return;
      }
    }
  }

  private static void acpEvent(String update) {
    var data = obj(update);
    if (SessionEvents.string(data, "sessionUpdate").equals("agent_message_chunk")
        && Files.exists(ROOT.resolve("workspace/figure.png"))) {
      data.getAsJsonObject("content").addProperty("text", "DeepSeek result 中文 ![结果](figure.png)");
      update = data.toString();
    }
    send(
        "{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"dsh-fixture\",\"update\":"
            + update
            + "}}");
  }

  private static volatile OutputStream sse;

  private static synchronized void event(String type, String json) throws IOException {
    if (sse == null) throw new IOException("No subscription before work");
    var frame = new JsonObject();
    frame.addProperty("id", UUID.randomUUID().toString());
    frame.addProperty("type", type);
    frame.add("data", obj(json));
    sse.write(("data: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
    sse.flush();
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    if (Files.exists(ROOT.resolve("workspace/figure.png")) && body.contains("OpenCode result 中文"))
      body = body.replace("OpenCode result 中文", "OpenCode result 中文 ![结果](figure.png)");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void ocEnd() throws IOException {
    event(
        "session.step.ended",
        "{\"sessionID\":\"oc-fixture\",\"tokens\":{\"input\":20,\"output\":7,\"reasoning\":3,\"cache\":{\"read\":10,\"write\":5}}}");
    event("session.execution.succeeded", "{\"sessionID\":\"oc-fixture\"}");
  }

  private static void http(String[] args) throws Exception {
    if (!List.of(args)
        .containsAll(List.of("serve", "--stdio", "--hostname", "127.0.0.1", "--port", "0")))
      System.exit(10);
    if (Files.exists(ROOT.resolve("bad-url"))) {
      send("{\"url\":\"http://example.com:80\"}");
      Thread.sleep(60000);
      return;
    }
    String password = System.getenv("OPENCODE_PASSWORD");
    if (password == null) System.exit(10);
    String authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8));
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext(
        "/api/",
        exchange -> {
          try {
            if (!authorization.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
              exchange.sendResponseHeaders(401, -1);
              exchange.close();
              return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/event")) {
              exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
              exchange.sendResponseHeaders(200, 0);
              sse = exchange.getResponseBody();
              event("server.connected", "{}");
              new CountDownLatch(1).await();
              return;
            }
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/api/session")) {
              Files.writeString(ROOT.resolve("create.json"), body);
              var create = obj(body);
              var permissions = create.getAsJsonArray("permissions");
              if (!permissions.get(0).getAsJsonObject().get("effect").getAsString().equals("deny"))
                throw new IOException();
              if (sse == null) throw new IOException("late subscription");
              respond(exchange, "{\"data\":{\"id\":\"oc-fixture\"}}");
              return;
            }
            if (path.endsWith("/prompt")) {
              var prompt = obj(body);
              Files.writeString(ROOT.resolve("last-prompt.json"), body);
              respond(exchange, "{\"data\":{\"id\":\"inbox-fixture\"}}");
              if (prompt.get("delivery").getAsString().equals("steer")) {
                Files.writeString(ROOT.resolve("steer"), prompt.get("text").getAsString());
                // A previous execution may end before the admitted steering message is delivered.
                event("session.execution.succeeded", "{\"sessionID\":\"oc-fixture\"}");
                Thread.sleep(100);
                var delivery = new JsonObject();
                delivery.addProperty("sessionID", "oc-fixture");
                delivery.add("inboxID", prompt.get("id"));
                event("session.inbox.delivered", delivery.toString());
                event("session.execution.started", "{\"sessionID\":\"oc-fixture\"}");
                ocEnd();
                return;
              }
              event("session.execution.started", "{\"sessionID\":\"oc-fixture\"}");
              event(
                  "session.tool.input.started",
                  "{\"sessionID\":\"oc-fixture\",\"id\":\"read-1\",\"input\":\"PRIVATE_ARGS\"}");
              event(
                  "session.tool.success",
                  "{\"sessionID\":\"oc-fixture\",\"id\":\"read-1\",\"content\":[]}");
              Files.writeString(ROOT.resolve("running"), "yes");
              if (prompt.get("text").getAsString().contains("ASK"))
                event(
                    "form.created",
                    "{\"form\":{\"id\":\"frm_fixture\",\"sessionID\":\"oc-fixture\",\"title\":\"成果格式\",\"fields\":[{\"key\":\"format\",\"type\":\"string\",\"title\":\"请选择格式\",\"options\":[{\"value\":\"brief\",\"label\":\"简要\"}]}]}}");
              else if (!prompt.get("text").getAsString().contains("HOLD")) ocEnd();
              return;
            }
            if (path.endsWith("/reply")) {
              Files.writeString(ROOT.resolve("answers.json"), body);
              respond(exchange, "{}");
              event("form.replied", "{\"sessionID\":\"oc-fixture\",\"id\":\"frm_fixture\"}");
              ocEnd();
              return;
            }
            if (path.endsWith("/message")) {
              respond(
                  exchange,
                  "{\"data\":[{\"type\":\"assistant\",\"content\":[{\"type\":\"reasoning\",\"text\":\"PRIVATE_REASONING\"},{\"type\":\"text\",\"text\":\"OpenCode"
                      + " result 中文\"}]}]}");
              return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          } catch (Exception e) {
            exchange.close();
          }
        });
    server.start();
    send("{\"url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "\"}");
    new CountDownLatch(1).await();
  }
}
