package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.util.regex.*;

/** Normalize public data without forwarding native tool arguments, diagnostics or reasoning. */
final class SessionEvents {
  static String string(JsonObject value, String key) {
    return value != null && value.has(key) && value.get(key).isJsonPrimitive()
        ? value.get(key).getAsString()
        : "";
  }

  static long count(JsonObject value, String key) {
    try {
      return Math.max(0, value.get(key).getAsLong());
    } catch (RuntimeException e) {
      return 0;
    }
  }

  static void action(AgentSession.Listener listener, String id, boolean completed, boolean failed) {
    if (id.isBlank()) return;
    var event = new JsonObject();
    event.addProperty("type", completed ? "item.completed" : "item.started");
    var item = new JsonObject();
    item.addProperty("id", id);
    item.addProperty("type", "mcp_tool_call");
    item.addProperty("status", failed ? "failed" : completed ? "completed" : "in_progress");
    event.add("item", item);
    listener.event(event);
  }

  static void usage(AgentSession.Listener listener, long input, long output, Long cache) {
    var event = new JsonObject();
    event.addProperty("type", "token_count");
    var info = new JsonObject();
    var usage = new JsonObject();
    usage.addProperty("input_tokens", input);
    usage.addProperty("output_tokens", output);
    if (cache != null) usage.addProperty("cached_input_tokens", cache);
    info.add("total_token_usage", usage);
    event.add("info", info);
    listener.event(event);
  }

  static void window(AgentSession.Listener listener, JsonObject value, String key) {
    long size = count(value, key);
    if (size < 1 || size > 2_000_000) return;
    var event = new JsonObject();
    event.addProperty("type", "token_count");
    var info = new JsonObject();
    info.addProperty("model_context_window", size);
    event.add("info", info);
    listener.event(event);
  }

  static String content(AgentSession.Listener listener, JsonArray blocks) {
    var text = new StringBuilder();
    if (blocks == null) return "";
    for (var element : blocks) {
      if (!element.isJsonObject()) continue;
      var block = element.getAsJsonObject();
      String type = string(block, "type");
      if (type.equals("text")) {
        String value = string(block, "text");
        text.append(value, 0, Math.min(value.length(), Math.max(0, 16000 - text.length())));
      } else if (type.equals("image")) listener.image(null, string(block, "data"));
    }
    return text.toString();
  }

  static String markdown(AgentSession.Listener listener, String text) {
    var matcher = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(text);
    var cleaned = new StringBuffer();
    while (matcher.find()) {
      String path = matcher.group(2).trim();
      if (!path.contains("://") && !path.startsWith("data:")) listener.image(path, null);
      matcher.appendReplacement(cleaned, Matcher.quoteReplacement("[" + matcher.group(1) + "]"));
    }
    matcher.appendTail(cleaned);
    return cleaned.substring(0, Math.min(16000, cleaned.length()));
  }
}
