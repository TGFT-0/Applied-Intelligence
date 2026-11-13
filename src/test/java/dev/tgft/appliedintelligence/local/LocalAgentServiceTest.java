package dev.tgft.appliedintelligence.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAgentServiceTest {
  @TempDir Path root;

  private LocalCodex runner() {
    return new LocalCodex(
        root.resolve("config.json"), root.resolve("jobs.json"), root.resolve("workspace"));
  }

  @Test
  void retiredControlsCannotWriteConfigurationOrStartSharing() throws Exception {
    Path config = root.resolve("config.json");
    try (var service = new LocalAgentService(runner(), root.resolve("routes.json"))) {
      for (String action :
          List.of(
              "host_configure",
              "host_pair_code",
              "host_settings",
              "remote_pair",
              "remote_use_local")) {
        var data = new JsonObject();
        data.addProperty("enabled", true);
        data.addProperty("endpoint", "http://127.0.0.1:1");
        data.addProperty("code", "unused");
        var result = service.dispatch(action, data);
        assertEquals("remote_disabled", result.get("error_key").getAsString());
        assertFalse(result.get("retryable").getAsBoolean());
      }
      assertFalse(Files.exists(config));
      assertFalse(Files.exists(root.resolve("identity.json")));
    }
  }

  @Test
  void oldRemoteTargetsAndIdsAreBlockedInsteadOfReroutedLocally() throws Exception {
    String id = UUID.randomUUID().toString();
    Path routes = root.resolve("routes.json");
    Files.writeString(
        routes,
        "{\"tickets\":{\""
            + id
            + "\":{\"target\":\"old-host\",\"acknowledged\":false,\"request\":{\"prompt\":\"do not"
            + " replay\"}}}}");
    String original = Files.readString(routes);
    try (var service = new LocalAgentService(runner(), routes)) {
      for (String action :
          List.of("submit", "job", "cancel_job", "answer_job", "steer_job", "release_job")) {
        var data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("target", "local");
        assertEquals(
            "remote_disabled", service.dispatch(action, data).get("error_key").getAsString());
      }
      var target = new JsonObject();
      target.addProperty("target", "old-host");
      assertEquals(
          "remote_disabled", service.dispatch("agents", target).get("error_key").getAsString());
      var status = new JsonObject();
      status.addProperty("job", id);
      assertEquals(
          "remote_disabled",
          service.dispatch("status_jobs", status).get("error_key").getAsString());
      assertEquals(original, Files.readString(routes));
      assertFalse(Files.exists(root.resolve("workspace")));
    }
  }

  @Test
  void alreadyArchivedRemoteBookImagesRemainAvailableWithoutAConnection() throws Exception {
    String id = UUID.randomUUID().toString();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", bytes);
    var saved =
        new ImageAssets(root.resolve("report-images"))
            .capture(id, bytes.toByteArray(), "fixture.png");
    Files.writeString(
        root.resolve("routes.json"),
        "{\"tickets\":{\"" + id + "\":{\"target\":\"withdrawn-host\"}}}");
    try (var service = new LocalAgentService(runner(), root.resolve("routes.json"))) {
      var query = new JsonObject();
      query.addProperty("id", id);
      query.add("image", saved.get("id"));
      query.addProperty("offset", 0);
      var image = service.dispatch("image_chunk", query);
      assertFalse(image.has("error_key"), image.toString());
      assertEquals(
          saved.get("id").getAsString(),
          ImageAssets.digest(Base64.getDecoder().decode(image.get("data").getAsString())));
    }
  }

  @Test
  void withdrawnCpuCanBeClearedWithoutMisidentifyingANewLocalOrder() throws Exception {
    String id = UUID.randomUUID().toString(), recipe = "a".repeat(64);
    Path routes = root.resolve("routes.json");
    Files.writeString(
        routes,
        "{\"tickets\":{\""
            + id
            + "\":{\"target\":\"old-host\",\"request\":{\"recipe\":\""
            + recipe
            + "\"}}}}");
    try (var service = new LocalAgentService(runner(), routes)) {
      var query = new JsonObject();
      query.addProperty("recipe", recipe);
      query.addProperty("cpu_started", System.currentTimeMillis() / 1000.0 - 3600);
      var old = service.dispatch("status_jobs", query).getAsJsonObject("selected_job");
      assertEquals(id, old.get("id").getAsString());
      assertEquals("interrupted", old.get("state").getAsString());
      query.addProperty("cpu_started", System.currentTimeMillis() / 1000.0);
      assertFalse(service.dispatch("status_jobs", query).has("selected_job"));
      var cancel = new JsonObject();
      cancel.addProperty("id", id);
      var ack = service.dispatch("cancel_job", cancel);
      assertEquals(id, ack.get("cancel_ack").getAsString());
      assertEquals("remote_disabled", ack.get("error_key").getAsString());
      assertFalse(Files.exists(root.resolve("workspace")));
    }
  }

  @Test
  void malformedLegacyRoutingHistoryFailsClosed() throws Exception {
    Path routes = root.resolve("routes.json");
    Files.writeString(routes, "{\"tickets\":\"corrupt\"}");
    try (var runner = runner()) {
      assertThrows(java.io.IOException.class, () -> new LocalAgentService(runner, routes));
    }
  }
}
