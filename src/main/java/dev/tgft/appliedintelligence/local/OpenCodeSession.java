package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Official OpenCode 2 API, owned loopback server, authenticated SSE subscribed before admission.
 */
final class OpenCodeSession implements AgentSession {
  private final Process process;
  private final Listener listener;
  private final String authorization;
  private final HttpClient http =
      HttpClient.newBuilder()
          .proxy(ProxySelector.of(null))
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  private final BlockingQueue<JsonObject> events = new ArrayBlockingQueue<>(256);
  private final Map<String, JsonObject> forms = new ConcurrentHashMap<>();
  private final Set<String> pendingSteers = ConcurrentHashMap.newKeySet();
  private final String turn = UUID.randomUUID().toString();
  private volatile boolean live;
  private volatile InputStream stream;
  private String base, session = "";
  private long input, output, cache;

  OpenCodeSession(Process process, Listener listener, String password) {
    this.process = process;
    this.listener = listener;
    authorization =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(("opencode:" + password).getBytes(StandardCharsets.UTF_8));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", authorization);
  }

  private JsonObject call(String method, String path, JsonObject data) throws IOException {
    var builder = request(path).timeout(Duration.ofSeconds(10));
    if (data != null)
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(data.toString()));
    else builder.method(method, HttpRequest.BodyPublishers.noBody());
    try {
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (var body = response.body()) {
        Thread watchdog =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        Thread.sleep(10000);
                        body.close();
                      } catch (InterruptedException | IOException ignored) {
                      }
                    });
        byte[] bytes;
        try {
          bytes = body.readNBytes(2 * 1024 * 1024 + 1);
        } finally {
          watchdog.interrupt();
        }
        if (response.statusCode() < 200
            || response.statusCode() >= 300
            || bytes.length > 2 * 1024 * 1024) throw new IOException("OpenCode request failed");
        return bytes.length == 0
            ? new JsonObject()
            : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  private void prompt(String id, String text, String delivery) throws IOException {
    var data = new JsonObject();
    data.addProperty("id", "msg_" + id.replace("-", ""));
    data.addProperty("text", text);
    data.addProperty("delivery", delivery);
    call("POST", "/api/session/" + session + "/prompt", data);
  }

  public CompletableFuture<Steering> steer(String id, String expectedTurn, String text) {
    if (!live || !turn.equals(expectedTurn))
      return CompletableFuture.completedFuture(new Steering("rejected", "steer_inactive"));
    pendingSteers.add("msg_" + id.replace("-", ""));
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            prompt(id, text, "steer");
            return new Steering("accepted", "");
          } catch (IOException e) {
            return new Steering("unknown", "steer_unknown");
          }
        });
  }

  public void answer(JsonElement id, JsonObject values) throws IOException {
    String key = id.getAsString();
    var form = forms.get(key);
    if (form == null) throw new IOException("expired form");
    var answers = new JsonObject();
    for (var element : form.getAsJsonArray("fields")) {
      var field = element.getAsJsonObject();
      String fieldId = SessionEvents.string(field, "key");
      String value =
          values
              .getAsJsonObject("answers")
              .getAsJsonObject(fieldId)
              .getAsJsonArray("answers")
              .get(0)
              .getAsString();
      if (field.has("options"))
        for (var option : field.getAsJsonArray("options")) {
          var o = option.getAsJsonObject();
          if (value.equals(SessionEvents.string(o, "label"))) {
            value = SessionEvents.string(o, "value");
            break;
          }
        }
      answers.addProperty(fieldId, value);
    }
    var body = new JsonObject();
    body.add("answer", answers);
    call("POST", "/api/session/" + session + "/form/" + safe(key) + "/reply", body);
    forms.remove(key);
  }

  private static String safe(String id) throws IOException {
    if (!id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("invalid identity");
    return id;
  }

  private void question(JsonObject form) throws IOException {
    String id = safe(SessionEvents.string(form, "id"));
    try {
      var questions = new JsonArray();
      for (var element : form.getAsJsonArray("fields")) {
        var field = element.getAsJsonObject();
        if (!SessionEvents.string(field, "type").equals("string")
            || field.has("when") && !field.getAsJsonArray("when").isEmpty()
            || field.has("hidden") && field.get("hidden").getAsBoolean())
          throw new IllegalArgumentException();
        var q = new JsonObject();
        q.addProperty("id", SessionEvents.string(field, "key"));
        String title = SessionEvents.string(field, "title");
        if (title.isBlank()) title = SessionEvents.string(form, "title");
        q.addProperty("header", title.substring(0, Math.min(80, title.length())));
        String description = SessionEvents.string(field, "description");
        q.addProperty("question", title + (description.isBlank() ? "" : "\n" + description));
        var options = new JsonArray();
        var labels = new HashSet<String>();
        if (field.has("options"))
          for (var option : field.getAsJsonArray("options")) {
            var o = option.getAsJsonObject();
            String label = SessionEvents.string(o, "label");
            if (!labels.add(label)) throw new IllegalArgumentException();
            var v = new JsonObject();
            v.addProperty("label", label);
            v.addProperty(
                "description",
                SessionEvents.string(o, "description").isBlank()
                    ? label
                    : SessionEvents.string(o, "description"));
            options.add(v);
          }
        q.add("options", options);
        questions.add(q);
      }
      var params = new JsonObject();
      params.add("questions", questions);
      AgentQuestion.read(params);
      forms.put(id, form);
      listener.question(new JsonPrimitive(id), params);
    } catch (RuntimeException e) {
      call("POST", "/api/session/" + session + "/form/" + id + "/cancel", new JsonObject());
    }
  }

  public Result run(String cwd, String text) throws IOException {
    var reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    try {
      String line;
      while ((line = LocalCodex.boundedLine(reader)) != null) {
        try {
          var frame = JsonParser.parseString(line).getAsJsonObject();
          if (frame.has("url")) {
            base = frame.get("url").getAsString();
            break;
          }
        } catch (RuntimeException ignored) {
        }
      }
      if (base == null) return new Result(false, "");
      URI address = URI.create(base);
      if (!address.getScheme().equals("http")
          || !address.getHost().equals("127.0.0.1")
          || address.getPort() < 1
          || address.getUserInfo() != null
          || address.getRawQuery() != null
          || address.getFragment() != null
          || !Set.of("", "/").contains(address.getPath()))
        throw new IOException("invalid server address");
      base = base.replaceAll("/+$", "");
      // A successful authenticated subscription is established before creating or prompting a
      // session.
      var subscription =
          http.send(
              request("/api/event").header("Accept", "text/event-stream").build(),
              HttpResponse.BodyHandlers.ofInputStream());
      if (subscription.statusCode() != 200) {
        subscription.body().close();
        throw new IOException("event subscription failed");
      }
      stream = subscription.body();
      Thread.ofVirtual().name("ai-opencode-events").start(this::readEvents);
      var create = new JsonObject();
      create.addProperty("title", "Applied Intelligence");
      var location = new JsonObject();
      location.addProperty("directory", cwd);
      create.add("location", location);
      var permissions = new JsonArray();
      for (String action :
          List.of("*", "read", "glob", "grep", "list", "question", "webfetch", "websearch")) {
        var rule = new JsonObject();
        rule.addProperty("action", action);
        rule.addProperty("resource", "*");
        rule.addProperty("effect", action.equals("*") ? "deny" : "allow");
        permissions.add(rule);
      }
      create.add("permissions", permissions);
      var record = call("POST", "/api/session", create).getAsJsonObject("data");
      session = safe(SessionEvents.string(record, "id"));
      listener.session(session);
      listener.turn(turn);
      live = true;
      prompt(UUID.randomUUID().toString(), text, "queue");
      while (process.isAlive()) {
        var event = events.poll(1, TimeUnit.SECONDS);
        if (event == null) continue;
        String type = SessionEvents.string(event, "type");
        if (type.equals("stream_failure")) throw new IOException("event stream lost");
        var data = event.getAsJsonObject("data");
        if (data == null) continue;
        if (type.equals("form.created")) {
          var form = data.getAsJsonObject("form");
          if (form != null && session.equals(SessionEvents.string(form, "sessionID")))
            question(form);
          continue;
        }
        if (!session.equals(SessionEvents.string(data, "sessionID"))) continue;
        if (type.equals("session.inbox.delivered")) {
          pendingSteers.remove(SessionEvents.string(data, "inboxID"));
        } else if (type.equals("session.inbox.cancelled")) {
          if (pendingSteers.remove(SessionEvents.string(data, "inboxID")))
            return new Result(false, "");
        } else if (type.equals("form.replied") || type.equals("form.cancelled")) {
          String id = SessionEvents.string(data, "id");
          forms.remove(id);
          listener.resolved(new JsonPrimitive(id));
        } else if (type.equals("session.tool.input.started"))
          SessionEvents.action(listener, SessionEvents.string(data, "id"), false, false);
        else if (type.equals("session.tool.success") || type.equals("session.tool.failed")) {
          SessionEvents.action(
              listener, SessionEvents.string(data, "id"), true, type.endsWith("failed"));
          imageContent(data.getAsJsonArray("content"));
        } else if (type.equals("session.step.ended") || type.equals("session.step.failed")) {
          var usage = data.getAsJsonObject("tokens");
          if (usage != null) {
            var cached = usage.getAsJsonObject("cache");
            long read = SessionEvents.count(cached, "read");
            input =
                Math.addExact(
                    input,
                    SessionEvents.count(usage, "input")
                        + read
                        + SessionEvents.count(cached, "write"));
            cache = Math.addExact(cache, read);
            output =
                Math.addExact(
                    output,
                    SessionEvents.count(usage, "output") + SessionEvents.count(usage, "reasoning"));
            SessionEvents.usage(listener, input, output, cache);
          }
        } else if (type.equals("session.execution.succeeded")) {
          if (!pendingSteers.isEmpty()) continue;
          var messages =
              call(
                      "GET",
                      "/api/session/" + session + "/message?type=assistant&order=desc&limit=1",
                      null)
                  .getAsJsonArray("data");
          String result =
              messages == null || messages.isEmpty()
                  ? ""
                  : SessionEvents.content(
                      listener, messages.get(0).getAsJsonObject().getAsJsonArray("content"));
          return new Result(!result.isBlank(), SessionEvents.markdown(listener, result));
        } else if (type.equals("session.execution.failed")
            || type.equals("session.execution.interrupted")) return new Result(false, "");
      }
      return new Result(false, "");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    } finally {
      live = false;
      if (stream != null) stream.close();
      http.shutdownNow();
    }
  }

  private void imageContent(JsonArray contents) {
    if (contents == null) return;
    for (var element : contents)
      if (element.isJsonObject()) {
        var c = element.getAsJsonObject();
        String type = SessionEvents.string(c, "type");
        if (type.equals("image")) listener.image(null, SessionEvents.string(c, "data"));
        if (type.equals("file")) {
          String uri = SessionEvents.string(c, "url");
          if (uri.isBlank()) uri = SessionEvents.string(c, "uri");
          if (uri.startsWith("data:image/png;base64,") || uri.startsWith("data:image/jpeg;base64,"))
            listener.image(null, uri.substring(uri.indexOf(',') + 1));
          else if (uri.startsWith("file:"))
            try {
              listener.image(java.nio.file.Path.of(URI.create(uri)).toString(), null);
            } catch (RuntimeException ignored) {
            }
        }
      }
  }

  private void readEvents() {
    try (var input = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      StringBuilder data = new StringBuilder();
      while ((line = LocalCodex.boundedLine(input)) != null) {
        if (line.isEmpty()) {
          if (!data.isEmpty()) {
            if (!events.offer(JsonParser.parseString(data.toString()).getAsJsonObject()))
              throw new IOException("event overflow");
            data.setLength(0);
          }
        } else if (line.startsWith("data:")) {
          if (!data.isEmpty()) data.append('\n');
          data.append(line.substring(5).stripLeading());
          if (data.length() > ImageAssets.MAX_BYTES * 4 / 3 + 65536)
            throw new IOException("event too large");
        }
      }
    } catch (Exception ignored) {
    } finally {
      var failed = new JsonObject();
      failed.addProperty("type", "stream_failure");
      if (!events.offer(failed)) {
        events.clear();
        events.offer(failed);
      }
    }
  }
}
