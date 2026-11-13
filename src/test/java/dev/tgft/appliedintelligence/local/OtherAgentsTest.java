package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs({OS.LINUX, OS.MAC})
class OtherAgentsTest {
  @TempDir Path root;
  LocalCodex runner;

  @AfterEach
  void close() {
    if (runner != null) runner.close();
  }

  static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for protocol fixture");
  }

  private String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  private JsonObject configure(String provider) throws Exception {
    Path executable = root.resolve("cli " + provider);
    String classes =
        Path.of(FakeOtherAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + System.getProperty("path.separator")
            + Path.of(LocalCodex.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            + System.getProperty("path.separator")
            + Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Files.writeString(
        executable,
        "#!/bin/sh\nexec "
            + quote(Path.of(System.getProperty("java.home"), "bin/java").toString())
            + " "
            + quote("-Dai.test.root=" + root)
            + " "
            + quote("-Dai.test.backend=" + provider)
            + " -cp "
            + quote(classes)
            + " "
            + FakeOtherAgent.class.getName()
            + " \"$@\"\n");
    executable.toFile().setExecutable(true);
    if (runner == null)
      runner =
          new LocalCodex(
              root.resolve("settings.json"), root.resolve("jobs.json"), root.resolve("workspace"));
    var data = new JsonObject();
    data.addProperty("provider", provider);
    data.addProperty("executable", executable.toString());
    data.addProperty("workspace", root.resolve("workspace").toString());
    runner.dispatch("local_configure", data);
    await(
        () ->
            runner
                .dispatch("local_settings", new JsonObject())
                .getAsJsonObject("local")
                .get("status")
                .getAsString()
                .equals("local_ready"));
    assertEquals(
        provider,
        runner
            .dispatch("local_settings", new JsonObject())
            .getAsJsonObject("local")
            .get("provider")
            .getAsString());
    return data;
  }

  private JsonObject request(String prompt) {
    var data = new JsonObject();
    data.addProperty("id", UUID.randomUUID().toString());
    data.addProperty("agent", "auto");
    data.addProperty("prompt", prompt);
    data.addProperty("retain", true);
    return data;
  }

  private JsonObject job(JsonObject request) {
    return runner.dispatch("job", request).getAsJsonObject("job");
  }

  private JsonObject finish(JsonObject request) throws Exception {
    runner.dispatch("submit", request);
    await(
        () ->
            Set.of("completed", "failed", "cancelled")
                .contains(job(request).get("state").getAsString()));
    return job(request);
  }

  private void publicOnly(JsonObject record) {
    assertFalse(record.toString().contains("PRIVATE_"));
    assertFalse(record.toString().contains(root.toString()));
    assertFalse(record.has("request"));
  }

  @Test
  void piCompletesOnSettledAndDeduplicatesUsageWithoutReasoning() throws Exception {
    configure("pi");
    var record = finish(request("Summarize"));
    assertEquals("completed", record.get("state").getAsString(), record.toString());
    assertEquals("Pi result 中文", record.get("result").getAsString());
    assertEquals("pi-local", record.get("agent").getAsString());
    var progress = record.getAsJsonObject("progress");
    assertEquals(131072, progress.get("contextWindow").getAsInt());
    assertEquals(32, progress.getAsJsonObject("usage").get("inputTokens").getAsLong());
    assertEquals(5, progress.getAsJsonObject("usage").get("outputTokens").getAsLong());
    assertFalse(record.getAsJsonObject("capabilities").get("questions").getAsBoolean());
    publicOnly(record);
  }

  @Test
  void piSteeringStaysOnOriginalProviderAfterSettingsSwitch() throws Exception {
    configure("pi");
    var request = request("HOLD");
    runner.dispatch("submit", request);
    await(() -> Files.exists(root.resolve("running")));
    await(() -> !job(request).get("turn").getAsString().isBlank());
    String turn = job(request).get("turn").getAsString();
    configure("deepseek");
    var steer = new JsonObject();
    steer.add("id", request.get("id"));
    steer.addProperty("turn", turn);
    steer.addProperty("request", UUID.randomUUID().toString());
    steer.addProperty("text", "finish");
    runner.dispatch("steer_job", steer);
    await(() -> job(request).get("state").getAsString().equals("completed"));
    assertEquals("finish", Files.readString(root.resolve("steer")));
    assertEquals("pi-local", job(request).get("agent").getAsString());
    assertEquals("accepted", runner.dispatch("steer_job", steer).get("steer_state").getAsString());
  }

  @Test
  void deepseekUsesReadonlyOverlayDeniesPermissionsAndDoesNotInventTokenUsage() throws Exception {
    configure("deepseek");
    var record = finish(request("Summarize"));
    assertEquals("completed", record.get("state").getAsString(), record.toString());
    assertEquals("DeepSeek result 中文", record.get("result").getAsString());
    assertFalse(record.getAsJsonObject("capabilities").get("steering").getAsBoolean());
    var progress = record.getAsJsonObject("progress");
    assertEquals(128000, progress.get("contextWindow").getAsInt());
    assertTrue(!progress.has("usage") || progress.get("usage").isJsonNull());
    var overlay =
        JsonParser.parseString(Files.readString(root.resolve("overlay.json"))).getAsJsonArray();
    assertEquals(
        "read-only",
        overlay.get(0).getAsJsonObject().getAsJsonObject("config").get("mode").getAsString());
    await(
        () -> {
          try (var files = Files.list(root)) {
            return files.noneMatch(p -> p.getFileName().toString().startsWith("ai-read-only-"));
          } catch (Exception e) {
            return false;
          }
        });
    publicOnly(record);
  }

  @Test
  void openCodeOwnsAuthenticatedServerSubscribesBeforePromptAndProducesResult() throws Exception {
    configure("opencode");
    var record = finish(request("Summarize"));
    assertEquals("completed", record.get("state").getAsString(), record.toString());
    assertEquals("OpenCode result 中文", record.get("result").getAsString());
    var usage = record.getAsJsonObject("progress").getAsJsonObject("usage");
    assertEquals(35, usage.get("inputTokens").getAsLong());
    assertEquals(10, usage.get("outputTokens").getAsLong());
    publicOnly(record);
    var create =
        JsonParser.parseString(Files.readString(root.resolve("create.json"))).getAsJsonObject();
    assertEquals(
        "deny",
        create.getAsJsonArray("permissions").get(0).getAsJsonObject().get("effect").getAsString());
  }

  @Test
  void openCodeAnswersNativeFormUsingItsOptionValue() throws Exception {
    configure("opencode");
    var request = request("ASK");
    runner.dispatch("submit", request);
    await(() -> job(request).has("question") && !job(request).get("question").isJsonNull());
    var question = job(request).getAsJsonObject("question");
    var answer = new JsonObject();
    answer.add("id", request.get("id"));
    answer.add("request", question.get("request"));
    var values = new JsonObject();
    values.addProperty("format", "简要");
    answer.add("answers", values);
    assertTrue(runner.dispatch("answer_job", answer).has("answer_ack"));
    await(() -> job(request).get("state").getAsString().equals("completed"));
    assertEquals(
        "brief",
        JsonParser.parseString(Files.readString(root.resolve("answers.json")))
            .getAsJsonObject()
            .getAsJsonObject("answer")
            .get("format")
            .getAsString());
  }

  @Test
  void openCodeSteeringAndCancellationUseCurrentExecutionOnly() throws Exception {
    configure("opencode");
    var request = request("HOLD");
    runner.dispatch("submit", request);
    await(() -> Files.exists(root.resolve("running")));
    var steer = new JsonObject();
    steer.add("id", request.get("id"));
    steer.addProperty("request", UUID.randomUUID().toString());
    steer.add("turn", job(request).get("turn"));
    steer.addProperty("text", "finish");
    runner.dispatch("steer_job", steer);
    await(() -> job(request).get("state").getAsString().equals("completed"));
    assertEquals("finish", Files.readString(root.resolve("steer")));
    Files.delete(root.resolve("running"));
    var another = request("HOLD");
    runner.dispatch("submit", another);
    await(() -> Files.exists(root.resolve("running")));
    assertEquals(
        "cancelled",
        runner.dispatch("cancel_job", another).getAsJsonObject("job").get("state").getAsString());
  }

  @Test
  void openCodeRejectsNonLoopbackServerAddressBeforeSendingCredentials() throws Exception {
    configure("opencode");
    Files.writeString(root.resolve("bad-url"), "yes");
    var record = finish(request("Summarize"));
    assertEquals("failed", record.get("state").getAsString());
  }

  @Test
  void oldConfigDefaultsToCodexAndUnknownProviderCannotOverwriteIt() throws Exception {
    var legacy = new LocalCodex.Config("codex", "workspace", 123);
    assertEquals("codex", legacy.provider());
    assertThrows(IllegalArgumentException.class, () -> AgentBackend.of("malicious"));
    configure("pi");
    String original = Files.readString(root.resolve("settings.json"));
    var invalid = new JsonObject();
    invalid.addProperty("provider", "unknown");
    invalid.addProperty("executable", "other");
    invalid.addProperty("workspace", root.toString());
    assertThrows(IllegalArgumentException.class, () -> runner.dispatch("local_configure", invalid));
    assertEquals(original, Files.readString(root.resolve("settings.json")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pi", "deepseek", "opencode"})
  void localServiceRetainsActualProviderAndArchivesFinalAnswerImage(String provider)
      throws Exception {
    configure(provider);
    ImageIO.write(
        new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB),
        "png",
        root.resolve("workspace/figure.png").toFile());
    try (var service = new LocalAgentService(runner, root.resolve("routes.json"))) {
      var request = request("Summarize image");
      assertFalse(service.dispatch("submit", request).has("error_key"));
      await(() -> job(request).get("state").getAsString().equals("completed"));
      var record = service.dispatch("job", request).getAsJsonObject("job");
      assertEquals(provider + "-local", record.get("agent").getAsString());
      assertEquals(1, record.getAsJsonArray("images").size());
      assertFalse(record.get("result").getAsString().contains("figure.png"));
      assertEquals(
          AgentBackend.of(provider).steering,
          record.getAsJsonObject("capabilities").get("steering").getAsBoolean());
      publicOnly(record);
    }
  }

  @Test
  void officialWindowsNpmLaunchersUseKnownEntriesWithoutAShell() throws Exception {
    var entries =
        Map.of(
            AgentBackend.PI,
            "@earendil-works/pi-coding-agent/dist/bundle/cli.js",
            AgentBackend.DEEPSEEK,
            "@deepseek-ai/dsh/lib/bin.js",
            AgentBackend.OPENCODE,
            "@opencode/cli/bin/opencode.exe");
    for (var entry : entries.entrySet()) {
      Path npm = root.resolve(entry.getKey().id + " path & spaces");
      Files.createDirectories(npm);
      Path shim = npm.resolve(entry.getKey().executable + ".cmd"),
          node = npm.resolve("node.exe"),
          script = npm.resolve("node_modules/" + entry.getValue());
      Files.createDirectories(script.getParent());
      Files.writeString(shim, "fixture");
      Files.writeString(node, "fixture");
      Files.writeString(script, "fixture");
      assertEquals(
          entry.getKey() == AgentBackend.OPENCODE
              ? List.of(script.toString())
              : List.of(node.toString(), script.toString()),
          LocalCodex.commandPrefix(shim.toString(), entry.getKey(), true));
      Files.delete(script);
      assertThrows(
          LocalCodex.Failure.class,
          () -> LocalCodex.commandPrefix(shim.toString(), entry.getKey(), true));
    }
  }
}
