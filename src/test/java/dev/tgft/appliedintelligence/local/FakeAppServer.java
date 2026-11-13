package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A separate-process stdio peer: no model, credentials, game classes or network. */
public final class FakeAppServer {
  private static final Gson JSON = new Gson();
  private static final PrintStream OUT = System.out;
  private static boolean failed;

  private static void send(JsonObject value) {
    OUT.println(value);
    OUT.flush();
  }

  private static JsonObject obj(String value) {
    return JsonParser.parseString(value).getAsJsonObject();
  }

  private static void notify(String method, JsonObject params) {
    params.addProperty("threadId", "fixture-session");
    params.addProperty("turnId", "fixture-turn");
    var value = new JsonObject();
    value.addProperty("method", method);
    value.add("params", params);
    send(value);
  }

  private static void reply(JsonElement id, String result) {
    var value = new JsonObject();
    value.add("id", id);
    value.add("result", obj(result));
    send(value);
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getProperty("ai.test.root"));
    Files.writeString(root.resolve("app-server-args.txt"), String.join("\n", args));
    if (!List.of(args).contains("features.default_mode_request_user_input=true")) System.exit(9);
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String prompt = "";
    String line;
    while ((line = reader.readLine()) != null) {
      var frame = obj(line);
      String method = frame.has("method") ? frame.get("method").getAsString() : "";
      if (method.equals("initialize")) reply(frame.get("id"), "{}");
      if (method.equals("thread/start")) {
        var params = frame.getAsJsonObject("params");
        if (!params.get("sandbox").getAsString().equals("read-only")
            || !params.get("approvalPolicy").getAsString().equals("never")) System.exit(9);
        Files.writeString(root.resolve("thread-start.json"), params.toString());
        reply(frame.get("id"), "{\"thread\":{\"id\":\"fixture-session\"}}");
      }
      if (method.equals("turn/start")) {
        prompt =
            frame
                .getAsJsonObject("params")
                .getAsJsonArray("input")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString();
        reply(frame.get("id"), "{\"turn\":{\"id\":\"fixture-turn\",\"status\":\"inProgress\"}}");
        break;
      }
    }
    if (prompt.contains("IMAGE_FIXTURE") || Files.exists(root.resolve("image-fixture"))) {
      imageFixture(root);
      if (prompt.contains("INLINE")) {
        var bitmap =
            new java.awt.image.BufferedImage(180, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new Random(41);
        for (int y = 0; y < 180; y++)
          for (int x = 0; x < 180; x++) bitmap.setRGB(x, y, random.nextInt());
        var bytes = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bitmap, "png", bytes);
        var block = new JsonObject();
        block.addProperty("type", "image");
        block.addProperty("data", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        var content = new JsonArray();
        content.add(block);
        var result = new JsonObject();
        result.add("content", content);
        var item = new JsonObject();
        item.addProperty("type", "mcpToolCall");
        item.addProperty("id", "inline-image");
        item.add("result", result);
        var params = new JsonObject();
        params.add("item", item);
        notify("item/completed", params);
      }
      if (Files.exists(root.resolve("image-fixture"))) {
        steerFixture(root, prompt, reader);
        return;
      }
      var item =
          obj(
              "{\"type\":\"agentMessage\",\"id\":\"image-result\",\"text\":\"Image result"
                  + " ![终端布局](终端布局.png)\"}");
      var params = new JsonObject();
      params.add("item", item);
      notify("item/completed", params);
      notify(
          "turn/completed", obj("{\"turn\":{\"id\":\"fixture-turn\",\"status\":\"completed\"}}"));
      return;
    }
    if (prompt.contains("STEER_FIXTURE") || Files.exists(root.resolve("steer-fixture"))) {
      steerFixture(root, prompt, reader);
      return;
    }
    boolean ask = prompt.contains("ASK_FIXTURE") || Files.exists(root.resolve("ask-fixture"));
    if (ask) {
      Files.writeString(root.resolve("question-started"), "yes");
      for (int round = 0; round < (prompt.contains("ASK_TWICE") ? 2 : 1); round++) {
        var request =
            obj(
                "{\"id\":\"ask-"
                    + round
                    + "\",\"method\":\"item/tool/requestUserInput\",\"params\":{\"threadId\":\"fixture-session\",\"turnId\":\"fixture-turn\",\"itemId\":\"question-item\",\"isBlocking\":true,\"questions\":[{\"id\":\"format\",\"header\":\"成果格式\",\"question\":\"这份结果应该侧重哪一种格式？可以选择建议，也可以填写自己的回答。\",\"options\":[{\"label\":\"简要总结\",\"description\":\"提炼重点，保留关键结论。\"},{\"label\":\"详细说明\",\"description\":\"补充步骤与原因，便于复查。\"}]},{\"id\":\"audience\",\"header\":\"阅读对象\",\"question\":\"这份结果主要给谁看？\",\"options\":null}]}}");
        if (prompt.contains("SECRET"))
          request
              .getAsJsonObject("params")
              .getAsJsonArray("questions")
              .get(0)
              .getAsJsonObject()
              .addProperty("isSecret", true);
        send(request);
        if (prompt.contains("STALE_REQUEST")) {
          Thread.sleep(100);
          var resolved = obj("{\"requestId\":\"ask-0\"}");
          notify("serverRequest/resolved", resolved);
        }
        var response = obj(Objects.requireNonNull(reader.readLine()));
        if (!response.get("id").equals(request.get("id")) || !response.has("result"))
          System.exit(10);
        Files.writeString(
            root.resolve("answers"),
            response + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        prompt += "\nPlayer answers: " + response.getAsJsonObject("result").toString();
      }
    }
    final String content = prompt;
    System.setIn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    System.setOut(
        new PrintStream(
            new OutputStream() {
              private final ByteArrayOutputStream line = new ByteArrayOutputStream();

              public void write(int b) {
                if (b == '\n') {
                  translate(line.toString(StandardCharsets.UTF_8));
                  line.reset();
                } else line.write(b);
              }
            },
            true,
            StandardCharsets.UTF_8));
    var legacy =
        new ArrayList<>(List.of("exec", "--json", "--sandbox", "read-only", "-C", root.toString()));
    for (int i = 1; i < args.length; i++)
      if (args[i].equals("-c")) {
        legacy.add("-c");
        legacy.add(args[++i]);
      }
    legacy.add("-");
    FakeCodex.main(legacy.toArray(String[]::new));
  }

  private static void imageFixture(Path root) throws Exception {
    // Thread/start contains the actual configured workspace; never use the test root as an
    // attachment path.
    Path workspace =
        Path.of(obj(Files.readString(root.resolve("thread-start.json"))).get("cwd").getAsString());
    Files.createDirectories(workspace);
    for (int n = 0; n < 3; n++) {
      var image =
          new java.awt.image.BufferedImage(800, 450, java.awt.image.BufferedImage.TYPE_INT_RGB);
      var g = image.createGraphics();
      g.setColor(new java.awt.Color(0x252635));
      g.fillRect(0, 0, 800, 450);
      g.setColor(new java.awt.Color(0xc6c6d2));
      g.fillRect(40, 40, 720, 370);
      g.setColor(new java.awt.Color(0x969ab0));
      g.fillRect(56, 86, 688, 308);
      g.setColor(new java.awt.Color(0x8eeaff));
      g.fillRect(56, 56, 180 + n * 80, 18);
      for (int y = 0; y < 3; y++)
        for (int x = 0; x < 7; x++) {
          g.setColor(new java.awt.Color(0x66667e));
          g.fillRect(72 + x * 92, 102 + y * 92, 80, 80);
          g.setColor(new java.awt.Color(n == 0 ? 0x8eeaff : n == 1 ? 0xffd382 : 0xc498e8));
          g.fillRect(84 + x * 92, 114 + y * 92, 24, 24);
        }
      g.dispose();
      String name = n == 0 ? "终端布局.png" : n == 1 ? "任务步骤.png" : "成果预览.png";
      javax.imageio.ImageIO.write(image, "png", workspace.resolve(name).toFile());
      var item = new JsonObject();
      item.addProperty("type", n == 2 ? "imageGeneration" : "imageView");
      item.addProperty("id", "image-" + n);
      item.addProperty(n == 2 ? "savedPath" : "path", workspace.resolve(name).toString());
      var params = new JsonObject();
      params.add("item", item);
      notify("item/completed", params);
    }
  }

  private static void steerFixture(Path root, String prompt, BufferedReader reader)
      throws Exception {
    Files.writeString(
        root.resolve("calls"), "run\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    var plan = new JsonArray();
    int count = prompt.contains("SCROLL") || Files.exists(root.resolve("steer-scroll")) ? 15 : 3;
    for (int i = 0; i < count; i++) {
      var step = new JsonObject();
      step.addProperty(
          "step", i == 0 ? "读取资料" : i == 1 ? "执行任务" : i == 2 ? "整理结果" : "检查条目 " + (i + 1));
      step.addProperty("status", i == 0 ? "inProgress" : "pending");
      plan.add(step);
    }
    var progress = new JsonObject();
    progress.add("plan", plan);
    notify("turn/plan/updated", progress);
    usage(obj("{\"input_tokens\":12000,\"cached_input_tokens\":8000,\"output_tokens\":640}"));
    Files.writeString(root.resolve("steer-started"), "yes");
    if (prompt.contains("STEER_BLOCKED")) Thread.sleep(60000);
    var accepted = new ArrayList<String>();
    String line;
    while ((line = reader.readLine()) != null) {
      var frame = obj(line);
      if (!frame.has("method") || !frame.get("method").getAsString().equals("turn/steer")) continue;
      var params = frame.getAsJsonObject("params");
      Files.writeString(
          root.resolve("steers"),
          frame + "\n",
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      String text =
          params.getAsJsonArray("input").get(0).getAsJsonObject().get("text").getAsString();
      boolean valid =
          params.get("threadId").getAsString().equals("fixture-session")
              && params.get("expectedTurnId").getAsString().equals("fixture-turn");
      UUID.fromString(params.get("clientUserMessageId").getAsString());
      if (!valid || text.contains("reject")) {
        var error = new JsonObject();
        error.add("id", frame.get("id"));
        error.add("error", obj("{\"code\":-32602,\"message\":\"Not active\"}"));
        send(error);
        continue;
      }
      if (text.contains("delay")) Thread.sleep(1500);
      if (text.contains("no_ack")) continue;
      if (text.contains("eof")) return;
      if (text.contains("end_race")) {
        finishSteering(accepted);
        return;
      }
      reply(
          frame.get("id"),
          text.contains("wrong_turn")
              ? "{\"turnId\":\"another-turn\"}"
              : "{\"turnId\":\"fixture-turn\"}");
      accepted.add(text);
      usage(obj("{\"input_tokens\":16480,\"cached_input_tokens\":12000,\"output_tokens\":2160}"));
      if (text.contains("finish")) {
        finishSteering(accepted);
        return;
      }
    }
  }

  private static void finishSteering(List<String> accepted) {
    var item = new JsonObject();
    item.addProperty("id", "final");
    item.addProperty("type", "agentMessage");
    item.addProperty("phase", "final_answer");
    item.addProperty("text", "已完成任务。补充指令：\n" + String.join("\n", accepted));
    var params = new JsonObject();
    params.add("item", item);
    notify("item/completed", params);
    notify("turn/completed", obj("{\"turn\":{\"id\":\"fixture-turn\",\"status\":\"completed\"}}"));
  }

  private static void usage(JsonObject total) {
    var normalized = new JsonObject();
    normalized.add("inputTokens", total.get("input_tokens"));
    normalized.add("outputTokens", total.get("output_tokens"));
    normalized.add("cachedInputTokens", total.get("cached_input_tokens"));
    var usage = new JsonObject();
    usage.add("total", normalized);
    usage.addProperty("modelContextWindow", 128000);
    var params = new JsonObject();
    params.add("tokenUsage", usage);
    notify("thread/tokenUsage/updated", params);
  }

  private static void translate(String line) {
    JsonObject value;
    try {
      value = obj(line);
    } catch (RuntimeException e) {
      OUT.println(line);
      OUT.flush();
      return;
    }
    String type = value.get("type").getAsString();
    if (type.equals("token_count"))
      usage(value.getAsJsonObject("info").getAsJsonObject("total_token_usage"));
    if (type.equals("turn.failed")) {
      failed = true;
      notify("error", new JsonObject());
    }
    if (type.equals("item.updated")
        && value.getAsJsonObject("item").get("type").getAsString().equals("todo_list")) {
      var plan = new JsonArray();
      boolean active = false;
      for (var v : value.getAsJsonObject("item").getAsJsonArray("items")) {
        var p = v.getAsJsonObject();
        var step = new JsonObject();
        step.add("step", p.get("text"));
        boolean complete = p.get("completed").getAsBoolean();
        step.addProperty("status", complete ? "completed" : active ? "pending" : "inProgress");
        if (!complete) active = true;
        plan.add(step);
      }
      var params = new JsonObject();
      params.add("plan", plan);
      notify("turn/plan/updated", params);
    }
    if (type.equals("item.started") || type.equals("item.completed")) {
      var item = value.getAsJsonObject("item");
      if (item.get("type").getAsString().equals("agent_message"))
        item.addProperty("type", "agentMessage");
      item.addProperty("id", "fixture-item");
      var params = new JsonObject();
      params.add("item", item);
      notify(type.equals("item.started") ? "item/started" : "item/completed", params);
    }
    if (type.equals("turn.completed")) {
      if (value.has("usage")) usage(value.getAsJsonObject("usage"));
      notify(
          "turn/completed",
          obj(
              "{\"turn\":{\"id\":\"fixture-turn\",\"status\":\""
                  + (failed ? "failed" : "completed")
                  + "\"}}"));
    }
  }
}
