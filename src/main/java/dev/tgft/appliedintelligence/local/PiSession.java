package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Official Pi JSONL RPC. Only public final text and bounded tool metadata leave this adapter. */
final class PiSession implements AgentSession {
  private final BufferedWriter writer;
  private final BufferedReader reader;
  private final Listener listener;
  private final Map<String, CompletableFuture<Steering>> pending = new ConcurrentHashMap<>();
  private final Map<String, JsonObject> usageByMessage = new LinkedHashMap<>();
  private final String turn = UUID.randomUUID().toString();
  private volatile boolean live;
  private String result = "";
  private boolean failed;

  PiSession(Process process, Listener listener) {
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

  private void command(String id, String type, String message) throws IOException {
    var value = new JsonObject();
    value.addProperty("id", id);
    value.addProperty("type", type);
    if (message != null) value.addProperty("message", message);
    send(value);
  }

  public CompletableFuture<Steering> steer(String id, String expectedTurn, String text) {
    if (!live || !turn.equals(expectedTurn))
      return CompletableFuture.completedFuture(new Steering("rejected", "steer_inactive"));
    var future = new CompletableFuture<Steering>();
    pending.put(id, future);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                command(id, "steer", text);
              } catch (IOException e) {
                pending.remove(id);
                future.complete(new Steering("unknown", "steer_unknown"));
              }
            });
    return future;
  }

  public void answer(JsonElement id, JsonObject answer) throws IOException {
    throw new IOException("Pi question extensions are disabled in read-only mode");
  }

  public Result run(String cwd, String prompt) throws IOException {
    try {
      command("state", "get_state", null);
      String line;
      while ((line = LocalCodex.boundedLine(reader)) != null) {
        JsonObject frame;
        try {
          frame = JsonParser.parseString(line).getAsJsonObject();
        } catch (RuntimeException e) {
          continue;
        }
        String type = SessionEvents.string(frame, "type");
        if (type.equals("response")) {
          String id = SessionEvents.string(frame, "id");
          boolean success = frame.has("success") && frame.get("success").getAsBoolean();
          var future = pending.remove(id);
          if (future != null) {
            future.complete(
                new Steering(success ? "accepted" : "rejected", success ? "" : "steer_rejected"));
            continue;
          }
          if (id.equals("state")) {
            if (!success) return new Result(false, "");
            var state = frame.getAsJsonObject("data");
            if (state == null || !state.has("model") || !state.get("model").isJsonObject())
              return new Result(false, "");
            SessionEvents.window(listener, state.getAsJsonObject("model"), "contextWindow");
            listener.session(turn);
            listener.turn(turn);
            live = true;
            command("prompt", "prompt", prompt);
          } else if (id.equals("prompt")
              && (!success
                  || frame.has("data")
                      && SessionEvents.string(frame.getAsJsonObject("data"), "disposition")
                          .equals("handled"))) return new Result(false, "");
        } else if (type.equals("message_end") && frame.has("message")) {
          var message = frame.getAsJsonObject("message");
          if (!SessionEvents.string(message, "role").equals("assistant")) continue;
          String stop = SessionEvents.string(message, "stopReason");
          failed = stop.equals("error") || stop.equals("aborted");
          result = SessionEvents.content(listener, message.getAsJsonArray("content"));
          if (message.has("usage")) {
            String key = SessionEvents.string(message, "id");
            if (key.isEmpty()) key = SessionEvents.string(message, "timestamp");
            usageByMessage.put(key, message.getAsJsonObject("usage"));
            long input = 0, output = 0, cache = 0;
            for (var usage : usageByMessage.values()) {
              long read = SessionEvents.count(usage, "cacheRead"),
                  write = SessionEvents.count(usage, "cacheWrite");
              input =
                  Math.addExact(
                      input,
                      Math.addExact(
                          SessionEvents.count(usage, "input"), Math.addExact(read, write)));
              cache = Math.addExact(cache, read);
              output = Math.addExact(output, SessionEvents.count(usage, "output"));
            }
            SessionEvents.usage(listener, input, output, cache);
          }
        } else if (type.equals("tool_execution_start") || type.equals("tool_execution_end")) {
          boolean end = type.endsWith("end");
          SessionEvents.action(
              listener,
              SessionEvents.string(frame, "toolCallId"),
              end,
              frame.has("isError") && frame.get("isError").getAsBoolean());
          if (end && frame.has("result") && frame.get("result").isJsonObject())
            SessionEvents.content(
                listener, frame.getAsJsonObject("result").getAsJsonArray("content"));
        } else if (type.equals("agent_settled")) {
          return new Result(
              !failed
                  && !(frame.has("aborted") && frame.get("aborted").getAsBoolean())
                  && !result.isBlank(),
              SessionEvents.markdown(listener, result));
        } else if (type.equals("extension_ui_request")) {
          // Do not strand an unexpected extension dialog. No extension is authorized to gain tools.
          var response = new JsonObject();
          response.addProperty("type", "extension_ui_response");
          response.add("id", frame.get("id"));
          response.addProperty("cancelled", true);
          send(response);
        }
      }
      return new Result(false, "");
    } finally {
      live = false;
      pending.values().forEach(f -> f.complete(new Steering("unknown", "steer_unknown")));
      pending.clear();
    }
  }
}
