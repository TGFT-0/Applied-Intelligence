package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/** One task, one stdio app-server connection. JSON-RPC replies resume its original turn. */
final class CodexAppServer implements AgentSession {
  private final BufferedWriter writer;
  private final BufferedReader reader;
  private final Listener listener;

  private final Map<String, CompletableFuture<Steering>> steering = new ConcurrentHashMap<>();
  private volatile String thread = "", turn = "";
  private volatile boolean live;
  private boolean failed;
  private String result;

  CodexAppServer(Process process, Listener listener) {
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
    value.addProperty("id", id);
    value.addProperty("method", method);
    value.add("params", params);
    send(value);
  }

  public void answer(JsonElement id, JsonObject result) throws IOException {
    var value = new JsonObject();
    value.add("id", id);
    value.add("result", result);
    send(value);
  }

  public CompletableFuture<Steering> steer(String requestId, String expectedTurn, String text) {
    var future = new CompletableFuture<Steering>();
    String rpcId = "steer-" + requestId;
    if (!live || !turn.equals(expectedTurn)) {
      future.complete(new Steering("rejected", "steer_inactive"));
      return future;
    }
    steering.put(rpcId, future);
    var params = new JsonObject();
    params.addProperty("threadId", thread);
    params.addProperty("expectedTurnId", expectedTurn);
    params.addProperty("clientUserMessageId", requestId);
    var input = new JsonArray();
    var item = new JsonObject();
    item.addProperty("type", "text");
    item.addProperty("text", text);
    input.add(item);
    params.add("input", input);
    var frame = new JsonObject();
    frame.addProperty("id", rpcId);
    frame.addProperty("method", "turn/steer");
    frame.add("params", params);
    // Never hold the world's job lock while a child process has stopped reading stdin.
    Thread.ofVirtual()
        .name("ai-steer-send")
        .start(
            () -> {
              if (future.isDone()) return;
              try {
                send(frame);
              } catch (IOException e) {
                steering.remove(rpcId);
                future.complete(new Steering("unknown", "steer_unknown"));
              }
            });
    return future;
  }

  private void closeSteering() {
    live = false;
    steering.values().forEach(f -> f.complete(new Steering("unknown", "steer_unknown")));
    steering.clear();
  }

  private void reject(JsonElement id) throws IOException {
    var value = new JsonObject();
    value.add("id", id);
    var error = new JsonObject();
    error.addProperty("code", -32601);
    error.addProperty(
        "message", "This client supports public questions only; permissions remain read-only.");
    value.add("error", error);
    send(value);
  }

  private static JsonObject object(String raw) {
    return JsonParser.parseString(raw).getAsJsonObject();
  }

  private void event(String type, String key, JsonElement value) {
    var e = new JsonObject();
    e.addProperty("type", type);
    if (key != null) e.add(key, value);
    listener.event(e);
  }

  public Result run(String cwd, String prompt) throws IOException {
    try {
      return read(cwd, prompt);
    } finally {
      closeSteering();
    }
  }

