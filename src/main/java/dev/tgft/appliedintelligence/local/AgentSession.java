package dev.tgft.appliedintelligence.local;

import com.google.gson.*;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/** A task-scoped official CLI transport. Unavailable capabilities must fail explicitly. */
interface AgentSession {
  interface Listener {
    void event(JsonObject event);

    void session(String id);

    void turn(String id);

    void question(JsonElement rpcId, JsonObject params);

    void resolved(JsonElement rpcId);

    default void image(String path, String inline) {}
  }

  record Result(boolean success, String text) {}

  record Steering(String state, String error) {}

  Result run(String cwd, String prompt) throws IOException;

  void answer(JsonElement id, JsonObject result) throws IOException;

  CompletableFuture<Steering> steer(String requestId, String expectedTurn, String text);
}
