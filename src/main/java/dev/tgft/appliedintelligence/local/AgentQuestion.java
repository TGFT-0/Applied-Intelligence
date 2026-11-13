package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A bounded public input request. RPC identities and answers never enter item data. */
public record AgentQuestion(String request, List<Question> questions) {
  public record Option(String label, String description) {}

  public record Question(String id, String header, String question, List<Option> options) {}

  static AgentQuestion read(JsonObject params) {
    var values = params.getAsJsonArray("questions");
    if (values == null || values.isEmpty() || values.size() > 6)
      throw new IllegalArgumentException();
    var questions = new ArrayList<Question>();
    var ids = new HashSet<String>();
    for (var value : values) {
      var q = value.getAsJsonObject();
      if (q.has("isSecret") && q.get("isSecret").getAsBoolean())
        throw new IllegalArgumentException();
      String id = text(q, "id", 128),
          header = text(q, "header", 80),
          body = text(q, "question", 2000);
      if (!ids.add(id)) throw new IllegalArgumentException();
      var options = new ArrayList<Option>();
      if (q.has("options") && !q.get("options").isJsonNull()) {
        var array = q.getAsJsonArray("options");
        if (array.size() > 8) throw new IllegalArgumentException();
        for (var option : array) {
          var o = option.getAsJsonObject();
          options.add(new Option(text(o, "label", 160), text(o, "description", 500)));
        }
      }
      questions.add(new Question(id, header, body, List.copyOf(options)));
    }
    var result = new AgentQuestion(UUID.randomUUID().toString(), List.copyOf(questions));
    if (new Gson().toJson(result).getBytes(StandardCharsets.UTF_8).length > 22000)
      throw new IllegalArgumentException();
    return result;
  }

  private static String text(JsonObject value, String key, int max) {
    var p = value.getAsJsonPrimitive(key);
    if (p == null || !p.isString()) throw new IllegalArgumentException();
    String s = p.getAsString();
    if (s.isBlank() || s.length() > max) throw new IllegalArgumentException();
    return s;
  }

  JsonObject validateAnswers(JsonObject answers) {
    if (answers.size() != questions.size()) throw new IllegalArgumentException();
    var clean = new JsonObject();
    for (var question : questions) {
      String answer = text(answers, question.id(), 2000).trim();
      var response = new JsonObject();
      var list = new JsonArray();
      list.add(answer);
      response.add("answers", list);
      clean.add(question.id(), response);
    }
    var result = new JsonObject();
    result.add("answers", clean);
    if (result.toString().getBytes(StandardCharsets.UTF_8).length > 24000)
      throw new IllegalArgumentException();
    return result;
  }

  static String digest(JsonObject answers) {
    try {
      var canonical = new TreeMap<String, String>();
      answers.entrySet().forEach(e -> canonical.put(e.getKey(), e.getValue().getAsString().trim()));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(new Gson().toJson(canonical).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
