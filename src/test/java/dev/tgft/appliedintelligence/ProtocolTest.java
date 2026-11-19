package dev.tgft.appliedintelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  @Test
  void parseBoundedObjectRejectsDeepNestingTrailingTokensDuplicateKeysAndHugeNumbers() {
    String deep = "{\"a\":".repeat(64) + "{}" + "}".repeat(64);
    assertThrows(
        IllegalArgumentException.class, () -> Protocol.parseBoundedObject(deep, 32768, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseBoundedObject("{\"a\":1}{\"b\":2}", 32768, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseBoundedObject("{\"a\":1,\"a\":2}", 32768, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseBoundedObject("{\"n\":1e999999}", 32768, 16));
    assertThrows(
        IllegalArgumentException.class,
        () -> Protocol.parseBoundedObject("{\"s\":\"" + "中".repeat(12000) + "\"}", 32768, 16));

    var valid = Protocol.parseBoundedObject("{\"action\":\"agents\",\"data\":{}}", 32768, 16);
    assertEquals("agents", valid.get("action").getAsString());
  }

  @Test
  void clientTransportFailsClosedWhenMultiplayerOrLanDisabled() {
    var previousReceiver = Protocol.ClientReceiver.receiver;
    var previousAllow = Protocol.ClientReceiver.allowClientTransport;
    List<String> delivered = new ArrayList<>();
    try {
      Protocol.ClientReceiver.receiver = delivered::add;
      Protocol.ClientReceiver.allowClientTransport = () -> false;

      Protocol.ClientReceiver.acceptRemote("{\"local\":{\"status\":\"local_ready\"}}");
      assertTrue(delivered.isEmpty());

      Protocol.send("local_settings", new JsonObject());
      assertEquals(1, delivered.size());
      assertEquals(
          "singleplayer_only",
          JsonParser.parseString(delivered.getFirst())
              .getAsJsonObject()
              .get("error_key")
              .getAsString());

      delivered.clear();
      Protocol.ClientReceiver.allowClientTransport = () -> true;
      Protocol.ClientReceiver.acceptRemote("{\"a\":".repeat(32) + "{}" + "}".repeat(32));
      assertTrue(delivered.isEmpty());

      Protocol.ClientReceiver.acceptRemote("{\"id\":\"" + UUID.randomUUID() + "\"}");
      assertEquals(1, delivered.size());
    } finally {
      Protocol.ClientReceiver.receiver = previousReceiver;
      Protocol.ClientReceiver.allowClientTransport = previousAllow;
    }
  }
}
