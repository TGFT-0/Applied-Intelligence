package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class JobProgressTest {
  private JsonObject event(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }

  @Test
  void updatesRealPlanWithoutAccumulatingDuplicateSteps() {
    var p = new JobProgress();
    p.observe(
        event(
            "{\"type\":\"item.updated\",\"item\":{\"type\":\"todo_list\",\"items\":[{\"text\":\"读取资料\",\"completed\":false},{\"text\":\"执行\",\"completed\":false}]}}"));
    assertTrue(p.planned);
    assertEquals("running", p.steps.get(0).state());
    assertEquals("queued", p.steps.get(1).state());
    p.observe(
        event(
            "{\"type\":\"item.updated\",\"item\":{\"type\":\"todo_list\",\"items\":[{\"text\":\"读取资料\",\"completed\":true},{\"text\":\"执行\",\"completed\":false}]}}"));
    assertEquals(2, p.steps.size());
    assertEquals("completed", p.steps.get(0).state());
    assertEquals("running", p.steps.get(1).state());
  }

  @Test
  void observedToolsAreNotInventedPlanOrPrivateReasoning() {
    var p = new JobProgress();
    p.observe(
        event(
            "{\"type\":\"item.started\",\"item\":{\"id\":\"r\",\"type\":\"reasoning\",\"text\":\"PRIVATE\"}}"));
    assertTrue(p.steps.isEmpty());
    p.observe(
        event(
            "{\"type\":\"item.started\",\"item\":{\"id\":\"c\",\"type\":\"command_execution\",\"command\":\"SECRET\"}}"));
    p.observe(
        event(
            "{\"type\":\"item.completed\",\"item\":{\"id\":\"c\",\"type\":\"command_execution\",\"aggregated_output\":\"SECRET\"}}"));
    assertFalse(p.planned);
    assertEquals(1, p.steps.size());
    assertEquals("completed", p.steps.get(0).state());
    assertFalse(new Gson().toJson(p).contains("SECRET"));
    assertFalse(new Gson().toJson(p).contains("PRIVATE"));
  }

  @Test
  void capacityIsUnknownUnlessExplicitlyReported() {
    var p = new JobProgress();
    assertEquals(0, p.contextWindow);
    p.observe(event("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":200000}}"));
    assertEquals(0, p.contextWindow);
    p.observe(event("{\"type\":\"token_count\",\"info\":{\"model_context_window\":128000}}"));
    assertEquals(128000, p.contextWindow);
    p.observe(event("{\"model_context_window\":-1}"));
    assertEquals(128000, p.contextWindow);
  }

  @Test
  void longPlansHaveBoundedPublicLabelsAndSteps() {
    var p = new JobProgress();
    var item = new JsonObject();
    item.addProperty("type", "todo_list");
    var steps = new JsonArray();
    for (int i = 0; i < 100; i++) {
      var step = new JsonObject();
      step.addProperty("text", "中🙂".repeat(100));
      step.addProperty("completed", false);
      steps.add(step);
    }
    item.add("items", steps);
    var event = new JsonObject();
    event.add("item", item);
    p.observe(event);
    assertEquals(24, p.steps.size());
    assertTrue(p.truncated);
    assertEquals(64, p.steps.get(0).title().codePointCount(0, p.steps.get(0).title().length()));
  }

  @Test
  void cumulativeExecUsageDoesNotDoubleCountCacheOrRepeatedCompletion() {
    var p = new JobProgress();
    var report =
        event(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":16480,\"cached_input_tokens\":12000,\"output_tokens\":2160,\"reasoning_output_tokens\":1000}}");
    p.observe(report);
    p.observe(report);
    assertEquals(18640, p.usage.total());
    assertEquals(12000, p.usage.cachedInputTokens());
    assertEquals(2160, p.usage.outputTokens());
    assertEquals(0, p.contextWindow);
  }

  @Test
  void liveTotalSnapshotsAndFinalExecSummaryShareOneCounter() {
    var p = new JobProgress();
    p.observe(
        event(
            "{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":12000,\"cached_input_tokens\":8000,\"output_tokens\":640}}}"));
    assertEquals(12640, p.usage.total());
    p.observe(
        event(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":16480,\"cached_input_tokens\":12000,\"output_tokens\":2160}}"));
    p.observe(
        event(
            "{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{\"input_tokens\":12000,\"output_tokens\":640}}}"));
    assertEquals(18640, p.usage.total());
    p.observe(
        event(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":16480,\"output_tokens\":2160}}"));
    assertEquals(12000, p.usage.cachedInputTokens());
  }

  @Test
  void missingUsageAndUnknownCacheRemainDistinctFromReportedZero() {
    var p = new JobProgress();
    p.observe(event("{\"type\":\"turn.completed\"}"));
    assertNull(p.usage);
    p.observe(
        event("{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}"));
    assertEquals(0, p.usage.total());
    assertNull(p.usage.cachedInputTokens());
    assertNull(new JobProgress().usage);
  }

  @Test
  void malformedCountsCannotReplaceAValidSnapshot() {
    var p = new JobProgress();
    p.observe(
        event(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":100,\"output_tokens\":20}}"));
    for (String bad :
        new String[] {
          "{\"input_tokens\":-1,\"output_tokens\":200}",
          "{\"input_tokens\":200,\"output_tokens\":0.5}",
          "{\"input_tokens\":\"200\",\"output_tokens\":30}",
          "{\"input_tokens\":200,\"output_tokens\":30,\"cached_input_tokens\":201}",
          "{\"input_tokens\":200}",
          "{\"input_tokens\":9223372036854775807,\"output_tokens\":1}"
        }) {
      p.observe(event("{\"type\":\"turn.completed\",\"usage\":" + bad + "}"));
      assertEquals(120, p.usage.total());
    }
  }

  @Test
  void usageSurvivesSerializationAndLargeCountsStayExact() {
    var p = new JobProgress();
    p.observe(
        event(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":3000000000,\"cached_input_tokens\":2000000000,\"output_tokens\":7}}"));
    var json = new Gson();
    var recovered = json.fromJson(json.toJson(p), JobProgress.class);
    assertEquals(3000000007L, recovered.usage.total());
    assertEquals(p.usage, recovered.usage);
  }
}
