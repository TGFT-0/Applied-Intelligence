package dev.tgft.appliedintelligence.client;

import com.google.gson.*;
import java.util.UUID;

/**
 * One job's draft and immutable in-flight message identity. No Minecraft or transport ownership.
 */
final class SteeringDraft {
  String text = "", request = "", turn = "", sentText = "", state = "", error = "";
  long lastSend;

  boolean editable() {
    return request.isBlank();
  }

  void edit(String value) {
    if (!editable()) return;
    text = value;
    state = "";
    error = "";
  }

  boolean retryable(long now) {
    return !request.isBlank()
        && !state.equals("unknown")
        && (state.equals("retry") || now - lastSend >= 10000);
  }

  JsonObject send(String jobId, String currentTurn, long now) {
    if (request.isBlank()) {
      request = UUID.randomUUID().toString();
      turn = currentTurn;
      sentText = text.trim();
    }
    state = "pending";
    error = "";
    lastSend = now;
    var data = new JsonObject();
    data.addProperty("id", jobId);
    data.addProperty("request", request);
    data.addProperty("turn", turn);
    data.addProperty("text", sentText);
    return data;
  }

  void receive(JsonObject value) {
    if (request.isBlank()
        || !value.has("steer_request")
        || !request.equals(value.get("steer_request").getAsString())) return;
    if (value.has("steer_state")) {
      state = value.get("steer_state").getAsString();
      error = value.has("error_key") ? value.get("error_key").getAsString() : "";
      if (state.equals("accepted")) {
        text = "";
        request = "";
        sentText = "";
      } else if (state.equals("rejected")) request = "";
    } else if (value.has("error_key")) {
      error = value.get("error_key").getAsString();
      if (error.startsWith("remote_") || error.equals("response_too_large")) state = "retry";
      else {
        state = "rejected";
        request = "";
      }
    }
  }

  String status(long now) {
    if (state.equals("accepted")) return "steer_accepted";
    if (!error.isBlank()) return error;
    return state.equals("pending") ? retryable(now) ? "steer_unconfirmed" : "steer_sending" : "";
  }
}
