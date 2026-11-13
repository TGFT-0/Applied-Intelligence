package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.util.*;

/**
 * Public CLI plan/action metadata only. Never stores reasoning, command output, or tool arguments.
 */
public final class JobProgress {
  public static final int MAX_STEPS = 24;
  public double started;
  public int contextWindow;
  public Usage usage;
  public boolean planned;
  public boolean truncated;
  public List<Step> steps = new ArrayList<>();

  public record Step(String id, String title, String kind, String state) {}

  /** Cached input is a subset of input; snapshots are cumulative for this CLI thread. */
  public record Usage(long inputTokens, long outputTokens, Long cachedInputTokens) {
    public Usage {
      if (inputTokens < 0
          || outputTokens < 0
          || cachedInputTokens != null
              && (cachedInputTokens < 0 || cachedInputTokens > inputTokens))
        throw new IllegalArgumentException("Invalid token usage");
      Math.addExact(inputTokens, outputTokens);
    }

    public long total() {
      return Math.addExact(inputTokens, outputTokens);
    }
  }

  public void observe(JsonObject event) {
    readUsage(event);
    readWindow(event);
    if (event.has("info") && event.get("info").isJsonObject())
      readWindow(event.getAsJsonObject("info"));
    if (!event.has("item") || !event.get("item").isJsonObject()) return;
    JsonObject item = event.getAsJsonObject("item");
    String kind = string(item, "type"), type = string(event, "type");
    if (kind.equals("todo_list") || kind.equals("plan")) {
      JsonArray items =
          item.has("items") && item.get("items").isJsonArray()
              ? item.getAsJsonArray("items")
              : item.has("steps") && item.get("steps").isJsonArray()
                  ? item.getAsJsonArray("steps")
                  : null;
      if (items == null) return;
      var next = new ArrayList<Step>();
      boolean active = false;
      for (var value : items) {
        if (next.size() >= MAX_STEPS) break;
        if (!value.isJsonObject()) continue;
        JsonObject step = value.getAsJsonObject();
        String title = clean(step.has("text") ? string(step, "text") : string(step, "step"));
        if (title.isBlank()) continue;
        String state = string(step, "status");
        boolean complete =
            (step.has("completed")
                    && step.get("completed").isJsonPrimitive()
                    && step.get("completed").getAsBoolean())
                || state.equals("completed");
        String phase =
            complete
                ? "completed"
                : state.equals("failed")
                    ? "failed"
                    : state.equals("in_progress") || (!active && state.isBlank())
                        ? "running"
                        : "queued";
        if (!complete) active = true;
        next.add(new Step("plan-" + next.size(), title, "plan", phase));
      }
      truncated = items.size() > MAX_STEPS;
      planned = !next.isEmpty();
      steps = next;
      return;
    }
    if (planned
        || !Set.of("command_execution", "web_search", "mcp_tool_call", "file_change")
            .contains(kind)) return;
    String id = string(item, "id");
    if (id.isBlank()) return;
    if (!type.equals("item.started") && !type.equals("item.completed")) return;
    String state =
        type.equals("item.started")
            ? "running"
            : string(item, "status").equals("failed") ? "failed" : "completed";
    for (int i = 0; i < steps.size(); i++)
      if (steps.get(i).id().equals(id)) {
        steps.set(i, new Step(id, "", kind, state));
        return;
      }
    if (steps.size() >= MAX_STEPS) truncated = true;
    if (steps.size() < MAX_STEPS) steps.add(new Step(id, "", kind, state));
  }

  private void readUsage(JsonObject event) {
    JsonObject value = null;
    if (string(event, "type").equals("turn.completed")
        && event.has("usage")
        && event.get("usage").isJsonObject()) value = event.getAsJsonObject("usage");
    if (string(event, "type").equals("token_count")
        && event.has("info")
        && event.get("info").isJsonObject()) {
      var info = event.getAsJsonObject("info");
      if (info.has("total_token_usage") && info.get("total_token_usage").isJsonObject())
        value = info.getAsJsonObject("total_token_usage");
    }
    if (value == null) return;
    try {
      var next =
          new Usage(
              count(value, "input_tokens"),
              count(value, "output_tokens"),
              value.has("cached_input_tokens") ? count(value, "cached_input_tokens") : null);
      // Do not add repeated totals, or let delayed snapshots move the counter backwards.
      if (usage == null || next.total() >= usage.total()) {
        if (usage != null
            && next.inputTokens() == usage.inputTokens()
            && next.outputTokens() == usage.outputTokens()
            && next.cachedInputTokens() == null)
          next = new Usage(next.inputTokens(), next.outputTokens(), usage.cachedInputTokens());
        usage = next;
      }
    } catch (RuntimeException ignored) {
      /* Keep the last complete, valid snapshot. */
    }
  }

  private static long count(JsonObject value, String key) {
    var number = value.get(key).getAsJsonPrimitive();
    if (!number.isNumber()) throw new IllegalArgumentException("Token count must be a number");
    return number.getAsBigDecimal().longValueExact();
  }

  private void readWindow(JsonObject value) {
    if (value.has("model_context_window"))
      try {
        int n = value.get("model_context_window").getAsInt();
        if (n > 0 && n <= 2_000_000) contextWindow = n;
      } catch (RuntimeException ignored) {
      }
  }

  private static String string(JsonObject value, String key) {
    return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
  }

  private static String clean(String text) {
    String s = text.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    return s.substring(0, s.offsetByCodePoints(0, Math.min(64, s.codePointCount(0, s.length()))));
  }
}