  private Result read(String cwd, String prompt) throws IOException {
    request(
        1,
        "initialize",
        object(
            "{\"clientInfo\":{\"name\":\"applied_intelligence\",\"version\":\"0.11.0\"},\"capabilities\":{\"experimentalApi\":true}}"));
    String line;
    while ((line = LocalCodex.boundedLine(reader)) != null) {
      JsonObject value;
      try {
        value = object(line);
      } catch (RuntimeException e) {
        continue;
      }
      if (!value.has("method")) {
        if (value.has("id")) {
          var pending = steering.remove(value.get("id").getAsString());
          if (pending != null) {
            boolean accepted =
                value.has("result")
                    && value.get("result").isJsonObject()
                    && value.getAsJsonObject("result").has("turnId")
                    && turn.equals(value.getAsJsonObject("result").get("turnId").getAsString());
            pending.complete(
                new Steering(accepted ? "accepted" : "rejected", accepted ? "" : "steer_rejected"));
            continue;
          }
        }
        if (value.has("error")) return new Result(false, "");
        if (!value.has("id") || !value.get("id").isJsonPrimitive()) continue;
        String id = value.get("id").getAsString();
        if (id.equals("1")) {
          var initialized = new JsonObject();
          initialized.addProperty("method", "initialized");
          send(initialized);
          var params = new JsonObject();
          params.addProperty("cwd", cwd);
          params.addProperty("sandbox", "read-only");
          params.addProperty("approvalPolicy", "never");
          params.addProperty("ephemeral", true);
          request(2, "thread/start", params);
        } else if (id.equals("2")) {
          thread =
              value.getAsJsonObject("result").getAsJsonObject("thread").get("id").getAsString();
          listener.session(thread);
          event("thread.started", null, null);
          var params = new JsonObject();
          params.addProperty("threadId", thread);
          var input = new JsonArray();
          var text = new JsonObject();
          text.addProperty("type", "text");
          text.addProperty("text", prompt);
          input.add(text);
          params.add("input", input);
          request(3, "turn/start", params);
        } else if (id.equals("3")) {
          turn = value.getAsJsonObject("result").getAsJsonObject("turn").get("id").getAsString();
          live = true;
          listener.turn(turn);
          event("turn.started", null, null);
        }
        continue;
      }
      String method = value.get("method").getAsString();
      var params =
          value.has("params") && value.get("params").isJsonObject()
              ? value.getAsJsonObject("params")
              : new JsonObject();
      if (params.has("threadId") && !thread.equals(params.get("threadId").getAsString())) {
        if (value.has("id")) reject(value.get("id"));
        continue;
      }
      if (value.has("id")) {
        if (method.equals("item/tool/requestUserInput")) listener.question(value.get("id"), params);
        else reject(value.get("id"));
        continue;
      }
      if (method.equals("turn/started")) {
        turn = params.getAsJsonObject("turn").get("id").getAsString();
        live = true;
        listener.turn(turn);
      } else if (method.equals("serverRequest/resolved")) {
        listener.resolved(params.get("requestId"));
      } else if (method.equals("turn/plan/updated")) {
        var items = new JsonArray();
        for (var element : params.getAsJsonArray("plan")) {
          var p = element.getAsJsonObject();
          var item = new JsonObject();
          item.add("step", p.get("step"));
          String status = p.get("status").getAsString();
          item.addProperty("status", status.equals("inProgress") ? "in_progress" : status);
          items.add(item);
        }
        var item = new JsonObject();
        item.addProperty("type", "plan");
        item.add("steps", items);
        event("item.updated", "item", item);
      } else if (method.equals("thread/tokenUsage/updated")) {
        var usage = params.getAsJsonObject("tokenUsage");
        var total = usage.getAsJsonObject("total");
        var normalized = new JsonObject();
        for (String[] pair :
            new String[][] {
              {"inputTokens", "input_tokens"},
              {"outputTokens", "output_tokens"},
              {"cachedInputTokens", "cached_input_tokens"}
            }) if (total.has(pair[0])) normalized.add(pair[1], total.get(pair[0]));
        var info = new JsonObject();
        info.add("total_token_usage", normalized);
        if (usage.has("modelContextWindow"))
          info.add("model_context_window", usage.get("modelContextWindow"));
        event("token_count", "info", info);
      } else if (method.equals("item/completed") || method.equals("item/started")) {
        var item = params.getAsJsonObject("item");
        String type = item.get("type").getAsString();
        if (method.equals("item/completed")) {
          if (type.equals("imageView") && item.has("path"))
            listener.image(item.get("path").getAsString(), null);
          if (type.equals("imageGeneration")) {
            if (item.has("savedPath") && !item.get("savedPath").isJsonNull())
              listener.image(item.get("savedPath").getAsString(), null);
            else if (item.has("result") && !item.get("result").isJsonNull())
              listener.image(null, item.get("result").getAsString());
          }
          if (type.equals("mcpToolCall")
              && item.has("result")
              && item.get("result").isJsonObject()) {
            var content = item.getAsJsonObject("result").getAsJsonArray("content");
            if (content != null)
              for (var block : content) {
                if (!block.isJsonObject()) continue;
                var image = block.getAsJsonObject();
                if (image.has("type")
                    && image.get("type").getAsString().equals("image")
                    && image.has("data")) listener.image(null, image.get("data").getAsString());
              }
          }
        }
        if (type.equals("agentMessage") && method.equals("item/completed")) {
          if (!item.has("phase")
              || item.get("phase").isJsonNull()
              || item.get("phase").getAsString().equals("final_answer")) {
            String text = item.get("text").getAsString();
            var matcher =
                java.util.regex.Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(text);
            var cleaned = new StringBuffer();
            while (matcher.find()) {
              String path = matcher.group(2).trim();
              if (!path.contains("://") && !path.startsWith("data:")) listener.image(path, null);
              matcher.appendReplacement(
                  cleaned, java.util.regex.Matcher.quoteReplacement("[" + matcher.group(1) + "]"));
            }
            matcher.appendTail(cleaned);
            result = cleaned.substring(0, Math.min(16000, cleaned.length()));
          }
        } else if (Set.of("commandExecution", "webSearch", "mcpToolCall", "fileChange")
            .contains(type)) {
          var safe = new JsonObject();
          safe.add("id", item.get("id"));
          safe.addProperty(
              "type",
              switch (type) {
                case "commandExecution" -> "command_execution";
                case "webSearch" -> "web_search";
                case "mcpToolCall" -> "mcp_tool_call";
                default -> "file_change";
              });
          if (item.has("status")) safe.add("status", item.get("status"));
          event(method.equals("item/started") ? "item.started" : "item.completed", "item", safe);
        }
      } else if (method.equals("error")) {
        failed = true;
        event("error", null, null);
      } else if (method.equals("turn/completed")) {
        live = false;
        boolean success =
            params.getAsJsonObject("turn").get("status").getAsString().equals("completed")
                && !failed
                && result != null;
        event(success ? "turn.completed" : "turn.failed", null, null);
        return new Result(success, success ? result : "");
      }
    }
    return new Result(false, "");
  }
}
