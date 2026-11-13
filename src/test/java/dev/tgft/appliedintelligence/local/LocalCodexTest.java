package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class LocalCodexTest {
  @TempDir Path root;
  LocalCodex service;
  Path executable, config, database;

  @BeforeEach
  void setup() throws Exception {
    config = root.resolve("config/local.json");
    database = root.resolve("world/data/jobs.json");
    executable = root.resolve("Codex launcher with spaces");
    String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
    String classes =
        Path.of(FakeCodex.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString()
            + System.getProperty("path.separator")
            + Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Files.writeString(
        executable,
        "#!/bin/sh\nexec "
            + quote(java)
            + " "
            + quote("-Dai.test.root=" + root)
            + " -cp "
            + quote(classes)
            + " "
            + FakeCodex.class.getName()
            + " \"$@\"\n");
    assertTrue(executable.toFile().setExecutable(true));
    service = new LocalCodex(config, database, root.resolve("workspace"));
  }

  @AfterEach
  void cleanup() {
    if (service != null) service.close();
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private JsonObject call(String action) {
    return service.dispatch(action, new JsonObject());
  }

  private String status() {
    return call("local_settings").getAsJsonObject("local").get("status").getAsString();
  }

  private void configure() {
    JsonObject settings = new JsonObject();
    settings.addProperty("executable", executable.toString());
    settings.addProperty("workspace", root.resolve("workspace with spaces").toString());
    service.dispatch("local_configure", settings);
  }

  private void ready() throws Exception {
    configure();
    await(() -> status().equals("local_ready"));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for process state");
  }

  private JsonObject request(String prompt) {
    JsonObject data = new JsonObject();
    data.addProperty("id", UUID.randomUUID().toString());
    data.addProperty("agent", "codex-local");
    data.addProperty("prompt", prompt);
    JsonArray skills = new JsonArray();
    skills.add("Be concise 中文");
    data.add("skills", skills);
    return data;
  }

  private JsonObject job(String id) {
    for (var value : call("jobs").getAsJsonArray("jobs")) {
      JsonObject job = value.getAsJsonObject();
      if (job.get("id").getAsString().equals(id)) return job;
    }
    throw new AssertionError("Missing job");
  }

  private JsonObject finish(JsonObject data) throws Exception {
    String id = service.dispatch("submit", data).get("id").getAsString();
    await(
        () ->
            Set.of("completed", "failed", "interrupted")
                .contains(job(id).get("state").getAsString()));
    return job(id);
  }

  @Test
  void receivesInlineMcpImageAboveLegacyLineLimit() throws Exception {
    ready();
    var result = finish(request("IMAGE_FIXTURE INLINE"));
    assertEquals("completed", result.get("state").getAsString());
    var entries = result.getAsJsonArray("images");
    assertEquals(4, entries.size());
    assertTrue(entries.get(3).getAsJsonObject().get("bytes").getAsInt() > 65536);
  }

  @Test
  void capturesAppServerImagesWithoutPublicPathsAndRestoresAfterRestart() throws Exception {
    ready();
    var record = finish(request("IMAGE_FIXTURE"));
    assertEquals("completed", record.get("state").getAsString());
    assertEquals(3, record.getAsJsonArray("images").size());
    assertFalse(record.toString().contains(root.toString()));
    assertFalse(record.get("result").getAsString().contains(".png)"));
    String id = record.get("id").getAsString();
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals(3, job(id).getAsJsonArray("images").size());
    var query = new JsonObject();
    query.addProperty("id", id);
    query.addProperty(
        "image", record.getAsJsonArray("images").get(0).getAsJsonObject().get("id").getAsString());
    query.addProperty("offset", 0);
    assertTrue(service.dispatch("image_chunk", query).has("data"));
  }

  @Test
  void configurationIsInGameAndRequiresNoBridgeSecret() throws Exception {
    assertEquals("local_not_configured", status());
    assertTrue(call("agents").getAsJsonArray("agents").isEmpty());
    ready();
    assertTrue(Files.isDirectory(root.resolve("workspace with spaces")));
    assertEquals(
        "Codex",
        call("agents").getAsJsonArray("agents").get(0).getAsJsonObject().get("name").getAsString());
    assertTrue(Files.readString(config).contains("executable"));
    assertFalse(Files.readString(config).contains("token"));
  }

  @Test
  void loginFailureAndMissingCliAreDistinct() throws Exception {
    Files.writeString(root.resolve("logged-out"), "");
    configure();
    await(() -> status().equals("local_login_required"));
    assertTrue(call("agents").getAsJsonArray("agents").isEmpty());
    JsonObject settings = new JsonObject();
    settings.addProperty("executable", root.resolve("missing.exe").toString());
    settings.addProperty("workspace", root.resolve("workspace").toString());
    service.dispatch("local_configure", settings);
    await(() -> status().equals("local_cli_missing"));
  }

  @Test
  void realSubprocessUsesReadOnlySandboxAndPreservesSnapshot() throws Exception {
    assertEquals("fixture-not-a-credential", System.getenv("AI_BRIDGE_TOKEN"));
    ready();
    JsonObject data = request("Reply 中文");
    String id = service.dispatch("submit", data).get("id").getAsString();
    data.getAsJsonArray("skills").set(0, new JsonPrimitive("Changed original"));
    await(() -> job(id).get("state").getAsString().equals("completed"));
    JsonObject job = job(id);
    assertTrue(job.get("result").getAsString().contains("Skill:\nBe concise 中文"));
    assertFalse(job.get("result").getAsString().contains("Changed original"));
    assertEquals("fixture-session", job.get("session").getAsString());
    assertFalse(job.toString().contains("private-reasoning-fixture"));
    assertFalse(job.has("request"));
  }

  @Test
  void simultaneousRetriesStartOnlyOneProcessAndRejectChangedMeaning() throws Exception {
    ready();
    JsonObject data = request("Retry");
    try (ExecutorService clients = Executors.newFixedThreadPool(8)) {
      List<Callable<JsonObject>> calls = new ArrayList<>();
      for (int i = 0; i < 8; i++) calls.add(() -> service.dispatch("submit", data.deepCopy()));
      for (Future<JsonObject> result : clients.invokeAll(calls))
        assertEquals(data.get("id"), result.get().get("id"));
    }
    await(() -> job(data.get("id").getAsString()).get("state").getAsString().equals("completed"));
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
    data.addProperty("prompt", "Changed request");
    assertEquals(
        "request_conflict",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("submit", data)).key);
  }

  @Test
  void errorsAndMissingCompletionTextCannotBecomeSuccess() throws Exception {
    ready();
    for (String prompt : List.of("FAIL", "ERROR_THEN_COMPLETE", "NO_MESSAGE")) {
      JsonObject job = finish(request(prompt));
      assertEquals("failed", job.get("state").getAsString());
      assertEquals("local_execution_failed", job.get("result_key").getAsString());
    }
  }

  @Test
  void oversizedOutputIsBoundedAndDoesNotBreakLaterEvents() throws Exception {
    ready();
    JsonObject job = finish(request("OVERSIZED"));
    assertEquals("completed", job.get("state").getAsString());
    assertTrue(Files.size(database) < 5000);
  }

  @Test
  void timeoutKillsCodexAndReportsFailure() throws Exception {
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"), Duration.ofSeconds(1));
    ready();
    JsonObject job = finish(request("SLOW"));
    assertEquals("failed", job.get("state").getAsString());
    assertEquals("local_task_timeout", job.get("result_key").getAsString());
  }

  @Test
  void closingWorldKillsChildProcessesAndDoesNotReplayQueuedTasks() throws Exception {
    ready();
    JsonObject first = request("CHILD"), second = request("Queued");
    service.dispatch("submit", first);
    service.dispatch("submit", second);
    await(() -> Files.exists(root.resolve("child.pid")));
    long pid = Long.parseLong(Files.readString(root.resolve("child.pid")));
    service.close();
    await(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    service = new LocalCodex(config, database, root.resolve("workspace"));
    await(() -> status().equals("local_ready"));
    assertEquals("interrupted", job(first.get("id").getAsString()).get("state").getAsString());
    assertEquals("interrupted", job(second.get("id").getAsString()).get("state").getAsString());
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
  }

  @Test
  void completedResultSurvivesWorldReopenAndRetry() throws Exception {
    ready();
    JsonObject data = request("Persist");
    JsonObject before = finish(data);
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals(before, job(data.get("id").getAsString()));
    service.dispatch("submit", data);
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
  }

  @Test
  void windowsNpmShimResolvesToNodeWithoutShellInterpretation() throws Exception {
    Path npm = root.resolve("npm path & spaces");
    Files.createDirectories(npm.resolve("node_modules/@openai/codex/bin"));
    Path shim = npm.resolve("codex.cmd"), node = npm.resolve("node.exe");
    Path script = npm.resolve("node_modules/@openai/codex/bin/codex.js");
    Files.writeString(shim, "fixture");
    Files.writeString(node, "fixture");
    Files.writeString(script, "fixture");
    assertEquals(
        List.of(node.toString(), script.toString()),
        LocalCodex.commandPrefix(shim.toString(), true));
  }

  @Test
  void arbitraryWindowsBatchLaunchersAreNotPassedToAShell() throws Exception {
    Path shim = root.resolve("custom.cmd");
    Files.writeString(shim, "fixture");
    assertEquals(
        "local_launcher_unsupported",
        assertThrows(
                LocalCodex.Failure.class, () -> LocalCodex.commandPrefix(shim.toString(), true))
            .key);
  }

  @Test
  void craftingStatusListsAllTasksWithoutDuplicatingLargeResults() throws Exception {
    ready();
    String first = null;
    for (int i = 0; i < 8; i++) {
      JsonObject data = request("Long result " + i + "\n" + "x".repeat(7000));
      data.addProperty("title", "Named task " + i);
      data.addProperty("recipe", "a".repeat(64));
      JsonObject completed = finish(data);
      assertEquals("completed", completed.get("state").getAsString());
      if (i == 0) first = data.get("id").getAsString();
    }
    JsonObject selection = new JsonObject();
    selection.addProperty("job", first);
    JsonObject response = service.dispatch("status_jobs", selection);
    assertEquals(8, response.getAsJsonArray("jobs").size());
    assertEquals(first, response.getAsJsonObject("selected_job").get("id").getAsString());
    assertTrue(
        response
            .getAsJsonObject("selected_job")
            .get("result")
            .getAsString()
            .contains("Long result 0"));
    for (JsonElement value : response.getAsJsonArray("jobs")) {
      assertFalse(value.getAsJsonObject().has("result"));
      assertFalse(value.getAsJsonObject().has("request"));
    }
    selection.addProperty("summaries_only", true);
    JsonObject summaries = service.dispatch("status_jobs", selection);
    assertFalse(summaries.has("selected_job"));
    var summary = summaries.getAsJsonArray("jobs").get(0).getAsJsonObject();
    assertEquals("a".repeat(64), summary.get("recipe").getAsString());
    assertEquals("turn.completed", summary.get("stage").getAsString());
    assertTrue(response.toString().length() < 14000);
    selection.remove("summaries_only");
    selection.addProperty("progress_only", true);
    JsonObject compact = service.dispatch("status_jobs", selection);
    JsonObject compactJob = compact.getAsJsonObject("selected_job");
    assertTrue(compactJob.has("progress"));
    for (String field : java.util.List.of("result", "request", "result_key", "events", "session"))
      assertFalse(compactJob.has(field), field);
    assertFalse(compact.getAsJsonArray("jobs").get(0).getAsJsonObject().has("title"));
    assertTrue(compact.toString().length() < 5000);

    assertEquals(
        "Named task 7",
        response.getAsJsonArray("jobs").get(0).getAsJsonObject().get("title").getAsString());
  }

  @Test
  @Timeout(60)
  void uncollectedCraftResultsSurviveHistoryRolloverAndRestartUntilAcknowledged() throws Exception {
    ready();
    JsonObject protectedRequest = request("uncollected product");
    protectedRequest.addProperty("retain", true);
    finish(protectedRequest);
    String id = protectedRequest.get("id").getAsString();
    for (int i = 0; i < 101; i++) finish(request("ordinary history " + i));
    JsonObject query = new JsonObject();
    query.addProperty("id", id);
    assertTrue(
        service.dispatch("job", query).getAsJsonObject("job").get("retained").getAsBoolean());
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    await(() -> status().equals("local_ready"));
    assertEquals(
        "completed",
        service.dispatch("job", query).getAsJsonObject("job").get("state").getAsString());
    service.dispatch("release_job", query);
    finish(request("after acknowledgement"));
    assertFalse(service.dispatch("job", query).has("job"));
  }

  @Test
  void configuredContextReachesCliAndReportedStepsSurviveReload() throws Exception {
    JsonObject settings = new JsonObject();
    settings.addProperty("executable", executable.toString());
    settings.addProperty("workspace", root.resolve("workspace").toString());
    settings.addProperty("context_tokens", 128000);
    service.dispatch("local_configure", settings);
    await(() -> status().equals("local_ready"));
    Files.writeString(root.resolve("usage-final-only"), "");
    var result = finish(request("PROGRESS_FIXTURE"));
    String args = Files.readString(root.resolve("last-args.txt"));
    assertTrue(args.contains("-c\nmodel_context_window=128000"));
    var progress = result.getAsJsonObject("progress");
    assertTrue(progress.get("started").getAsDouble() > 0);
    assertEquals(128000, progress.get("contextWindow").getAsInt());
    var usage = progress.getAsJsonObject("usage");
    assertEquals(16480, usage.get("inputTokens").getAsLong());
    assertEquals(2160, usage.get("outputTokens").getAsLong());
    assertEquals(12000, usage.get("cachedInputTokens").getAsLong());
    var status = new JsonObject();
    status.addProperty("progress_only", true);
    status.addProperty("job", result.get("id").getAsString());
    var snapshot = service.dispatch("status_jobs", status);
    assertEquals(usage, snapshot.getAsJsonArray("jobs").get(0).getAsJsonObject().get("usage"));
    assertEquals(
        usage, snapshot.getAsJsonObject("selected_job").getAsJsonObject("progress").get("usage"));
    assertEquals(3, progress.getAsJsonArray("steps").size());
    assertTrue(
        progress.getAsJsonArray("steps").asList().stream()
            .allMatch(e -> e.getAsJsonObject().get("state").getAsString().equals("completed")));
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    var query = new JsonObject();
    query.addProperty("id", result.get("id").getAsString());
    assertEquals(
        progress,
        service.dispatch("job", query).getAsJsonObject("job").getAsJsonObject("progress"));
  }

  @Test
  void corruptRecordsArePreservedAndNeverOverwritten() throws Exception {
    service.close();
    Files.createDirectories(database.getParent());
    Files.writeString(database, "broken");
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals(
        "local_storage_error", assertThrows(LocalCodex.Failure.class, () -> call("jobs")).key);
    service.close();
    assertEquals("broken", Files.readString(database));
  }

  private JsonObject waitQuestion(String id) throws Exception {
    await(() -> job(id).has("question") && !job(id).get("question").isJsonNull());
    return job(id).getAsJsonObject("question");
  }

  private JsonObject answer(String id, JsonObject question) {
    var data = new JsonObject();
    data.addProperty("id", id);
    data.add("request", question.get("request"));
    var answers = new JsonObject();
    answers.addProperty("format", "中文自定义总结");
    answers.addProperty("audience", "新玩家");
    data.add("answers", answers);
    return data;
  }

  @Test
  void questionsPauseTimeoutAndAnswersResumeTheSameTurnIdempotently() throws Exception {
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"), Duration.ofSeconds(1));
    ready();
    var data = request("ASK_FIXTURE");
    String id = service.dispatch("submit", data).get("id").getAsString();
    var question = waitQuestion(id);
    Thread.sleep(1250);
    assertEquals("running", job(id).get("state").getAsString());
    var invalid = answer(id, question);
    invalid.getAsJsonObject("answers").remove("audience");
    assertEquals(
        "question_invalid_answer",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("answer_job", invalid)).key);
    var valid = answer(id, question);
    assertEquals(question.get("request"), service.dispatch("answer_job", valid).get("answer_ack"));
    assertEquals(question.get("request"), service.dispatch("answer_job", valid).get("answer_ack"));
    var conflicting = valid.deepCopy();
    conflicting.getAsJsonObject("answers").addProperty("format", "Changed");
    assertEquals(
        "request_conflict",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("answer_job", conflicting))
            .key);
    await(() -> job(id).get("state").getAsString().equals("completed"));
    assertTrue(job(id).get("result").getAsString().contains("中文自定义总结"));
    assertFalse(job(id).has("question"));
    assertFalse(job(id).has("answerReceipts"));
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
    assertEquals(1, Files.readAllLines(root.resolve("answers")).size());
    var thread =
        JsonParser.parseString(Files.readString(root.resolve("thread-start.json")))
            .getAsJsonObject();
    assertEquals("never", thread.get("approvalPolicy").getAsString());
    assertEquals("read-only", thread.get("sandbox").getAsString());
    assertTrue(
        Files.readString(root.resolve("app-server-args.txt"))
            .contains("features.default_mode_request_user_input=true"));
  }

  @Test
  void anOldAnswerCannotResolveTheNextQuestion() throws Exception {
    ready();
    var data = request("ASK_FIXTURE ASK_TWICE");
    String id = service.dispatch("submit", data).get("id").getAsString();
    var first = waitQuestion(id);
    var reply = answer(id, first);
    service.dispatch("answer_job", reply);
    await(
        () ->
            job(id).has("question")
                && !job(id)
                    .getAsJsonObject("question")
                    .get("request")
                    .equals(first.get("request")));
    var second = job(id).getAsJsonObject("question");
    service.dispatch("answer_job", reply);
    assertEquals(second, job(id).getAsJsonObject("question"));
    service.dispatch("answer_job", answer(id, second));
    await(() -> job(id).get("state").getAsString().equals("completed"));
    assertEquals(2, Files.readAllLines(root.resolve("answers")).size());
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
  }

  @Test
  void cancellingOrClosingAnAwaitingJobInvalidatesItsQuestion() throws Exception {
    ready();
    var first = request("ASK_FIXTURE");
    String id = service.dispatch("submit", first).get("id").getAsString();
    var pending = waitQuestion(id);
    var cancel = new JsonObject();
    cancel.addProperty("id", id);
    service.dispatch("cancel_job", cancel);
    assertEquals(
        "question_expired",
        assertThrows(
                LocalCodex.Failure.class, () -> service.dispatch("answer_job", answer(id, pending)))
            .key);
    assertFalse(job(id).has("question"));
    var second = request("ASK_FIXTURE");
    String next = service.dispatch("submit", second).get("id").getAsString();
    var nextQuestion = waitQuestion(next);
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals("interrupted", job(next).get("state").getAsString());
    assertFalse(job(next).has("question"));
    assertEquals(
        "question_expired",
        assertThrows(
                LocalCodex.Failure.class,
                () -> service.dispatch("answer_job", answer(next, nextQuestion)))
            .key);
    assertFalse(Files.exists(root.resolve("calls")));
  }

  @Test
  void answeredQuestionsRestoreTheExecutionTimeout() throws Exception {
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"), Duration.ofSeconds(1));
    ready();
    var data = request("ASK_FIXTURE SLOW");
    String id = service.dispatch("submit", data).get("id").getAsString();
    service.dispatch("answer_job", answer(id, waitQuestion(id)));
    await(() -> job(id).get("state").getAsString().equals("failed"));
    assertEquals("local_task_timeout", job(id).get("result_key").getAsString());
  }

  @Test
  void serverResolvedRequestsAndUnsupportedSecretQuestionsCannotAcceptReplies() throws Exception {
    ready();
    var data = request("ASK_FIXTURE STALE_REQUEST");
    String id = service.dispatch("submit", data).get("id").getAsString();
    var q = waitQuestion(id);
    await(() -> !job(id).has("question"));
    assertEquals(
        "question_expired",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("answer_job", answer(id, q)))
            .key);
    var cancel = new JsonObject();
    cancel.addProperty("id", id);
    service.dispatch("cancel_job", cancel);
    var secret = finish(request("ASK_FIXTURE SECRET"));
    assertEquals("failed", secret.get("state").getAsString());
    assertEquals("question_unsupported", secret.get("result_key").getAsString());
    assertFalse(secret.has("question"));
  }

  private String steeringJob(String prompt) throws Exception {
    String id = service.dispatch("submit", request(prompt)).get("id").getAsString();
    await(() -> job(id).has("turn") && !job(id).get("turn").getAsString().isBlank());
    return id;
  }

  private JsonObject steering(String id, String text) {
    var data = new JsonObject();
    data.addProperty("id", id);
    data.addProperty("request", UUID.randomUUID().toString());
    data.add("turn", job(id).get("turn"));
    data.addProperty("text", text);
    return data;
  }

  private String steeringState(String id, JsonObject request) {
    for (var value : job(id).getAsJsonArray("steering")) {
      var receipt = value.getAsJsonObject();
      if (receipt.get("steer_request").equals(request.get("request")))
        return receipt.get("steer_state").getAsString();
    }
    return "";
  }

  @Test
  void steeringUsesTheActiveTurnAndConcurrentRetriesForwardOnlyOnce() throws Exception {
    ready();
    String id = steeringJob("STEER_FIXTURE");
    var data = steering(id, "delay 先处理兼容性问题，\n文档稍后再写。 $(literal)");
    service.dispatch("steer_job", data);
    try (var clients = Executors.newFixedThreadPool(8)) {
      List<Callable<JsonObject>> calls = new ArrayList<>();
      for (int i = 0; i < 8; i++) calls.add(() -> service.dispatch("steer_job", data.deepCopy()));
      for (var result : clients.invokeAll(calls))
        assertEquals(data.get("request"), result.get().get("steer_request"));
    }
    await(() -> steeringState(id, data).equals("accepted"));
    assertEquals(1, Files.readAllLines(root.resolve("steers")).size());
    var forwarded =
        JsonParser.parseString(Files.readAllLines(root.resolve("steers")).getFirst())
            .getAsJsonObject()
            .getAsJsonObject("params");
    assertEquals("fixture-session", forwarded.get("threadId").getAsString());
    assertEquals("fixture-turn", forwarded.get("expectedTurnId").getAsString());
    assertEquals(data.get("request"), forwarded.get("clientUserMessageId"));
    assertEquals(
        data.get("text"), forwarded.getAsJsonArray("input").get(0).getAsJsonObject().get("text"));
    var changed = data.deepCopy();
    changed.addProperty("text", "other instruction");
    assertEquals(
        "request_conflict",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("steer_job", changed)).key);
    var stale = steering(id, "stale");
    stale.addProperty("turn", "another-turn");
    assertEquals(
        "steer_inactive",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("steer_job", stale)).key);
    var finish = steering(id, "finish");
    service.dispatch("steer_job", finish);
    await(() -> job(id).get("state").getAsString().equals("completed"));
    assertTrue(job(id).get("result").getAsString().contains("文档稍后再写。"));
    assertEquals(1, Files.readAllLines(root.resolve("calls")).size());
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals("accepted", service.dispatch("steer_job", data).get("steer_state").getAsString());
    assertEquals(2, Files.readAllLines(root.resolve("steers")).size());
  }

  @Test
  void aRejectedSteeringRpcDoesNotFailTheOngoingTask() throws Exception {
    ready();
    String id = steeringJob("STEER_FIXTURE");
    for (String text : List.of("reject", "wrong_turn")) {
      var data = steering(id, text);
      service.dispatch("steer_job", data);
      await(() -> steeringState(id, data).equals("rejected"));
      assertEquals(
          "rejected", service.dispatch("steer_job", data).get("steer_state").getAsString());
      assertEquals("running", job(id).get("state").getAsString());
    }
    var finish = steering(id, "finish");
    service.dispatch("steer_job", finish);
    await(() -> job(id).get("state").getAsString().equals("completed"));
    assertEquals(3, Files.readAllLines(root.resolve("steers")).size());
  }

  @Test
  void missingAcknowledgementsAndCompletionRacesStayUnconfirmedWithoutResending() throws Exception {
    ready();
    for (String text : List.of("no_ack", "eof", "end_race")) {
      String id = steeringJob("STEER_FIXTURE");
      var data = steering(id, text);
      service.dispatch("steer_job", data);
      await(
          () -> {
            try {
              return Files.exists(root.resolve("steers"))
                  && Files.readString(root.resolve("steers"))
                      .contains(data.get("request").getAsString());
            } catch (Exception e) {
              return false;
            }
          });
      if (text.equals("no_ack")) {
        assertEquals(
            "pending", service.dispatch("steer_job", data).get("steer_state").getAsString());
        var cancel = new JsonObject();
        cancel.addProperty("id", id);
        service.dispatch("cancel_job", cancel);
      }
      await(() -> steeringState(id, data).equals("unknown"));
      assertEquals("unknown", service.dispatch("steer_job", data).get("steer_state").getAsString());
      assertNotEquals("running", job(id).get("state").getAsString());
    }
    assertEquals(3, Files.readAllLines(root.resolve("steers")).size());
    assertEquals(3, Files.readAllLines(root.resolve("calls")).size());
  }

  @Test
  void interruptedUnconfirmedSteeringCannotBeReplayedAfterRestart() throws Exception {
    ready();
    String id = steeringJob("STEER_FIXTURE");
    var data = steering(id, "no_ack");
    service.dispatch("steer_job", data);
    await(() -> Files.exists(root.resolve("steers")));
    service.close();
    service = new LocalCodex(config, database, root.resolve("workspace"));
    assertEquals("interrupted", job(id).get("state").getAsString());
    assertEquals("unknown", service.dispatch("steer_job", data).get("steer_state").getAsString());
    assertEquals(1, Files.readAllLines(root.resolve("steers")).size());
    assertFalse(job(id).toString().contains("digest"));
  }

  @Test
  void steeringRequiresACompleteActiveTaskAndDoesNotAnswerPendingQuestions() throws Exception {
    ready();
    String id = steeringJob("ASK_FIXTURE");
    waitQuestion(id);
    assertEquals(
        "steer_answer_first",
        assertThrows(
                LocalCodex.Failure.class,
                () -> service.dispatch("steer_job", steering(id, "do something")))
            .key);
    var invalid = steering(id, "");
    assertEquals(
        "invalid_text",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("steer_job", invalid)).key);
    invalid.addProperty("text", "x".repeat(4001));
    assertEquals(
        "invalid_text",
        assertThrows(LocalCodex.Failure.class, () -> service.dispatch("steer_job", invalid)).key);
    var queued = request("STEER_FIXTURE");
    String queuedId = service.dispatch("submit", queued).get("id").getAsString();
    assertEquals(
        "steer_inactive",
        assertThrows(
                LocalCodex.Failure.class,
                () -> service.dispatch("steer_job", steering(queuedId, "not yet")))
            .key);
    assertFalse(Files.exists(root.resolve("steers")));
  }

  @Test
  void aChildNotReadingSteeringCannotBlockCancellationOrShutdown() throws Exception {
    ready();
    String id = steeringJob("STEER_FIXTURE STEER_BLOCKED");
    await(() -> Files.exists(root.resolve("steer-started")));
    var data = steering(id, "中".repeat(4000));
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> {
          service.dispatch("steer_job", data);
          var cancel = new JsonObject();
          cancel.addProperty("id", id);
          service.dispatch("cancel_job", cancel);
        });
    await(() -> steeringState(id, data).equals("unknown"));
    assertEquals("cancelled", job(id).get("state").getAsString());
    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> service.close());
  }
}
