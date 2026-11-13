package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class AgentQuestionTest {
  private JsonObject request() {
    return JsonParser.parseString(
            "{\"questions\":[{\"id\":\"format\",\"header\":\"格式\",\"question\":\"请选择格式\",\"options\":[{\"label\":\"总结\",\"description\":\"概括重点\"}]}]}")
        .getAsJsonObject();
  }

  @Test
  void preservesChineseChoicesAndAllowsAnExplicitFreeTextAnswer() {
    var q = AgentQuestion.read(request());
    var answers = new JsonObject();
    answers.addProperty("format", " 我自己的格式 ");
    var response = q.validateAnswers(answers);
    assertEquals(
        "我自己的格式",
        response
            .getAsJsonObject("answers")
            .getAsJsonObject("format")
            .getAsJsonArray("answers")
            .get(0)
            .getAsString());
    assertEquals("总结", q.questions().getFirst().options().getFirst().label());
    assertNotEquals(q.request(), AgentQuestion.read(request()).request());
  }

  @Test
  void invalidOrIncompleteQuestionsAndAnswersAreRejectedWithoutTruncation() {
    var value = request();
    value.getAsJsonArray("questions").add(value.getAsJsonArray("questions").get(0).deepCopy());
    assertThrows(IllegalArgumentException.class, () -> AgentQuestion.read(value));
    var secret = request();
    secret.getAsJsonArray("questions").get(0).getAsJsonObject().addProperty("isSecret", true);
    assertThrows(IllegalArgumentException.class, () -> AgentQuestion.read(secret));
    var q = AgentQuestion.read(request());
    var answers = new JsonObject();
    answers.addProperty("format", " ");
    assertThrows(IllegalArgumentException.class, () -> q.validateAnswers(answers));
    answers.addProperty("format", "a".repeat(2001));
    assertThrows(IllegalArgumentException.class, () -> q.validateAnswers(answers));
    answers.addProperty("format", "yes");
    answers.addProperty("unknown", "no");
    assertThrows(IllegalArgumentException.class, () -> q.validateAnswers(answers));
  }
}
