package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/** ACP v1 stdio for DeepSeek Harness. No filesystem, terminal, or permission grants. */
final class AcpSession implements AgentSession {
  private final BufferedWriter writer;
  private final BufferedReader reader;
  private final Listener listener;
  private String session = "";
  private final StringBuilder text = new StringBuilder();

  AcpSession(Process process, Listener listener) {
    writer =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    this.listener = listener;
  }

  private synchronized void send(JsonObject value) throws IOException {
    writer.write(value.toString());
    writer.newLine();
    writer.flush();
  }

  private void request(int id, String method, JsonObject params) throws IOException {
    var value = new JsonObject();
    value.addProperty("jsonrpc", "2.0");
    value.addProperty("id", id);
    value.addProperty("method", method);
    value.add("params", params);
    send(value);
  }

  public void answer(JsonElement id, JsonObject result) throws IOException {
    throw new IOException("ACP backend has no question interface");
  }

  public CompletableFuture<Steering> steer(String id, String turn, String value) {
    return CompletableFuture.completedFuture(new Steering("rejected", "provider_no_steering"));
  }

  public Result run(String cwd, String prompt) throws IOException {
    var init = new JsonObject();
    init.addProperty("protocolVersion", 1);
    init.add("clientCapabilities", new JsonObject());
    var client = new JsonObject();
    client.addProperty("name", "applied-intelligence");
    client.addProperty("version", "0.11.1");
    init.add("clientInfo", client);
    request(1, "initialize", init);
    String line;
    while ((line = LocalCodex.boundedLine(reader)) != null) {
      JsonObject frame;
      try {
        frame = JsonParser.parseString(line).getAsJsonObject();
      } catch (RuntimeException e) {
        continue;
      }
      String method = SessionEvents.string(frame, "method");
      if (!method.isEmpty()) {
        var params =
            frame.has("params") && frame.get("params").isJsonObject()
                ? frame.getAsJsonObject("params")
                : new JsonObject();
        if (frame.has("id")) {
          var response = new JsonObject();
          response.addProperty("jsonrpc", "2.0");
          response.add("id", frame.get("id"));
          if (method.equals("session/request_permission")) {
            var outcome = new JsonObject();
            outcome.addProperty("outcome", "cancelled");
            var result = new JsonObject();
            result.add("outcome", outcome);
            response.add("result", result);
          } else {
            var error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty(
                "message", "This client does not grant filesystem, terminal or tool access.");
            response.add("error", error);
          }
          send(response);
          continue;
        }
        if (!method.equals("session/update")
            || !session.equals(SessionEvents.string(params, "sessionId"))) continue;
        var update = params.getAsJsonObject("update");
        if (update == null) continue;
        switch (SessionEvents.string(update, "sessionUpdate")) {
          case "agent_message_chunk" -> {
            var block = update.getAsJsonObject("content");
            if (block != null) {
              if (SessionEvents.string(block, "type").equals("text")) {
                String value = SessionEvents.string(block, "text");
                text.append(value, 0, Math.min(value.length(), Math.max(0, 16000 - text.length())));
              } else if (SessionEvents.string(block, "type").equals("image"))
                listener.image(null, SessionEvents.string(block, "data"));
            }
          }
          case "tool_call", "tool_call_update" -> {
            String status = SessionEvents.string(update, "status");
            boolean end = status.equals("completed") || status.equals("failed");
            SessionEvents.action(
                listener, SessionEvents.string(update, "toolCallId"), end, status.equals("failed"));
            var contents = update.getAsJsonArray("content");
            if (contents != null)
              for (var e : contents)
                if (e.isJsonObject()) {
                  var content = e.getAsJsonObject().getAsJsonObject("content");
                  if (content != null && SessionEvents.string(content, "type").equals("image"))
                    listener.image(null, SessionEvents.string(content, "data"));
                }
          }
          // ACP's 'used' is context occupancy, not cumulative billable input/output usage.
          case "usage_update" -> SessionEvents.window(listener, update, "size");
          case "plan" -> {
            var event = new JsonObject();
            event.addProperty("type", "item.updated");
            var item = new JsonObject();
            item.addProperty("type", "plan");
            var steps = new JsonArray();
            var entries = update.getAsJsonArray("entries");
            if (entries != null)
              for (var e : entries) {
                var p = e.getAsJsonObject();
                var step = new JsonObject();
                step.addProperty("step", SessionEvents.string(p, "content"));
                step.addProperty("status", SessionEvents.string(p, "status"));
                steps.add(step);
              }
            item.add("steps", steps);
            event.add("item", item);
            listener.event(event);
          }
          default -> {} // Never expose agent_thought_chunk or rawInput/rawOutput.
        }
      } else {
        if (frame.has("error")) return new Result(false, "");
        String id = SessionEvents.string(frame, "id");
        var result = frame.getAsJsonObject("result");
        if (result == null) continue;
        if (id.equals("1")) {
          if (SessionEvents.count(result, "protocolVersion") != 1) return new Result(false, "");
          var params = new JsonObject();
          params.addProperty("cwd", cwd);
          params.add("mcpServers", new JsonArray());
          request(2, "session/new", params);
        } else if (id.equals("2")) {
          session = SessionEvents.string(result, "sessionId");
          if (session.isBlank()) return new Result(false, "");
          listener.session(session);
          listener.turn(session);
          var params = new JsonObject();
          params.addProperty("sessionId", session);
          var input = new JsonArray();
          var block = new JsonObject();
          block.addProperty("type", "text");
          block.addProperty("text", prompt);
          input.add(block);
          params.add("prompt", input);
          request(3, "session/prompt", params);
        } else if (id.equals("3"))
          return new Result(
              SessionEvents.string(result, "stopReason").equals("end_turn") && !text.isEmpty(),
              SessionEvents.markdown(listener, text.toString()));
      }
    }
    return new Result(false, "");
  }
}
