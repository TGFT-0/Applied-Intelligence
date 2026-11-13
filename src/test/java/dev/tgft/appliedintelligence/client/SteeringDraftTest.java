package dev.tgft.appliedintelligence.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class SteeringDraftTest {
  private JsonObject receipt(JsonObject request, String state) {
    var receipt = new JsonObject();
    receipt.add("steer_request", request.get("request"));
    receipt.addProperty("steer_state", state);
    return receipt;
  }

  @Test
  void retriesKeepTheirOriginalTextTurnAndMessageIdentityUntilConfirmed() {
    var draft = new SteeringDraft();
    draft.edit("先处理兼容性问题，\n文档稍后再写。");
    var first = draft.send("job-a", "turn-a", 1000);
    assertFalse(draft.editable());
    assertFalse(draft.retryable(10999));
    assertTrue(draft.retryable(11000));
    draft.edit("changed");
    var retry = draft.send("job-a", "turn-b", 12000);
    assertEquals(first, retry);
    draft.receive(receipt(first, "accepted"));
    assertTrue(draft.editable());
    assertEquals("", draft.text);
    assertEquals("steer_accepted", draft.status(13000));
  }

  @Test
  void lostTransportRepliesRetainTheDraftAndStaleRepliesCannotClearANewMessage() {
    var draft = new SteeringDraft();
    draft.edit("first");
    var first = draft.send("job-a", "turn-a", 1);
    var offline = new JsonObject();
    offline.add("steer_request", first.get("request"));
    offline.addProperty("error_key", "remote_unavailable");
    draft.receive(offline);
    assertTrue(draft.retryable(2));
    assertFalse(draft.editable());
    assertEquals("first", draft.text);
    assertEquals(first, draft.send("job-a", "turn-a", 2));
    draft.receive(receipt(first, "accepted"));
    draft.edit("second");
    var second = draft.send("job-a", "turn-a", 3);
    draft.receive(receipt(first, "accepted"));
    assertEquals("second", draft.text);
    assertEquals(second.get("request").getAsString(), draft.request);
  }

  @Test
  void confirmedRejectionKeepsEditableTextAndNewSendingUsesANewIdentity() {
    var draft = new SteeringDraft();
    draft.edit("keep me");
    var first = draft.send("job-a", "turn-a", 1);
    draft.receive(receipt(first, "rejected"));
    assertTrue(draft.editable());
    assertEquals("keep me", draft.text);
    var second = draft.send("job-a", "turn-a", 2);
    assertNotEquals(first.get("request"), second.get("request"));
    draft.receive(receipt(second, "unknown"));
    assertFalse(draft.retryable(50000));
    assertFalse(draft.editable());
    assertEquals("keep me", draft.text);
  }
}
